package com.example.model

/**
 * Android-Mac Bridge Protocol Messages.
 * Designed to align directly with Wire/Protobuf specifications in the shared module.
 * Security Baseline Rule 5: Strict schema, hostile input handling, max message size limit.
 */
sealed class ProtocolMessage(val type: String) {

    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val fingerprint: String,
        val protocolVersion: Int = 1,
        val capabilities: List<String> = listOf("clipboard", "file_transfer", "notification_mirror")
    ) : ProtocolMessage("HELLO")

    data class PairRequest(
        val deviceId: String,
        val deviceName: String,
        val fingerprint: String,
        val oneTimeSecret: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ProtocolMessage("PAIR_REQUEST")

    data class PairResponse(
        val accepted: Boolean,
        val deviceId: String,
        val fingerprint: String,
        val reason: String? = null
    ) : ProtocolMessage("PAIR_RESPONSE")

    data class Heartbeat(
        val seq: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val isAck: Boolean = false
    ) : ProtocolMessage("HEARTBEAT")

    data class ClipboardSync(
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val sourceDevice: String,
        val mimeType: String = "text/plain"
    ) : ProtocolMessage("CLIPBOARD")

    data class FileInit(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val sha256Checksum: String,
        val mimeType: String = "application/octet-stream",
        val chunkSize: Int = 64 * 1024
    ) : ProtocolMessage("FILE_INIT")

    data class FileChunk(
        val transferId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val offset: Long,
        val dataBase64: String,
        val chunkLength: Int
    ) : ProtocolMessage("FILE_CHUNK")

    data class FileAck(
        val transferId: String,
        val receivedBytes: Long,
        val status: String // "IN_PROGRESS", "COMPLETED", "VERIFICATION_FAILED", "RETRY_CHUNK"
    ) : ProtocolMessage("FILE_ACK")

    data class NotificationMirror(
        val notificationId: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val hasReplyAction: Boolean = false,
        val isDismissed: Boolean = false
    ) : ProtocolMessage("NOTIFICATION")

    data class NotificationAction(
        val notificationId: String,
        val actionType: String, // "DISMISS" or "REPLY"
        val replyText: String? = null
    ) : ProtocolMessage("NOTIFICATION_ACTION")
}

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprintHint: String?,
    val isPaired: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
