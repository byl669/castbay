package com.weenas.castbay.service.desktop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The receiver's long-term identity for CastBay Link: an EC P-256 key pair in the Android
 * Keystore with a self-signed certificate (see desktop/docs/protocol.md, section 3). Nobody
 * checks the certificate against a CA; a sender pins its fingerprint when it pairs.
 */
object LinkIdentity {
    private const val ALIAS = "castbay-link"
    private const val PROVIDER = "AndroidKeyStore"

    /** Creates the key pair on first use. */
    @Synchronized
    fun ensure(): KeyStore {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS libraries ask for different digests, and for none at all (pre-hashed input).
                .setDigests(
                    KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                )
                .setCertificateSubject(X500Principal("CN=CastBay Receiver"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 24 * 3600 * 1000L))
                .setCertificateNotAfter(Date(now + 20 * 365 * 24 * 3600 * 1000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
        return store
    }

    fun certificate(): X509Certificate = ensure().getCertificate(ALIAS) as X509Certificate

    fun privateKey(): PrivateKey = ensure().getKey(ALIAS, null) as PrivateKey

    fun chain(): Array<Certificate> = ensure().getCertificateChain(ALIAS)

    /** SHA-256 of the certificate's public key (SubjectPublicKeyInfo), lower-case hex. */
    fun fingerprint(certificate: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }
}
