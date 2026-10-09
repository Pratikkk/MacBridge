package com.example.network

import android.net.Uri
import com.example.model.PairedDevice

data class PairingCode(val device: PairedDevice, val secret: String) {
    companion object {
        fun parse(raw: String): PairingCode {
            require(raw.length <= 4096) { "Pairing code is too long" }
            val uri = Uri.parse(raw.trim())
            require(uri.scheme == "macbridge" && uri.host == "pair") { "Invalid MacBridge pairing code" }
            require(uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty() && uri.fragment == null) {
                "Invalid pairing code structure"
            }
            require(uri.queryParameterNames.all { uri.getQueryParameters(it).size == 1 }) {
                "Pairing code contains duplicate fields"
            }
            require(uri.getQueryParameter("v") == "2") { "Use a new pairing code from the Mac companion" }
            fun field(name: String) = uri.getQueryParameter(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing $name in pairing code")
            val fingerprint = field("fingerprint").uppercase()
            require(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fingerprint)) { "Invalid public-key pin" }
            val secret = field("secret")
            require(Regex("[0-9a-f]{64}").matches(secret)) { "Invalid one-time pairing secret" }
            val ip = field("ip")
            require(ip.length <= 253 && !ip.any { it.isWhitespace() || it in "/?#@" }) { "Invalid Mac address" }
            val port = field("port").toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid Mac port" }
            val id = field("id")
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) { "Invalid Mac identity" }
            val name = field("name")
            require(name.length <= 128 && !name.any { it.isISOControl() }) { "Invalid Mac name" }
            return PairingCode(PairedDevice(id, name, fingerprint,
                pinnedPublicKey = "", lastKnownIp = ip, port = port), secret)
        }
    }
}
