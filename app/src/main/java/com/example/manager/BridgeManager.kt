package com.example.manager

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.example.crypto.IdentityManager
import com.example.data.MacBridgeDatabase
import com.example.model.ConnectionState
import com.example.model.MirroredNotification
import com.example.model.PairedDevice
import com.example.model.ProtocolMessage
import com.example.network.MacSimulatorBench
import com.example.network.NsdDiscoveryManager
import com.example.network.SecureTransport
import com.example.network.PairingCode
import com.example.network.PairingFailure
import com.example.service.MacBridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main coordinator managing all 8 phases of the Android-Mac Bridge roadmap.
 */
class BridgeManager(
    val context: Context,
    val scope: CoroutineScope
) {
    private val TAG = "BridgeManager"
    val database = MacBridgeDatabase.getInstance(context)

    val identityManager = IdentityManager(context)

    val pairedDevices = database.pairedDeviceDao().getAllPairedDevices()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val clipboardHistory = database.clipboardDao().getAllClips()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val fileTransfers = database.fileTransferDao().getAllTransfers()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val mirroredNotifications = database.notificationDao().getAllNotifications()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val nsdManager = NsdDiscoveryManager(
        context = context,
        deviceId = identityManager.deviceId,
        deviceFingerprint = identityManager.getFingerprint()
    )

    // App-specific notification mirror filter preferences
    private val appMirrorPrefs = context.getSharedPreferences("macbridge_app_filters", Context.MODE_PRIVATE)

    lateinit var secureTransport: SecureTransport
    lateinit var clipboardManager: ClipboardSyncManager
    lateinit var fileTransferManager: FileTransferManager
    lateinit var macSimulator: MacSimulatorBench

    init {
        initSubsystems()
    }

    private fun initSubsystems() {
        secureTransport = SecureTransport(
            identityManager = identityManager,
            scope = scope,
            onMessageReceived = { message, source -> handleIncomingMessage(message, source.id) },
            onDeviceVerified = { onDeviceVerified(it) }
        )

        clipboardManager = ClipboardSyncManager(
            context = context,
            clipboardDao = database.clipboardDao(),
            scope = scope,
            sendProtocolMessage = { secureTransport.sendMessage(it) }
        )

        fileTransferManager = FileTransferManager(
            context = context,
            fileTransferDao = database.fileTransferDao(),
            scope = scope,
            target = {
                val state = secureTransport.connectionState.value as? ConnectionState.Connected
                val saved = state?.let { database.pairedDeviceDao().getDeviceById(it.device.id) }
                if (state == null || state.isSimulated || saved == null || saved.isBlocked || !saved.allowFileTransfer) null
                else FileTransferTarget(saved, state.connectedSince)
            },
            send = { message, destination ->
                val state = secureTransport.connectionState.value as? ConnectionState.Connected
                val saved = database.pairedDeviceDao().getDeviceById(destination.device.id)
                if (state?.device?.id != destination.device.id || state.connectedSince != destination.session ||
                    saved == null || saved.isBlocked || (!saved.allowFileTransfer && message !is ProtocolMessage.FileCancel)) false
                else secureTransport.sendMessage(message, destination.device.id)
            }
        )

        macSimulator = MacSimulatorBench(
            scope = scope,
            onSimulatedMessage = { message ->
                val state = secureTransport.connectionState.value as? ConnectionState.Connected
                if (state?.isSimulated == true) scope.launch { handleIncomingMessage(message) }
            }
        )

        // This version only initiates authenticated connections to the Mac.
        nsdManager.startDiscovery()
    }

    val isConnected: Boolean
        get() = secureTransport.connectionState.value is ConnectionState.Connected

    fun pairFromQrPayload(rawUri: String, onResult: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var endpoint: String? = null
            val result = try {
                val code = PairingCode.parse(rawUri)
                endpoint = "${code.device.lastKnownIp}:${code.device.port}"
                secureTransport.pairDevice(code.device, code.secret) { verified ->
                    database.pairedDeviceDao().insertOrUpdate(verified)
                }
                true to "Paired securely with ${code.device.name}. Turn on Clipboard sharing below to share text."
            } catch (e: Exception) {
                false to PairingFailure.message(e, endpoint)
            }
            withContext(Dispatchers.Main) { onResult(result.first, result.second) }
        }
    }

    fun connectToDevice(device: PairedDevice, fallbackIp: String? = null) {
        val targetIp = fallbackIp ?: device.lastKnownIp
        scope.launch(Dispatchers.IO) {
            val saved = database.pairedDeviceDao().getDeviceById(device.id)
            if (saved != null && !saved.isBlocked) secureTransport.connectToDevice(saved, targetIp, saved.port)
        }
    }

    fun disconnect() {
        secureTransport.disconnect()
        MacBridgeForegroundService.stop(context)
    }

    fun unpairDevice(device: PairedDevice) {
        scope.launch(Dispatchers.IO) {
            database.pairedDeviceDao().delete(device)
            if (secureTransport.isTargetDevice(device.id)) {
                disconnect()
            }
            DiagnosticLogger.i(TAG, "Device ${device.name} unpaired and certificate unpinned")
        }
    }

    fun updateDevicePermissions(
        device: PairedDevice,
        allowClipboard: Boolean,
        allowFiles: Boolean,
        allowNotifications: Boolean
    ) {
        scope.launch(Dispatchers.IO) {
            val updated = device.copy(
                allowClipboard = allowClipboard,
                allowFileTransfer = allowFiles,
                allowNotifications = allowNotifications
            )
            database.pairedDeviceDao().update(updated)
            DiagnosticLogger.i(TAG, "Updated permissions for ${device.name}: Clipboard=$allowClipboard, Files=$allowFiles, Notifications=$allowNotifications")
        }
    }

    fun pushClipboard(onResult: (Boolean) -> Unit = {}) {
        // Android clipboard access stays on the UI thread; socket writes run on IO.
        val text = clipboardManager.readCurrentText()
        scope.launch {
            val result = sendSharedText(text)
            withContext(Dispatchers.Main) { onResult(result == TextSendResult.SENT) }
        }
    }

    suspend fun sendSharedText(text: String?): TextSendResult = withContext(Dispatchers.IO) {
        if (text.isNullOrBlank()) return@withContext TextSendResult.EMPTY_TEXT
        val state = secureTransport.connectionState.value as? ConnectionState.Connected
            ?: return@withContext TextSendResult.NOT_CONNECTED
        val device = database.pairedDeviceDao().getDeviceById(state.device.id)
            ?: return@withContext TextSendResult.PERMISSION_DENIED
        if (device.isBlocked || !device.allowClipboard) {
            return@withContext TextSendResult.PERMISSION_DENIED
        }
        val current = secureTransport.connectionState.value as? ConnectionState.Connected
        if (current?.device?.id != state.device.id || current.connectedSince != state.connectedSince) {
            return@withContext TextSendResult.NOT_CONNECTED
        }
        clipboardManager.sendTextToMac(text, device.name) { secureTransport.sendMessage(it, device.id) }
    }

    fun isAppMirroringEnabled(packageName: String): Boolean {
        return appMirrorPrefs.getBoolean("mirror_$packageName", true)
    }

    fun setAppMirroringEnabled(packageName: String, enabled: Boolean) {
        appMirrorPrefs.edit().putBoolean("mirror_$packageName", enabled).apply()
        DiagnosticLogger.d(TAG, "Mirroring for $packageName set to: $enabled")
    }

    fun handleOutgoingNotification(mirrorMsg: ProtocolMessage.NotificationMirror) {
        val state = secureTransport.connectionState.value
        if (state is ConnectionState.Connected && state.device.allowNotifications) {
            secureTransport.sendMessage(mirrorMsg)
        }
        scope.launch(Dispatchers.IO) {
            database.notificationDao().insert(
                MirroredNotification(
                    notificationId = mirrorMsg.notificationId,
                    packageName = mirrorMsg.packageName,
                    appName = mirrorMsg.appName,
                    title = mirrorMsg.title,
                    text = mirrorMsg.text,
                    timestamp = mirrorMsg.timestamp,
                    hasReply = mirrorMsg.hasReplyAction
                )
            )
        }
    }

    fun handleNotificationDismissedLocally(notificationKey: String) {
        val dismissMsg = ProtocolMessage.NotificationAction(
            notificationId = notificationKey,
            actionType = "DISMISS"
        )
        secureTransport.sendMessage(dismissMsg)
        scope.launch(Dispatchers.IO) {
            database.notificationDao().markDismissed(notificationKey)
        }
    }

    private suspend fun handleIncomingMessage(msg: ProtocolMessage, sourceId: String? = null) {
        val state = secureTransport.connectionState.value as? ConnectionState.Connected ?: return
        if (sourceId != null && sourceId != state.device.id) return
        val device = if (state.isSimulated) state.device else database.pairedDeviceDao().getDeviceById(state.device.id) ?: return
        if (device.isBlocked || device.fingerprint != state.device.fingerprint) return
        val current = secureTransport.connectionState.value as? ConnectionState.Connected ?: return
        if (current.device.id != state.device.id || current.connectedSince != state.connectedSince) return
        when (msg) {
            is ProtocolMessage.ClipboardSync -> {
                val allowed = device.allowClipboard
                if (allowed) {
                    clipboardManager.handleIncomingClipboardFromMac(msg)
                } else {
                    DiagnosticLogger.w(TAG, "Clipboard sync blocked by per-device permission")
                }
            }
            is ProtocolMessage.FileAck -> fileTransferManager.handleAck(msg, device.id)
            is ProtocolMessage.NotificationAction -> {
                if (device.allowNotifications && msg.actionType == "DISMISS") {
                    scope.launch(Dispatchers.IO) {
                        database.notificationDao().markDismissed(msg.notificationId)
                    }
                }
            }
            else -> {}
        }
    }

    private fun onDeviceVerified(device: PairedDevice) {
        try {
            MacBridgeForegroundService.start(context, device.name)
        } catch (e: RuntimeException) {
            DiagnosticLogger.w(TAG, "Background service could not start: ${e.javaClass.simpleName}")
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun onDestroy() {
        nsdManager.stop()
        secureTransport.stop()
        MacBridgeForegroundService.stop(context)
    }
}
