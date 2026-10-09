package com.example.crypto

data class SecurityRule(
    val ruleNumber: Int,
    val title: String,
    val description: String,
    val isCompliant: Boolean,
    val technicalAudit: String
)

/** Implementation status, not an independent or runtime security audit. */
object SecurityBaselineAuditor {
    fun getAuditRules(): List<SecurityRule> = listOf(
        SecurityRule(1, "Platform Cryptography", "Pinned TLS 1.2+ and signed phone identity challenges.", true,
            "Uses SSLSocket, SHA-256 public-key pins and platform ECDSA. Phone proof runs inside TLS; this is not mutual TLS."),
        SecurityRule(2, "Discovery is Untrusted", "Discovery never authorizes pairing.", true,
            "A separate out-of-band Mac pairing code is required. Discovered fingerprint hints are not trusted."),
        SecurityRule(3, "Pair Out of Band", "Pair before granting feature access.", true,
            "Mac code has a 256-bit secret, 5-minute expiry and single successful use. Android stores the pin only after authentication."),
        SecurityRule(4, "Keys Stay Put", "Production Android identity fails closed without Keystore.", false,
            "Android uses Keystore with no software fallback by default. Development Mac keys use owner-only files; native Keychain storage remains to implement."),
        SecurityRule(5, "Treat Input as Hostile", "Bounded frames and filename sanitation are implemented.", false,
            "UTF-8 frames are limited to 1 MiB before parsing. File-transfer schema, quotas and ordering still need hardening."),
        SecurityRule(6, "Least Privilege", "New peers start with all features disabled.", false,
            "Incoming clipboard and files check stored peer permissions. Remaining notification and outgoing file paths still need a full permission audit."),
        SecurityRule(7, "Local Only", "Direct connections without a relay.", false,
            "No relay in the bridge protocol. Inherited Firebase dependencies and initialization require review before claiming zero cloud activity."),
        SecurityRule(8, "Network Path Review", "Android Kotlin and a development Python Mac peer.", false,
            "Native Swift Mac companion and independent protocol review remain planned. No blanket compliance assertion.")
    )
}
