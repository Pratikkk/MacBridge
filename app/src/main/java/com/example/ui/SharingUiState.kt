package com.example.ui

import com.example.model.ConnectionState
import com.example.model.PairedDevice

data class SharingUiState(val canSend: Boolean, val guidance: String, val needsDevices: Boolean)

fun sharingUiState(state: ConnectionState, savedDevices: List<PairedDevice>): SharingUiState {
    val connected = state as? ConnectionState.Connected
        ?: return SharingUiState(false, if (savedDevices.isEmpty()) "Pair your Mac to start sharing text." else "Connect your Mac to send text.", true)
    if (connected.isSimulated) return SharingUiState(false, "A demo connection cannot send to your Mac. Connect a paired Mac.", true)
    val device = savedDevices.firstOrNull { it.id == connected.device.id }
    if (device == null || device.isBlocked) return SharingUiState(false, "This Mac is unavailable. Check your devices.", true)
    if (!device.allowClipboard) return SharingUiState(false, "Turn on Clipboard sharing for this Mac in Devices.", true)
    return SharingUiState(true, "Text is sent over your encrypted local connection.", false)
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> if (state.isSimulated) "Demo connection" else "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Handshaking -> "Verifying Mac…"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Discovering -> "Looking for your Mac…"
    ConnectionState.Disconnected -> "Not connected"
}
