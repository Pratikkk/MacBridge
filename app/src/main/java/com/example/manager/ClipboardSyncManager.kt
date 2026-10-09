package com.example.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.data.ClipboardDao
import com.example.model.ClipboardItem
import com.example.model.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TextSendResult {
    SENT, EMPTY_TEXT, NOT_CONNECTED, PERMISSION_DENIED, SEND_FAILED
}

/**
 * Phase 4: Clipboard Sync.
 * Handles Android 10+ background restrictions with multi-surface triggers:
 * - Direct app focus sync
 * - Quick Settings Tile
 * - Persistent notification quick action
 * - Share sheet target
 */
class ClipboardSyncManager(
    private val context: Context,
    private val clipboardDao: ClipboardDao,
    private val scope: CoroutineScope,
    private val sendProtocolMessage: (ProtocolMessage) -> Boolean
) {
    private val TAG = "ClipboardSyncManager"
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _lastCopiedText = MutableStateFlow<String?>(null)
    val lastCopiedText: StateFlow<String?> = _lastCopiedText.asStateFlow()

    private var lastSyncedHash: Int = 0

    init {
        clipboard.addPrimaryClipChangedListener {
            onPrimaryClipChanged()
        }
    }

    private fun onPrimaryClipChanged() {
        try {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank() && text.hashCode() != lastSyncedHash) {
                    _lastCopiedText.value = text
                    DiagnosticLogger.d(TAG, "Local clipboard updated (${text.length} characters)")
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "Could not access clipboard: ${e.message}")
        }
    }

    /**
     * Triggered manually by user tap, Quick Settings Tile, or persistent notification action.
     */
    fun readCurrentText(): String? = try {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
    } catch (e: Exception) {
        DiagnosticLogger.w(TAG, "Could not read clipboard: ${e.javaClass.simpleName}")
        null
    }

    fun pushCurrentClipboardToMac(deviceName: String): Boolean =
        sendTextToMac(readCurrentText(), deviceName) == TextSendResult.SENT

    /** Sends the supplied text without reading or replacing the local clipboard. */
    fun sendTextToMac(
        text: String?,
        deviceName: String,
        send: (ProtocolMessage) -> Boolean = sendProtocolMessage
    ): TextSendResult {
        if (text.isNullOrBlank()) return TextSendResult.EMPTY_TEXT
        return try {
            val msg = ProtocolMessage.ClipboardSync(
                content = text,
                sourceDevice = "Android Phone"
            )
            val sent = send(msg)
            if (sent) {
                lastSyncedHash = text.hashCode()
                DiagnosticLogger.i(TAG, "Sent text to $deviceName (${text.length} characters)")
                scope.launch(Dispatchers.IO) {
                    clipboardDao.insert(
                        ClipboardItem(
                            content = text,
                            sourceDeviceName = "Sent to $deviceName",
                            isOutgoing = true
                        )
                    )
                }
            }
            if (sent) TextSendResult.SENT else TextSendResult.SEND_FAILED
        } catch (e: Exception) {
            DiagnosticLogger.e(TAG, "Error sending text: ${e.message}")
            TextSendResult.SEND_FAILED
        }
    }

    fun handleIncomingClipboardFromMac(msg: ProtocolMessage.ClipboardSync) {
        scope.launch(Dispatchers.Main) {
            try {
                lastSyncedHash = msg.content.hashCode()
                val clipData = ClipData.newPlainText("MacBridge Copy", msg.content)
                clipboard.setPrimaryClip(clipData)
                _lastCopiedText.value = msg.content
                DiagnosticLogger.i(TAG, "Received clipboard text (${msg.content.length} characters)")

                scope.launch(Dispatchers.IO) {
                    clipboardDao.insert(
                        ClipboardItem(
                            content = msg.content,
                            sourceDeviceName = msg.sourceDevice,
                            isOutgoing = false
                        )
                    )
                }
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "Error setting incoming clipboard: ${e.message}")
            }
        }
    }
}
