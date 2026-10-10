package com.example.ui

import com.example.model.ConnectionState
import com.example.model.PairedDevice

data class SharingUiState(val canSend: Boolean, val guidance: String, val needsDevices: Boolean)

enum class SharingFeature { CLIPBOARD, FILES }

fun sharingUiState(state: ConnectionState, savedDevices: List<PairedDevice>, feature: SharingFeature = SharingFeature.CLIPBOARD): SharingUiState {
    val noun = if (feature == SharingFeature.FILES) "files" else "text"
    val connected = state as? ConnectionState.Connected
        ?: return SharingUiState(false, if (savedDevices.isEmpty()) "Pair your Mac to share $noun." else "Connect your Mac to share $noun.", true)
    if (connected.isSimulated) return SharingUiState(false, "A demo connection cannot send to your Mac. Connect a paired Mac.", true)
    val device = savedDevices.firstOrNull { it.id == connected.device.id }
    if (device == null || device.isBlocked) return SharingUiState(false, "This Mac is unavailable. Check your devices.", true)
    if (device.fingerprint != connected.device.fingerprint) return SharingUiState(false, "This Mac’s identity changed. Pair it again in Devices.", true)
    val allowed = if (feature == SharingFeature.FILES) device.allowFileTransfer else device.allowClipboard
    val label = if (feature == SharingFeature.FILES) "File" else "Clipboard"
    if (!allowed) return SharingUiState(false, "Turn on $label sharing for this Mac in Devices.", true)
    return SharingUiState(true, "Sharing is ready on your local network.", false)
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> if (state.isSimulated) "Demo connection" else "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Handshaking -> "Verifying Mac…"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Discovering -> "Looking for your Mac…"
    ConnectionState.Disconnected -> "Not connected"
}
