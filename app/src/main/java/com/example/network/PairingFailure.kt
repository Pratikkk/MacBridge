package com.example.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

object PairingFailure {
    fun message(error: Exception, endpoint: String?): String {
        val mac = endpoint ?: "your Mac"
        return when (error) {
            is SocketTimeoutException -> "No response from $mac. Keep the Mac companion running, connect both devices to the same local network, and paste a fresh Mac pairing code. If it still times out, check the Mac firewall."
            is ConnectException -> "Could not connect to $mac. Start the Mac companion and paste its current pairing code. Both devices must be able to reach each other on the local network."
            is SSLHandshakeException -> "Could not verify the Mac TLS identity. Generate a fresh pairing code on the Mac and try again."
            else -> "Pairing failed: ${error.message ?: "connection rejected"}"
        }
    }
}
