package com.example.manager

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.example.crypto.IdentityManager
import com.example.data.MacBridgeDatabase
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.model.ProtocolMessage
import com.example.network.MacSimulatorBench
import com.example.network.NsdDiscoveryManager
import com.example.network.SecureTransport
import com.example.network.discoveryEndpoint
import com.example.network.PairingCode
import com.example.network.PairingFailure
import com.example.service.MacBridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.example.ui.uiStateIn
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
        .uiStateIn(scope, emptyList())

    val clipboardHistory = database.clipboardDao().getAllClips()
        .uiStateIn(scope, emptyList())

    val fileTransfers = database.fileTransferDao().getAllTransfers()
        .uiStateIn(scope, emptyList())

    val homeFileTransfers = database.fileTransferDao().getHomeTransfers()
        .uiStateIn(scope, emptyList())

    val mirroredNotifications = database.notificationDao().getAllNotifications()
        .uiStateIn(scope, emptyList())

    val nsdManager = NsdDiscoveryManager(
        context = context,
        deviceId = identityManager.deviceId
    )

    // Content-free app preferences; sharing is opt-in for each app and each Mac.
    private val appMirrorPrefs = context.getSharedPreferences("macbridge_app_filters", Context.MODE_PRIVATE)

    lateinit var secureTransport: SecureTransport
    lateinit var clipboardManager: ClipboardSyncManager
    lateinit var fileReceivingManager: FileReceivingManager
    lateinit var fileTransferManager: FileTransferManager
    lateinit var macSimulator: MacSimulatorBench

    internal val notificationActions = NotificationActions()
    private val notificationRelay by lazy { NotificationRelay(CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        current = {
            val state = secureTransport.connectionState.value as? ConnectionState.Connected
            val saved = state?.let { database.pairedDeviceDao().getDeviceById(it.device.id) }
            if (state == null || state.isSimulated || saved == null || saved.isBlocked || saved.fingerprint != state.device.fingerprint) null
            else NotificationDestination(saved.id, saved.fingerprint, state.connectedSince, saved.allowNotifications)
        }, permitted = { isAppMirroringEnabled(it) && com.example.service.MacBridgeNotificationListener.isPermissionGranted(context) },
        send = { msg, target ->
            val sent = secureTransport.sendMessage(msg, target.id, target.session)
            if (sent && msg is ProtocolMessage.NotificationMirror) msg.dismissToken?.let { notificationActions.bind(msg.notificationId, it, target) }
            sent
        }) }

    init { initSubsystems() }


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

        fileTransferManager = FileTransferManager(context, database.fileTransferDao(), scope,
            target = { fileTarget() }, send = { message, destination -> sendFileFrame(message, destination) },
            retain = { original ->
                val saved = database.pairedDeviceDao().getDeviceById(original.device.id)
                saved != null && !saved.isBlocked && saved.allowFileTransfer && saved.fingerprint == original.device.fingerprint
            })
        fileReceivingManager = FileReceivingManager(context, database.fileTransferDao(), scope,
            target = { fileTarget() }, send = { message, destination -> sendFileFrame(message, destination) },
            retain = { original ->
                val saved = database.pairedDeviceDao().getDeviceById(original.device.id)
                saved != null && !saved.isBlocked && saved.allowFileTransfer && saved.fingerprint == original.device.fingerprint
            })

        macSimulator = MacSimulatorBench(
            scope = scope,
            onSimulatedMessage = { message ->
                val state = secureTransport.connectionState.value as? ConnectionState.Connected
                if (state?.isSimulated == true) scope.launch { handleIncomingMessage(message) }
            }
        )

        scope.launch {
            secureTransport.connectionState.collect { if (it !is ConnectionState.Connected) notificationActions.clear() }
        }
        // This version only initiates authenticated connections to the Mac.
        nsdManager.startDiscovery()
    }

    private suspend fun fileTarget(): FileTransferTarget? {
        val state = secureTransport.connectionState.value as? ConnectionState.Connected ?: return null
        val saved = database.pairedDeviceDao().getDeviceById(state.device.id) ?: return null
        if (state.isSimulated || saved.isBlocked || !saved.allowFileTransfer || saved.fingerprint != state.device.fingerprint) return null
        return FileTransferTarget(saved, state.connectedSince)
    }

    private suspend fun sendFileFrame(message: ProtocolMessage, destination: FileTransferTarget): Boolean {
        val state = secureTransport.connectionState.value as? ConnectionState.Connected ?: return false
        val saved = database.pairedDeviceDao().getDeviceById(destination.device.id) ?: return false
        if (state.isSimulated || state.device.id != destination.device.id || state.connectedSince != destination.session ||
            saved.isBlocked || saved.fingerprint != destination.device.fingerprint ||
            (!saved.allowFileTransfer && message !is ProtocolMessage.FileCancel && message !is ProtocolMessage.FileAck)) return false
        return secureTransport.sendMessage(message, destination.device.id)
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
        scope.launch(Dispatchers.IO) {
            val saved = database.pairedDeviceDao().getDeviceById(device.id)
            if (saved != null && !saved.isBlocked) {
                val hint = if (fallbackIp == null) discoveryEndpoint(saved, nsdManager.discoveredPeers.value) else null
                val target = saved.copy(lastKnownIp = fallbackIp ?: hint?.host ?: saved.lastKnownIp, port = hint?.port ?: saved.port)
                secureTransport.connectToDevice(target, target.lastKnownIp, target.port)
            }
        }
    }

    fun disconnect() {
        notificationActions.clear()
        secureTransport.disconnect()
        MacBridgeForegroundService.stop(context)
    }

    fun unpairDevice(device: PairedDevice) {
        notificationActions.clearTarget(device.id)
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
        if (!allowNotifications) notificationActions.clearTarget(device.id)
        scope.launch(Dispatchers.IO) {
            val updated = device.copy(
                allowClipboard = allowClipboard,
                allowFileTransfer = allowFiles,
                allowNotifications = allowNotifications
            )
            database.pairedDeviceDao().update(updated)
            if (!allowNotifications) clearNotificationMirrors(deviceId = device.id)
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

    fun isAppMirroringEnabled(packageName: String): Boolean = appMirrorPrefs.getBoolean("enabled_$packageName", false)
    fun notificationPreviewsEnabled(): Boolean = appMirrorPrefs.getBoolean("previews", false)
    fun setNotificationPreviews(enabled: Boolean) {
        notificationRelay.invalidate()
        appMirrorPrefs.edit().putBoolean("previews", enabled).apply()
        clearNotificationMirrors()
    }
    fun rememberNotificationApp(pkg: String, name: String) {
        if (pkg.length > 255 || name.length > 100) return
        if (!appMirrorPrefs.contains("name_$pkg") && notificationApps().size >= 100) return
        if (appMirrorPrefs.getString("name_$pkg", null) != name) appMirrorPrefs.edit().putString("name_$pkg", name).apply()
    }
    fun notificationApps(): List<Pair<String, String>> = appMirrorPrefs.all.entries
        .filter { it.key.startsWith("name_") && it.value is String }
        .map { it.key.removePrefix("name_") to (it.value as String) }.sortedBy { it.second.lowercase() }
    fun setAppMirroringEnabled(packageName: String, enabled: Boolean) {
        if (!enabled) notificationRelay.invalidate()
        appMirrorPrefs.edit().putBoolean("enabled_$packageName", enabled).apply()
        if (!enabled) clearNotificationMirrors(packageName)
    }
    private fun notificationDestination(): NotificationDestination? {
        val state = secureTransport.connectionState.value as? ConnectionState.Connected ?: return null
        if (state.isSimulated) return null
        return NotificationDestination(state.device.id, state.device.fingerprint, state.connectedSince, state.device.allowNotifications)
    }
    fun handleOutgoingNotification(mirrorMsg: ProtocolMessage.NotificationMirror) {
        val target = notificationDestination() ?: return
        notificationRelay.offer(target, mirrorMsg)
    }
    fun handleNotificationDismissedLocally(notificationKey: String) {
        if (notificationKey.toByteArray().size > 512) return
        val target = notificationDestination() ?: return
        if (!notificationRelay.offer(target, ProtocolMessage.NotificationAction(notificationKey, "REMOVE"))) secureTransport.disconnect()
    }
    fun clearNotificationMirrors(packageName: String = "", deviceId: String? = null) {
        val target = notificationDestination()
        if (deviceId != null && target?.id != deviceId) return
        notificationRelay.invalidate()
        notificationActions.clear(packageName)
        if (target == null) return
        if (!notificationRelay.offer(target, ProtocolMessage.NotificationAction(packageName, "CLEAR"))) secureTransport.disconnect()
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
            is ProtocolMessage.FileInit, is ProtocolMessage.FileChunk, is ProtocolMessage.FileCancel -> {
                if (!state.isSimulated) fileReceivingManager.process(msg, FileTransferTarget(device, state.connectedSince))
            }
            is ProtocolMessage.NotificationAction -> {
                val token = msg.actionToken
                if (state.isSimulated || msg.actionType != "DISMISS" || token == null ||
                    !token.matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) || msg.notificationId.toByteArray().size > 512) return
                val target = NotificationDestination(device.id, device.fingerprint, state.connectedSince, device.allowNotifications)
                val result = withContext(Dispatchers.Main) {
                    // Recheck the original connection immediately before touching the system listener.
                    val live = secureTransport.connectionState.value as? ConnectionState.Connected
                    if (live?.device?.id != target.id || live.connectedSince != target.session) "STALE"
                    else com.example.service.MacBridgeNotificationListener.dismiss(this@BridgeManager, msg.notificationId, token, target)
                }
                secureTransport.sendMessage(ProtocolMessage.NotificationAction(msg.notificationId, "DISMISS_RESULT",
                    actionToken = token, status = result), target.id, target.session)
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
