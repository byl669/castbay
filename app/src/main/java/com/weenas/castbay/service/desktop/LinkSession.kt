package com.weenas.castbay.service.desktop

import com.weenas.castbay.service.AccessPolicy
import com.weenas.castbay.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/** Wrong codes in a row, across connections: after three, pairing is locked for a minute. */
class PairingGuard(private val clock: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun locked(): Boolean = clock() < lockedUntil

    @Synchronized
    fun failed() {
        if (++failures >= MAX_FAILURES) {
            lockedUntil = clock() + LOCK_MS
            failures = 0
        }
    }

    @Synchronized
    fun succeeded() {
        failures = 0
    }

    companion object {
        const val MAX_FAILURES = 3
        const val LOCK_MS = 60_000L
    }
}

/** One desktop sender's connection, from the TLS handshake to the end (desktop/docs/protocol.md). */
class LinkSession(
    private val socket: SSLSocket,
    private val host: LinkHost,
    private val pairings: LinkPairings,
    private val guard: PairingGuard,
    private val receiverId: String,
    private val onAdmitted: (LinkSession) -> Unit,
    private val onFinished: (LinkSession) -> Unit
) : Runnable {
    @Volatile var deviceId: String = ""
        private set
    @Volatile private var started = false
    @Volatile private var closing = false
    private var lastKeyRequest = 0L
    /**
     * One stream object, so the writes of different threads exclude each other. Taken after the
     * handshake only: asking an SSL socket for its streams starts the handshake, and anything
     * that goes wrong there (a peer with no certificate, plain TCP) must happen on this
     * session's own thread, not the accepting one.
     */
    @Volatile private var out: java.io.OutputStream? = null
    private val output: java.io.OutputStream get() = out ?: throw IOException("not connected yet")

    /**
     * Ends the session from outside (the TV's Stop, a replaced session, the receiver stopping).
     * Callers may be on the main thread, so the goodbye is written on a thread of its own.
     */
    fun close(reason: String) {
        if (closing) return
        closing = true
        Thread({
            try {
                if (out != null) LinkProtocol.writeControl(output, JSONObject().put("t", "bye").put("reason", reason))
            } catch (error: Exception) {
                Log.d(TAG, "Could not say goodbye", error)
            }
            runCatching { socket.close() }
        }, "Link-close").apply { isDaemon = true }.start()
    }

    override fun run() {
        try {
            socket.soTimeout = HANDSHAKE_MS
            socket.tcpNoDelay = true
            socket.startHandshake()
            out = socket.outputStream
            val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return
            val senderKey = MessageDigest.getInstance("SHA-256").digest(peer.publicKey.encoded)
            val fingerprint = senderKey.joinToString("") { "%02x".format(it) }
            deviceId = AccessPolicy.desktopDeviceId(fingerprint)
            val input = DataInputStream(BufferedInputStream(socket.inputStream, 64 * 1024))

            val hello = expect(input, "hello") ?: return
            val name = hello.optString("name").trim().ifEmpty { "Computer" }.take(60)
            if (hello.optInt("v", 0) < LinkProtocol.VERSION) return refuse("unsupported")
            val receiverKey = MessageDigest.getInstance("SHA-256").digest(LinkIdentity.certificate().publicKey.encoded)
            send(JSONObject().put("t", "hello").put("v", LinkProtocol.VERSION).put("name", host.receiverName)
                .put("id", receiverId).put("app", "CastBay").put("os", "android"))

            val paired = pairings.contains(fingerprint)
            when (host.admission(deviceId, name, paired)) {
                LinkAdmission.BLOCKED -> return refuse("blocked")
                LinkAdmission.BUSY -> return refuse("busy")
                LinkAdmission.NEEDS_APPROVAL -> {
                    host.requestApproval(deviceId, name)
                    return refuse("approval_needed")
                }
                LinkAdmission.NEEDS_CODE -> if (!pair(input, receiverKey, senderKey, fingerprint, name)) return
                LinkAdmission.ADMIT -> if (!paired && !AccessPolicy.desktopNeedsApproval(host.settings())) {
                    // Open verification: the first connection is trusted, and remembered.
                    pairings.add(LinkPairing(fingerprint, name))
                }
            }
            val password = host.settings().requiredPassword()
            if (password.isNotEmpty() && !constantTimeEquals(password, hello.optString("password"))) {
                return refuse("wrong_password")
            }
            host.remember(deviceId, name)
            send(JSONObject().put("t", "ready"))

            socket.soTimeout = KEEPALIVE_MS
            var offer = expect(input, "offer") ?: return
            while (true) {
                val size = validate(offer) ?: return refuse("unsupported")
                onAdmitted(this)
                host.sessionStarted(this, deviceId, name, size.first, size.second, offer.optInt("fps", 30))
                started = true
                send(JSONObject().put("t", "accept"))
                startStats()
                offer = relay(input) ?: break
            }
        } catch (error: IOException) {
            // Includes failed handshakes: anything on the network that is not a CastBay sender.
            if (!closing) Log.d(TAG, "Link session ended: ${error.message}")
        } catch (error: LinkProtocolException) {
            Log.w(TAG, "Link protocol error: ${error.message}")
        } catch (error: Exception) {
            Log.e(TAG, "Link session failed", error)
        } catch (error: StackOverflowError) {
            Log.e(TAG, "Link session failed", error)
        } finally {
            closing = true
            host.hideCode()
            runCatching { socket.close() }
            if (started) host.sessionEnded(this)
            onFinished(this)
        }
    }

    /** Shows the code on the TV and waits for the sender to type it. */
    private fun pair(input: DataInputStream, receiverKey: ByteArray, senderKey: ByteArray, fingerprint: String, name: String): Boolean {
        if (guard.locked()) {
            refuse("locked")
            return false
        }
        val code = LinkProtocol.verificationCode(receiverKey, senderKey)
        host.showCode(code)
        send(JSONObject().put("t", "pair_required"))
        socket.soTimeout = CODE_MS
        val reply = expect(input, "pair") ?: return false
        host.hideCode()
        if (guard.locked() || !constantTimeEquals(code, reply.optString("sas"))) {
            guard.failed()
            send(JSONObject().put("t", "pair_result").put("ok", false).put("reason", if (guard.locked()) "locked" else "wrong_code"))
            return false
        }
        guard.succeeded()
        pairings.add(LinkPairing(fingerprint, name))
        send(JSONObject().put("t", "pair_result").put("ok", true))
        return true
    }

    /** Reads until the sender ends or sends a new offer (returned). */
    private fun relay(input: DataInputStream): JSONObject? {
        while (true) {
            when (val message = LinkProtocol.read(input) ?: return null) {
                is LinkMessage.Video -> if (!host.video(message.data, message.presentationTimeUs)) askForKeyFrame()
                is LinkMessage.Control -> when (message.type) {
                    "offer" -> return message.json
                    "bye" -> return null
                    else -> {} // ping, pause, resume: reading it was the point
                }
                is LinkMessage.Unknown -> {}
            }
        }
    }

    private fun askForKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 1000) return
        lastKeyRequest = now
        send(JSONObject().put("t", "keyframe"))
    }

    private fun startStats() {
        if (statsStarted) return
        statsStarted = true
        Thread({
            while (!closing) {
                try {
                    Thread.sleep(1000)
                    // Also while nothing decodes (no screen yet): the sender hears that we are alive.
                    val stats = host.videoStats()
                    send(JSONObject().put("t", "stats").put("decode_ms", stats?.decodeLatencyMs ?: -1)
                        .put("dropped", stats?.droppedFrames ?: 0).put("fps", stats?.fps ?: 0.0))
                } catch (e: Exception) {
                    return@Thread
                }
            }
        }, "Link-stats").apply { isDaemon = true }.start()
    }

    private var statsStarted = false

    /** Reads the next message, which must be a control message of [type]; null if it ends or is something else. */
    private fun expect(input: DataInputStream, type: String): JSONObject? {
        val message = LinkProtocol.read(input) as? LinkMessage.Control ?: return null
        if (message.type != type) throw LinkProtocolException("expected $type, got ${message.type}")
        return message.json
    }

    private fun send(json: JSONObject) {
        if (!closing) LinkProtocol.writeControl(output, json)
    }

    private fun refuse(reason: String) {
        runCatching { send(JSONObject().put("t", "reject").put("reason", reason)) }
        Log.i(TAG, "Desktop sender refused: $reason")
    }

    /** The offer's size, or null if this receiver cannot take it. */
    private fun validate(offer: JSONObject): Pair<Int, Int>? {
        if (offer.optString("codec") != "h264") return null
        val width = offer.optInt("width", 0)
        val height = offer.optInt("height", 0)
        if (width !in 16..MAX_SIZE || height !in 16..MAX_SIZE || width % 2 != 0 || height % 2 != 0) return null
        return width to height
    }

    private fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val TAG = "CastBayLink"
        const val HANDSHAKE_MS = 10_000
        const val CODE_MS = 120_000
        const val KEEPALIVE_MS = 10_000
        const val MAX_SIZE = 8192
    }
}
