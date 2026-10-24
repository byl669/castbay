package com.weenas.castbay.service.desktop

import android.content.Context
import com.weenas.castbay.util.Log
import java.net.SocketException
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Listens for CastBay Link senders (desktop/docs/protocol.md) and runs one session at a time. */
class LinkServer(context: Context, private val host: LinkHost) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("link_identity", Context.MODE_PRIVATE)
    private val pairings = LinkPairings(appContext)
    private val guard = PairingGuard()
    private var server: SSLServerSocket? = null
    @Volatile private var current: LinkSession? = null
    private val connections = java.util.concurrent.atomic.AtomicInteger()

    /** Stable across restarts, so senders' saved receivers stay valid. */
    private val receiverId: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(LinkIdentity.certificate().publicKey.encoded)
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The port the server listens on, or 0 when it is not running. */
    @Volatile var port = 0
        private set

    /** The first 16 hex characters of the identity key's fingerprint: what discovery announces. */
    val identityId: String get() = receiverId

    @Synchronized
    fun start(): Int {
        stop()
        val socket = try {
            LinkTls.serverSocket(preferences.getInt(KEY_PORT, 0))
        } catch (error: Exception) {
            // The saved port is taken: take any.
            LinkTls.serverSocket(0)
        }
        preferences.edit().putInt(KEY_PORT, socket.localPort).apply()
        server = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "Link-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "CastBay Link on port $port (receiver $receiverId)")
        return port
    }

    @Synchronized
    fun stop() {
        current?.close("receiver_stopped")
        current = null
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** Ends the desktop session from the TV. */
    fun endSession() {
        current?.close("stopped_by_receiver")
    }

    fun forgetPairings() {
        pairings.clear()
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (error: SocketException) {
                return
            } catch (error: Exception) {
                Log.w(TAG, "Accept failed", error)
                continue
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                // Enough strangers knocking: a sender that really is one will try again.
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                val session = LinkSession(client, host, pairings, guard, receiverId, onAdmitted = ::admitted, onFinished = ::finished)
                Thread(session, "Link-session").apply { isDaemon = true }.start()
            } catch (error: Throwable) {
                // Nothing a stranger on the network does may end the accepting thread.
                Log.w(TAG, "Could not start a session", error)
                connections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    /** A new session is about to take the screen: a running one makes way. */
    private fun admitted(session: LinkSession) {
        val previous = current
        current = session
        if (previous != null && previous !== session) previous.close("replaced")
    }

    private fun finished(session: LinkSession) {
        connections.decrementAndGet()
        if (current === session) current = null
    }

    private companion object {
        const val TAG = "CastBayLink"
        const val KEY_PORT = "port"

        /** Connections open at once, handshaking or not; a session thread each. */
        const val MAX_CONNECTIONS = 8
    }
}
