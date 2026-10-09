package com.example.network

import com.example.manager.DiagnosticLogger
import com.example.model.PairedDevice
import com.example.model.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * Phase 7 & Testing: Mac Test Bench Simulator.
 * Simulates a native macOS Sequoia menu-bar peer for live testing of all 8 roadmap gates
 * directly on device without requiring external hardware setup.
 */
class MacSimulatorBench(
    private val scope: CoroutineScope,
    private val onSimulatedMessage: (ProtocolMessage) -> Unit
) {
    private val TAG = "MacSimulatorBench"

    val simulatedMacId = "mac_m3_pro_sequoia"
    val simulatedMacName = "MacBook Pro (M3 Max)"
    val simulatedMacFingerprint = "7E:3A:99:BC:11:42:5D:88:9F:22:A6:44:CD:10:81:EE:55:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD"
    val simulatedMacIp = "192.168.1.142"
    val simulatedMacPort = 8990
    var currentOneTimeSecret: String = "842915"
        private set

    private val _isSimulatedRunning = MutableStateFlow(false)
    val isSimulatedRunning: StateFlow<Boolean> = _isSimulatedRunning.asStateFlow()

    fun getPairingPayloadUri(): String {
        return "macbridge://pair?id=$simulatedMacId&name=${simulatedMacName.replace(" ", "%20")}&fingerprint=$simulatedMacFingerprint&ip=$simulatedMacIp&port=$simulatedMacPort&secret=$currentOneTimeSecret"
    }

    fun generateNewSecret(): String {
        currentOneTimeSecret = (100000 + (Math.random() * 900000).toInt()).toString()
        return currentOneTimeSecret
    }

    fun createPairedDeviceRecord(): PairedDevice {
        return PairedDevice(
            id = simulatedMacId,
            name = simulatedMacName,
            fingerprint = simulatedMacFingerprint,
            pinnedPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7e...simulated...MacPublicKey",
            lastKnownIp = simulatedMacIp,
            port = simulatedMacPort,
            pairedTimestamp = System.currentTimeMillis(),
            allowClipboard = true,
            allowFileTransfer = true,
            allowNotifications = true
        )
    }

    fun simulatePushClipboardFromMac(text: String = "https://github.com/pratik/macbridge-kmp") {
        scope.launch(Dispatchers.Default) {
            DiagnosticLogger.i(TAG, "Simulated Mac: Pushing clipboard to Android: \"$text\"")
            val msg = ProtocolMessage.ClipboardSync(
                content = text,
                sourceDevice = simulatedMacName
            )
            onSimulatedMessage(msg)
        }
    }

    fun simulateIncomingFileFromMac(
        fileName: String = "Architecture_Roadmap.pdf",
        totalBytes: Long = 512 * 1024 // 512 KB
    ) {
        scope.launch(Dispatchers.Default) {
            val transferId = UUID.randomUUID().toString()
            val dummyContent = "MacBridge binary stream payload from MacBook Pro M3 Max. Phase 5 file transfer chunk.\n".repeat(100)
            val sha256 = calculateSha256(dummyContent.toByteArray())

            DiagnosticLogger.i(TAG, "Simulated Mac: Initiating incoming file \"$fileName\" ($totalBytes bytes)")
            val initMsg = ProtocolMessage.FileInit(
                transferId = transferId,
                fileName = fileName,
                fileSize = totalBytes,
                sha256Checksum = sha256,
                mimeType = "application/pdf",
                chunkSize = 64 * 1024
            )
            onSimulatedMessage(initMsg)

            delay(300)
            val chunkSize = 64 * 1024
            val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize).toInt()
            val base64Data = android.util.Base64.encodeToString(dummyContent.toByteArray().take(1024).toByteArray(), android.util.Base64.NO_WRAP)

            for (i in 0 until totalChunks) {
                delay(200)
                val chunk = ProtocolMessage.FileChunk(
                    transferId = transferId,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    offset = (i * chunkSize).toLong(),
                    dataBase64 = base64Data,
                    chunkLength = chunkSize
                )
                onSimulatedMessage(chunk)
            }

            delay(200)
            DiagnosticLogger.i(TAG, "Simulated Mac: All chunks sent. Awaiting integrity verification.")
        }
    }

    fun simulateNotificationDismissFromMac(notificationId: String) {
        scope.launch(Dispatchers.Default) {
            DiagnosticLogger.i(TAG, "Simulated Mac: User dismissed notification on macOS notification center: $notificationId")
            val msg = ProtocolMessage.NotificationAction(
                notificationId = notificationId,
                actionType = "DISMISS"
            )
            onSimulatedMessage(msg)
        }
    }

    private fun calculateSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
