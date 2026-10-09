package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDevice(
    @PrimaryKey val id: String,
    val name: String,
    val fingerprint: String, // Pinned certificate fingerprint (SHA-256)
    val pinnedPublicKey: String,
    val lastKnownIp: String,
    val port: Int = 8990,
    val pairedTimestamp: Long = System.currentTimeMillis(),
    val allowClipboard: Boolean = false,
    val allowFileTransfer: Boolean = false,
    val allowNotifications: Boolean = false,
    val isBlocked: Boolean = false
)

@Entity(tableName = "clipboard_history")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceDeviceName: String,
    val isOutgoing: Boolean = false
)

enum class TransferDirection {
    INCOMING, OUTGOING
}

enum class TransferStatus {
    PENDING, TRANSFERRING, COMPLETED, FAILED, PAUSED
}

@Entity(tableName = "file_transfers")
data class FileTransferItem(
    @PrimaryKey val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val transferredBytes: Long = 0,
    val direction: TransferDirection,
    val status: TransferStatus,
    val sha256Checksum: String,
    val calculatedChecksum: String? = null,
    val filePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

@Entity(tableName = "mirrored_notifications")
data class MirroredNotification(
    @PrimaryKey val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasReply: Boolean = false,
    val isDismissed: Boolean = false
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class SystemLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Discovering : ConnectionState()
    data class Connecting(val target: String) : ConnectionState()
    data class Handshaking(val target: String, val step: String) : ConnectionState()
    data class Connected(
        val device: PairedDevice,
        val host: String,
        val port: Int,
        val roundTripTimeMs: Long = 12,
        val connectedSince: Long = System.currentTimeMillis(),
        val isSimulated: Boolean = false
    ) : ConnectionState()
    data class Reconnecting(val target: String, val attempt: Int, val delayMs: Long) : ConnectionState()
}
