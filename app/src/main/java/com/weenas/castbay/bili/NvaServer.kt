package com.weenas.castbay.bili

import com.weenas.castbay.dlna.Ssdp
import com.weenas.castbay.dlna.UpnpDescriptions
import com.weenas.castbay.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives Bilibili's cast ("哔哩必连" / NVA) on one port, which carries two things at once:
 * device and service descriptions over HTTP, and the control channel the phone opens with
 * `SETUP /projection NVA/1.0` before switching that same connection to binary frames.
 *
 * This build is a probe: it answers every command and writes what arrived, but does not fetch or
 * play anything. It exists to confirm against a real phone what the wire format actually is —
 * whether the TV appears under the phone's cast button, and what `Play` really carries — before
 * the playback path is built on top of it.
 */
class NvaServer(
    private val uuid: String,
    private val friendlyName: () -> String
) {
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    /** Listens on [preferredPort] when free, else on any port; returns the one used. */
    fun start(preferredPort: Int = Nva.PORT): Int {
        val socket = ServerSocket().apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(preferredPort))
            } catch (taken: java.io.IOException) {
                if (preferredPort == 0) throw taken
                Log.i(TAG, "Bilibili port $preferredPort is taken (云视听小电视?), using another one")
                bind(InetSocketAddress(0))
            }
        }
        server = socket
        running = true
        val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "Bili-nva").apply { isDaemon = true } }
        workers = pool
        Thread({
            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: SocketException) {
                    break
                }
                pool.execute { runCatching { serve(client) }.onFailure { Log.w(TAG, "Bilibili connection failed", it) } }
            }
        }, "Bili-nva-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
    }

    // ---- connections -------------------------------------------------------------------------

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val protocol = parts[2]
            val headers = readHeaders(input)
            val from = socket.inetAddress.hostAddress.orEmpty()

            if (protocol.startsWith("NVA/", ignoreCase = true)) {
                handshake(socket, input, output, method, path, headers, from)
                return
            }
            val body = readBody(input, headers)
            respond(socket, output, method, path, headers, body, from)
        }
    }

    private fun handshake(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        method: String,
        path: String,
        headers: Map<String, String>,
        from: String
    ) {
        val session = headers["session"].orEmpty().ifEmpty { java.util.UUID.randomUUID().toString() }
        Log.i(TAG, "Bilibili control channel: $method $path from $from (client ${short(headers["uuid"])}, session ${short(session)})")
        // A read timeout, not a blocking read: the frame loop then wakes up and sees `stop()`.
        socket.soTimeout = 1_000
        synchronized(output) {
            output.write(Nva.handshake(session, uuid).toByteArray(Charsets.UTF_8))
            output.flush()
        }
        val counter = AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val pinger = Thread({
            while (!stop.get() && running) {
                try {
                    synchronized(output) {
                        output.write(Nva.ping(counter.incrementAndGet()))
                        output.flush()
                    }
                } catch (error: Exception) {
                    break
                }
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        }, "Bili-nva-ping").apply { isDaemon = true }
        pinger.start()
        try {
            frames(input, output, counter)
        } finally {
            stop.set(true)
            pinger.interrupt()
            Log.i(TAG, "Bilibili control channel closed ($from)")
        }
    }

    private fun frames(input: InputStream, output: OutputStream, counter: AtomicInteger) {
        var cache = ByteArray(0)
        val buffer = ByteArray(4096)
        while (running) {
            val read = try {
                input.read(buffer)
            } catch (timeout: SocketTimeoutException) {
                continue
            } catch (closed: Exception) {
                break
            }
            if (read < 0) break
            val merged = ByteArray(cache.size + read)
            cache.copyInto(merged, 0)
            buffer.copyInto(merged, cache.size, 0, read)
            cache = merged

            val commands = mutableListOf<Nva.Command>()
            val consumed = Nva.parse(cache, cache.size, commands)
            if (consumed > 0) cache = cache.copyOfRange(consumed, cache.size)
            if (consumed == 0 && cache.size > MAX_FRAME_BYTES) break
            commands.forEach { command -> handle(command, output, counter) }
        }
    }

    /** Answers a command so the phone never waits, and writes what it said. */
    private fun handle(command: Nva.Command, output: OutputStream, counter: AtomicInteger) {
        Log.i(TAG, "Bilibili → ${command.method} ${describe(command)}")
        val reply = when (command.method) {
            "GetVolume" -> Nva.reply(command.counter, """{"volume":0}""")
            else -> Nva.ack(command.counter)
        }
        send(output, reply)
    }

    private fun send(output: OutputStream, bytes: ByteArray) {
        runCatching {
            synchronized(output) {
                output.write(bytes)
                output.flush()
            }
        }.onFailure { Log.w(TAG, "Could not answer Bilibili", it) }
    }

    // ---- HTTP (descriptions and SOAP) --------------------------------------------------------

    private fun respond(
        socket: Socket,
        output: OutputStream,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        from: String
    ) {
        val service = UpnpDescriptions.SERVICES.firstOrNull { path.startsWith("/${it.name}/") }
        when {
            path == Nva.DESCRIPTION_PATH || path == UpnpDescriptions.DESCRIPTION_PATH -> {
                Log.i(TAG, "Bilibili asked for the device description from $from (${headers["user-agent"].orEmpty()})")
                write(socket, output, 200, "OK", Nva.device(friendlyName(), uuid))
            }
            path == Nva.NIRVANA_SCPD -> write(socket, output, 200, "OK", Nva.SCPD)
            service != null && path == service.scpdPath -> write(socket, output, 200, "OK", UpnpDescriptions.scpd(service))
            path == Nva.NIRVANA_CONTROL || (service != null && path == service.controlPath) -> {
                val action = soapAction(body, headers)
                Log.i(TAG, "Bilibili SOAP ${if (service != null) service.name else Nva.SERVICE_NAME}.$action from $from")
                write(socket, output, 200, "OK", soapResponse(action, service?.type ?: Nva.SERVICE_TYPE))
            }
            path == Nva.NIRVANA_EVENT || (service != null && path == service.eventPath) -> {
                Log.i(TAG, "Bilibili subscribed to events from $from")
                write(socket, output, 200, "OK", "", listOf("SID" to "uuid:${java.util.UUID.randomUUID()}", "TIMEOUT" to "Second-300"))
            }
            else -> {
                Log.i(TAG, "Bilibili request $method $path from $from (not handled)")
                write(socket, output, 404, "Not Found", "")
            }
        }
    }

    private fun write(
        socket: Socket,
        output: OutputStream,
        status: Int,
        reason: String,
        body: String,
        headers: List<Pair<String, String>> = emptyList()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Server: ${Nva.SERVER}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            if (bytes.isNotEmpty()) append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Date: ${Nva.httpDate()}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            synchronized(output) {
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.write(bytes)
                output.flush()
            }
        }
    }

    private fun soapAction(body: String, headers: Map<String, String>): String {
        headers["soapaction"]?.trim('"')?.substringAfterLast('#')?.takeIf { it.isNotEmpty() }?.let { return it }
        val match = Regex("<(?:\\w+:)?([A-Za-z]+)\\s*[>/]").find(body.substringAfter("<s:Body>", body))
        return match?.groupValues?.get(1).orEmpty().ifEmpty { "Unknown" }
    }

    private fun soapResponse(action: String, serviceType: String): String {
        val args = when (action) {
            // Values a stock box reports; the probe only needs the phone to get past this step.
            "GetAppInfo" -> "<PackageName>com.xiaodianshi.tv.yst</PackageName><AppKey>0000000000000000</AppKey>" +
                "<Signature>0000000000000000</Signature><CurrentSignedIn>1</CurrentSignedIn>"
            "GetAccountInfo" -> "<VipInfo>0</VipInfo>"
            else -> ""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="$serviceType">$args</u:${action}Response></s:Body>
</s:Envelope>
"""
    }

    // ---- request parsing ---------------------------------------------------------------------

    private fun readHeaders(input: InputStream): Map<String, String> =
        Ssdp.headers(generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList())

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) break
                out.write(readBytes(input, size))
                readLine(input)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
        val length = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        return String(readBytes(input, length), Charsets.UTF_8)
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = ByteArray(minOf(length, MAX_BODY_BYTES))
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
            if (line.size() < MAX_LINE_BYTES) line.write(b)
        }
    }

    // ---- logging -----------------------------------------------------------------------------

    /**
     * A command's payload, with the account token masked: enough to see the shape of the wire
     * format without copying a credential into a log that may end up in a problem report.
     */
    private fun describe(command: Nva.Command): String {
        val payload = command.payload ?: return "(无参数)"
        val masked = payload
            .replace(Regex("(\"accessKey\"\\s*:\\s*\")([^\"]*)(\")")) { match ->
                val key = match.groupValues[2]
                match.groupValues[1] + key.take(6) + "…(共 ${key.length} 位)" + match.groupValues[3]
            }
            .replace(Regex("(\"access_key\"\\s*:\\s*\")([^\"]*)(\")")) { match ->
                val key = match.groupValues[2]
                match.groupValues[1] + key.take(6) + "…(共 ${key.length} 位)" + match.groupValues[3]
            }
        return if (masked.length <= MAX_LOG_BYTES) masked else masked.take(MAX_LOG_BYTES) + "…(${masked.length} 字节)"
    }

    private fun short(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        return if (value.length <= 10) value else "…" + value.takeLast(8)
    }

    private companion object {
        const val TAG = "CastBayBili"
        const val READ_TIMEOUT_MS = 10_000
        const val PING_INTERVAL_MS = 1_000L
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_FRAME_BYTES = 512 * 1024
        const val MAX_LOG_BYTES = 1_200
    }
}
