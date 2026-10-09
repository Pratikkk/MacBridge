package com.example.ui.screens.bridge

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.ui.connectionLabel
import com.example.ui.sharingUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun BridgeHomeScreen(bridgeManager: BridgeManager, onNavigateToPairing: () -> Unit,
    onNavigateToClipboard: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by bridgeManager.secureTransport.connectionState.collectAsState()
    val devices by bridgeManager.pairedDevices.collectAsState()
    val history by bridgeManager.clipboardHistory.collectAsState()
    var sending by remember { mutableStateOf(false) }
    val availability = sharingUiState(state, devices)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenTitle("Your Mac.\nWithin reach.", "Copy here. Continue there.")
        ConnectionHeroCard(state, devices,
            onConnect = {
                val eligible = devices.filter { !it.isBlocked }
                if (eligible.size == 1) bridgeManager.connectToDevice(eligible.single()) else onNavigateToPairing()
            }, onDisconnect = { bridgeManager.disconnect() })
        Panel {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
            Text("Send your clipboard", style = MaterialTheme.typography.titleLarge)
            Text(availability.guidance, color = Slate400, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                sending = true
                bridgeManager.pushClipboard { ok ->
                    sending = false
                    Toast.makeText(context, if (ok) "Clipboard sent to Mac" else "Couldn't send. Check your clipboard, connection and sharing permission.", Toast.LENGTH_LONG).show()
                }
            }, enabled = availability.canSend && !sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sync_clipboard_button")) {
                Text(if (sending) "Sending…" else "Send to Mac")
            }
            if (availability.needsDevices) {
                TextButton(onClick = onNavigateToPairing, modifier = Modifier.fillMaxWidth()) { Text("Open Devices") }
            }
        }
        OutlinedButton(onClick = onNavigateToClipboard, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(18.dp)) {
            Text("Clipboard history · ${history.size}")
        }
        Text("Just your devices, on your local network.", style = MaterialTheme.typography.bodyMedium, color = Slate400)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ConnectionHeroCard(connectionState: ConnectionState, devices: List<PairedDevice>,
    onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val connected = connectionState as? ConnectionState.Connected
    val busy = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Handshaking ||
        connectionState is ConnectionState.Reconnecting
    val eligible = devices.filter { !it.isBlocked }
    Surface(shape = RoundedCornerShape(28.dp), color = Slate900,
        border = BorderStroke(1.dp, Slate700), modifier = Modifier.fillMaxWidth()) {
        Box {
            FrostedBackdrop(Modifier.matchParentSize())
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.LaptopMac, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(connectionLabel(connectionState), color = Slate200, style = MaterialTheme.typography.labelLarge)
                Text(connected?.device?.name ?: eligible.singleOrNull()?.name ?: if (eligible.isEmpty()) "Meet your Mac" else "Your Macs",
                    style = MaterialTheme.typography.headlineMedium)
                Text(when {
                    connected?.isSimulated == true -> "Demo only. Connect a paired Mac to share."
                    connected != null -> "Ready on your local network. Your Mac identity is verified."
                    busy -> "Keep the Mac companion open. You can cancel and try again."
                    eligible.isEmpty() -> "Open the Mac companion and scan its QR code to get started."
                    else -> "Keep your Mac companion open on the same Wi-Fi or local network."
                }, color = Slate200, style = MaterialTheme.typography.bodyMedium)
                if (connected != null || busy) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("disconnect_button")) {
                        Text(if (busy) "Cancel connection" else "Disconnect")
                    }
                } else {
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("connect_button")) {
                        Text(if (eligible.isEmpty()) "Pair a Mac" else if (eligible.size == 1) "Connect to Mac" else "Choose a Mac")
                    }
                }
            }
        }
    }
}
