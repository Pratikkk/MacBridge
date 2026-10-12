package com.example.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

object PairingFailure {
    fun message(error: Exception, endpoint: String?): String {
        certificateValidityFailure(error)?.let { return it }
        val mac = endpoint ?: "your Mac"
        return when (error) {
            is SocketTimeoutException -> "No response from $mac. Keep the Mac companion running, connect both devices to the same local network, and paste a fresh Mac pairing code. If it still times out, check the Mac firewall."
            is ConnectException -> "Could not connect to $mac. Start the Mac companion and paste its current pairing code. Both devices must be able to reach each other on the local network."
            is SSLHandshakeException -> "Could not verify the Mac TLS identity. Generate a fresh pairing code on the Mac and try again."
            else -> "Pairing failed: ${error.message ?: "connection rejected"}"
        }
    }
}

/** Walk bounded causes without exposing certificate/provider exception contents. */
internal fun certificateValidityFailure(error: Throwable): String? {
    var cause: Throwable? = error
    repeat(16) {
        when (cause) {
            is java.security.cert.CertificateExpiredException -> return "The Mac certificate has expired. On your Mac, open MacBridge Settings → General → Certificate → Renew, then connect again. Existing pairing stays saved."
            is java.security.cert.CertificateNotYetValidException -> return "The Mac certificate is not yet valid. Check the date and time on both devices. If needed, renew it in MacBridge Settings → General on the Mac, then connect again. Keep your existing pairing."
        }
        cause = cause?.cause
    }
    return null
}
