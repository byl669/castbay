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
 * The phone sends `Play` with the account's `accessKey`; this side fetches the streams
 * ([BiliApi]), serves the timeline as a local MPD, plays it through [player], and reports
 * progress, quality and state back once a second — the phone's own controls (progress bar,
 * quality menu, speed) are drawn from those reports alone.
 */
class NvaServer(private val uuid: String, private val player: NvaPlayer) {
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    /**
     * The MPD for what is playing now, or null when the API gave a single muxed file.
     * Kept with its version: the player may retry a URL from a previous cast, and answering that
     * with the current timeline is how a half-old manifest reaches the XML parser.
     */
    private class Manifest(val version: Int, val body: String)

    @Volatile private var manifest: Manifest? = null

    /** Bumped for each new play, so the player reloads a manifest that changed behind its URL. */
    @Volatile private var manifestVersion = 0

    /** The control connection's writer, while a phone is connected: where reports go. */
    @Volatile private var control: OutputStream? = null

    private val sendCounter = AtomicInteger(0)

    @Volatile private var current: Nva.PlayRequest? = null
    @Volatile private var currentQn = 0

    /** The height the phone is told about; [currentQn] is a quality id (80), not a resolution. */
    @Volatile private var currentHeight = 0
    @Volatile private var qualities: List<BiliApi.Quality> = emptyList()
    @Volatile private var volume = 100

    /** Kept so a quality switch carries the speed over instead of dropping back to 1x. */
    @Volatile private var speed = 1f

