package com.weenas.castbay.service.desktop

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS for CastBay Link: mutual authentication with self-signed certificates on both sides.
 * The handshake accepts any client certificate; who the sender is gets decided afterwards by
 * its pinned fingerprint ([LinkIdentity.fingerprint]).
 */
object LinkTls {
    /** Cipher suites worth offering: forward secrecy and an AEAD cipher, with EC keys. */
    private val PREFERRED_SUITES = listOf(
        "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
    )

    /**
     * Accepts any certificate; pinning happens above TLS. A plain [X509TrustManager]: the
     * extended one needs API 24, and the receiver supports Android 6.
     */
    private object AcceptAnyClient : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        // No issuers: the client then offers whatever certificate it has.
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun serverSocket(port: Int): SSLServerSocket {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(LinkIdentity.ensure(), null)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(keys.keyManagers, arrayOf(AcceptAnyClient), null)
        }
        val socket = context.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        socket.reuseAddress = true
        socket.needClientAuth = true
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        socket.enabledCipherSuites = PREFERRED_SUITES.filter { it in socket.supportedCipherSuites }.toTypedArray()
        return socket
    }
}
