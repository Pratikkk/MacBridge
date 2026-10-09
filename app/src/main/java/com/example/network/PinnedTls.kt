package com.example.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object PinnedTls {
    fun fingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString(":") { "%02X".format(it) }

    fun context(expectedFingerprint: String, pinnedPublicKey: String? = null): SSLContext {
        require(Regex("([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}").matches(expectedFingerprint)) {
            "Invalid SHA-256 public-key pin"
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Inbound connections are not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
                leaf.checkValidity()
                val key = leaf.publicKey.encoded
                if (!fingerprint(key).equals(expectedFingerprint, ignoreCase = true)) {
                    throw CertificateException("Mac public-key pin mismatch")
                }
                if (pinnedPublicKey != null && !MessageDigest.isEqual(
                        key, android.util.Base64.decode(pinnedPublicKey, android.util.Base64.NO_WRAP))) {
                    throw CertificateException("Mac public key changed")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }
}