    /** The play state last reported, so only changes are sent. */
    @Volatile private var reportedPlaying: Boolean? = null
    @Volatile private var successSent = false

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
        Thread({ reportLoop() }, "Bili-nva-report").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        control = null
        manifest = null
        current = null
    }

    /** The phone stopped casting from elsewhere, or the TV did: tell it, and stop the picture. */
    fun stopPlayback() {
        player.stop()
        manifest = null
        current = null
        reportedPlaying = null
        successSent = false
        broadcast("OnPlayState", """{"playState":7}""")
    }

    /**
     * The video played to its end. The phone ends the episode on this and, with auto-next on,
     * casts the next one by itself.
     */
    fun finished() {
        reportedPlaying = null
        broadcast("OnPlayState", """{"playState":6}""")
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
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val protocol = parts[2]
            val headers = readHeaders(input)
            val from = socket.inetAddress.hostAddress.orEmpty()

            if (protocol.startsWith("NVA/", ignoreCase = true)) {
                handshake(socket, input, output, method, path, headers, from)
                return
            }
            val body = readBody(input, headers)
            respond(socket, output, method, path, query, headers, body, from)
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
        // Reports go to the newest connection: a phone that reconnects takes over the screen.
        control = output
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
            if (control === output) control = null
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

    /** Answers a command (the phone waits for the reply before its next one) and acts on it. */
    private fun handle(command: Nva.Command, output: OutputStream, counter: AtomicInteger) {
        Log.i(TAG, "Bilibili → ${command.method} ${describe(command)}")
        when (command.method) {
            "Play" -> {
                send(output, Nva.ack(command.counter))
                onPlay(command.payload)
            }
            "PlayUrl" -> send(output, Nva.ack(command.counter))
            "Pause" -> {
                send(output, Nva.ack(command.counter))
                player.setPaused(true)
                reportState(playing = false)
            }
            "Resume" -> {
                send(output, Nva.ack(command.counter))
                player.setPaused(false)
                reportState(playing = true)
            }
            "Seek" -> {
                send(output, Nva.ack(command.counter))
                val seconds = number(command.payload, "seekTs")?.toInt() ?: 0
                player.seekTo(seconds.coerceAtLeast(0))
            }
            "Stop" -> {
                send(output, Nva.ack(command.counter))
                player.stop()
                manifest = null
                reportedPlaying = null
                reportState(playing = false, stopped = true)
            }
            "SetVolume" -> {
                send(output, Nva.ack(command.counter))
                number(command.payload, "volume")?.let { value ->
                    volume = value.toInt().coerceIn(0, 100)
                    player.setVolume(volume)
                }
            }
            "GetVolume" -> send(output, Nva.reply(command.counter, """{"volume":$volume}"""))
            "SwitchSpeed" -> {
                send(output, Nva.ack(command.counter))
                val rate = (number(command.payload, "speed")?.toFloat() ?: 1f).coerceIn(0.25f, 3f)
                Log.i(TAG, "Bilibili speed ${rate}x")
                speed = rate
                player.setSpeed(rate)
                broadcast("SpeedChanged", speedJson(rate))
            }
            "SwitchQn" -> {
                send(output, Nva.ack(command.counter))
                val qn = number(command.payload, "qn")?.toInt() ?: 0
                if (qn > 0) switchQuality(qn)
            }
            // Danmaku is not drawn on the TV yet: acknowledged, and reported as closed so the
            // phone's switch matches what is on screen.
            "SwitchDanmaku", "SendDanmaku", "AppendDanmaku", "SetDanmakuSwitch" ->
                send(output, Nva.ack(command.counter))
            else -> send(output, Nva.ack(command.counter))
        }
    }

    private fun onPlay(payload: String?) {
        val request = Nva.PlayRequest.parse(payload) ?: return
        val previous = current
        current = request
        if (!request.open) {
            Log.i(TAG, "Bilibili pause (Play with isOpen false)")
            player.setPaused(true)
            reportState(playing = false)
            return
        }
        // The same video again with nothing stopped: the phone's resume, not a new cast.
        val same = previous != null && previous.aid == request.aid && previous.cid == request.cid
        if (same && player.progress() != null) {
            Log.i(TAG, "Bilibili resume")
            player.setPaused(false)
            reportState(playing = true)
            return
        }
        if (request.isLive) {
            Log.i(TAG, "Bilibili live room ${request.roomId} is not supported yet")
            broadcast("OnPlayState", """{"playState":7}""")
            return
        }
        Log.i(
            TAG,
            "Bilibili cast: aid ${request.aid} cid ${request.cid} epid ${request.epid} " +
                "from ${request.startSec} s, asking qn ${request.qn}"
        )
        open(request)
    }

    /** Fetches the streams off the protocol thread, then hands the player the manifest. */
    private fun open(request: Nva.PlayRequest) {
        Thread({
            try {
                broadcast("OnPlayState", """{"playState":3}""")
                val stream = BiliApi.request(
                    aid = request.aid, cid = request.cid, epid = request.epid,
                    accessKey = request.accessKey, qn = BiliApi.WANTED_QN
                )
                currentQn = stream.video.id
                currentHeight = stream.video.height
                qualities = stream.qualities
                speed = request.speed
                successSent = false
                val source = localSource(stream)
                player.play(
                    manifest = source,
                    startSec = request.startSec,
                    durationSec = (stream.durationMs / 1000).toInt(),
                    title = "Bilibili ${stream.video.height}p",
                    qn = stream.video.id,
                    speed = request.speed
                )
                broadcast("OnEpisodeSwitch", episodeJson(request))
                broadcast("OnQnSwitch", qnJson())
                broadcast("SpeedChanged", speedJson(request.speed))
                broadcast("OnDanmakuSwitch", """{"open":false}""")
            } catch (failure: BiliApi.Failure) {
                Log.e(TAG, "Bilibili could not play aid ${request.aid}: ${failure.message}")
                broadcast("OnPlayState", """{"playState":7}""")
            } catch (error: Exception) {
                Log.e(TAG, "Bilibili playback failed", error)
                broadcast("OnPlayState", """{"playState":7}""")
            }
        }, "Bili-nva-play").start()
    }

    /** A quality picked in the phone's menu: fetch that qn and carry on where it was. */
    private fun switchQuality(qn: Int) {
        val request = current ?: return
        val position = player.progress()?.positionSec ?: 0
        Log.i(TAG, "Bilibili quality switch to qn $qn at ${position} s")
        Thread({
            try {
                val stream = BiliApi.request(request.aid, request.cid, request.epid, request.accessKey, qn)
                currentQn = stream.video.id
                currentHeight = stream.video.height
                qualities = stream.qualities
                successSent = false
                val source = localSource(stream)
                player.play(
                    manifest = source,
                    startSec = position,
                    durationSec = (stream.durationMs / 1000).toInt(),
                    title = "Bilibili ${stream.video.height}p",
                    qn = stream.video.id,
                    speed = speed
                )
                broadcast("OnQnSwitch", qnJson())
            } catch (error: Exception) {
                Log.e(TAG, "Bilibili could not switch quality to $qn", error)
            }
        }, "Bili-nva-quality").start()
    }

    /**
     * What the player should open: this server's MPD when there is one (served from 127.0.0.1,
     * so no network interface has to be up), else the single muxed file the API gave.
     */
    private fun localSource(stream: BiliApi.Stream): String {
        val single = stream.single
        if (single != null) {
            manifest = null
            return single
        }
        val version = manifestVersion + 1
        manifestVersion = version
        manifest = Manifest(version, BiliApi.buildMpd(stream))
        return "http://127.0.0.1:$port$MANIFEST_PATH?v=$version"
    }

    /**
     * The phone's progress bar, play/pause state and quality label come from these reports:
     * without them it shows 00:00 / 00:00 and its own pause button does nothing.
     */
    private fun reportLoop() {
        while (running) {
            try {
                Thread.sleep(REPORT_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                break
            }
            if (control == null) continue
            val progress = player.progress() ?: continue
            if (progress.durationSec <= 0) continue
            broadcast(
                "OnProgress",
                """{"duration":${progress.durationSec},"position":${progress.positionSec}}"""
            )
            if (reportedPlaying != progress.playing) {
                reportState(progress.playing)
                if (progress.playing && !successSent) {
                    successSent = true
                    broadcast("PLAY_SUCCESS", null)
                }
            }
        }
    }

    private fun reportState(playing: Boolean, stopped: Boolean = false) {
        reportedPlaying = if (stopped) null else playing
        val state = when {
            stopped -> 7
            playing -> 4
            else -> 5
        }
        broadcast("OnPlayState", """{"playState":$state}""")
    }

    private fun episodeJson(request: Nva.PlayRequest): String = buildString {
        append("""{"playItem":{"aid":${request.aid},"cid":${request.cid},"contentType":${request.contentType}""")
        append(""","epId":${request.epid},"seasonId":${request.seasonId}}""")
        append(""","qnDesc":${qnJson()},"title":"Bilibili ${currentHeight}p"}""")
    }

    private fun qnJson(): String = buildString {
        append("""{"curQn":$currentQn,"userDesireQn":$currentQn,"supportQnList":[""")
        append(
            qualities.joinToString(",") { quality ->
                """{"quality":${quality.qn},"description":"${quality.description}","displayDesc":"${quality.description}",""" +
                    """"needVip":${quality.needVip},"needLogin":${quality.needLogin},"superscript":""}"""
            }
        )
        append("]}")
    }

    private fun speedJson(speed: Float): String =
        """{"currSpeed":$speed,"supportSpeedList":[0.5,0.75,1.0,1.25,1.5,2.0]}"""

    private fun broadcast(method: String, json: String?) {
        val out = control ?: return
        send(out, Nva.broadcast(sendCounter.incrementAndGet(), method, json))
    }

    private fun send(output: OutputStream, bytes: ByteArray) {
        runCatching {
            synchronized(output) {
                output.write(bytes)
                output.flush()
            }
        }.onFailure { Log.w(TAG, "Could not answer Bilibili", it) }
    }

    // ---- HTTP (descriptions, SOAP and the local manifest) -------------------------------------

    private fun respond(
        socket: Socket,
        output: OutputStream,
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: String,
        from: String
    ) {
        val service = UpnpDescriptions.SERVICES.firstOrNull { path.startsWith("/${it.name}/") }
        when {
            path == MANIFEST_PATH -> {
                val wanted = query.split('&')
                    .firstOrNull { it.startsWith("v=") }?.substringAfter("v=")?.toIntOrNull()
                val mpd = manifest
                if (mpd == null) {
                    write(socket, output, 404, "Not Found", "")
                } else {
                    if (wanted != null && wanted != mpd.version) {
                        Log.i(TAG, "Bilibili manifest v$wanted asked for; v${mpd.version} is current")
                    }
                    Log.i(TAG, "Bilibili manifest served to the player (${mpd.body.length} bytes)")
                    write(
                        socket, output, 200, "OK", mpd.body,
                        headers = listOf("Cache-Control" to "no-store"),
                        contentType = "application/dash+xml; charset=\"utf-8\""
                    )
                }
            }
            path == Nva.DESCRIPTION_PATH || path == UpnpDescriptions.DESCRIPTION_PATH -> {
                Log.i(TAG, "Bilibili asked for the device description from $from (${headers["user-agent"].orEmpty()})")
                write(socket, output, 200, "OK", Nva.device(uuid))
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
        headers: List<Pair<String, String>> = emptyList(),
        contentType: String = "text/xml; charset=\"utf-8\""
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Server: ${Nva.SERVER}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            if (bytes.isNotEmpty()) append("Content-Type: $contentType\r\n")
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
            // Values a stock box reports; the phone only needs to get past this step.
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

    /** One number out of a command payload, without building a whole parser. */
    private fun number(payload: String?, name: String): Double? {
        val text = payload ?: return null
        val match = Regex("\"$name\"\\s*:\\s*(-?[0-9.]+)").find(text) ?: return null
        return match.groupValues[1].toDoubleOrNull()
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
        const val REPORT_INTERVAL_MS = 1_000L
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_FRAME_BYTES = 512 * 1024
        const val MAX_LOG_BYTES = 1_200
        const val MANIFEST_PATH = "/bili/manifest.mpd"
    }
}
