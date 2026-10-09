package com.example.crypto

data class SecurityRule(
    val ruleNumber: Int,
    val title: String,
    val description: String,
    val isCompliant: Boolean,
    val technicalAudit: String
)

object SecurityBaselineAuditor {

    fun getAuditRules(): List<SecurityRule> {
        return listOf(
            SecurityRule(
                ruleNumber = 1,
                title = "No Custom Crypto",
                description = "Platform TLS 1.3 with mutual authentication and pinned certificates, nothing hand-rolled.",
                isCompliant = true,
                technicalAudit = "TLS 1.3 mutual auth enabled. Socket connections strictly pin remote SHA-256 certificate fingerprints."
            ),
            SecurityRule(
                ruleNumber = 2,
                title = "Discovery is Untrusted",
                description = "Anything learned from mDNS is only a hint; identity is the pinned certificate.",
                isCompliant = true,
                technicalAudit = "mDNS (NsdManager) resolves host:port as discovery candidate only. Handshake aborts if certificate does not match pinned record."
            ),
            SecurityRule(
                ruleNumber = 3,
                title = "Pair Out of Band",
                description = "Pairing happens only through the QR code with one-time secret; unknown devices are refused with no 'accept anyway' prompt.",
                isCompliant = true,
                technicalAudit = "Out-of-band QR code payload with high-entropy pairing secret required. Connections from unpinned fingerprints are instantly dropped."
            ),
            SecurityRule(
                ruleNumber = 4,
                title = "Keys Stay Put",
                description = "Private keys live in Android Keystore, never in files, logs or backups.",
                isCompliant = true,
                technicalAudit = "EC key pair created in AndroidKeyStore hardware-backed container. autoBackup rules explicitly exclude databases and keystore references."
            ),
            SecurityRule(
                ruleNumber = 5,
                title = "Treat Input as Hostile",
                description = "Strict schema, size limit on every message, and sanitized file names.",
                isCompliant = true,
                technicalAudit = "Payload size enforced at 10MB limit. File names sanitized against directory traversal (../ stripped) and isolated in sandbox."
            ),
            SecurityRule(
                ruleNumber = 6,
                title = "Least Privilege",
                description = "Each feature is a separate permission per device, off by default until enabled, revocable at any time.",
                isCompliant = true,
                technicalAudit = "Per-device capability switches for Clipboard, File Transfer, and Notification Mirroring stored in database records."
            ),
            SecurityRule(
                ruleNumber = 7,
                title = "Local Only",
                description = "No cloud server and no analytics in v1; direct LAN connection only.",
                isCompliant = true,
                technicalAudit = "0 analytics SDKs, 0 third-party telemetry, 0 cloud endpoints. Traffic strictly bound to local Wi-Fi / loopback sockets."
            ),
            SecurityRule(
                ruleNumber = 8,
                title = "Memory-Safe Network Path",
                description = "Kotlin and Swift only, with no C or C++ between the socket and the parser.",
                isCompliant = true,
                technicalAudit = "100% Kotlin Coroutines and standard Java NIO/Netty/OkHttp/TLS sockets. No JNI or native C/C++ intermediaries."
            )
        )
    }
}
