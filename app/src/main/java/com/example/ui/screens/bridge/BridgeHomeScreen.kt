package com.example.ui.screens.bridge

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.ui.connectionLabel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun BridgeHomeScreen(bridgeManager: BridgeManager, onNavigateToPairing: () -> Unit,
    onNavigateToClipboard: () -> Unit, onNavigateToFiles: () -> Unit, modifier: Modifier = Modifier) {
    val state by bridgeManager.secureTransport.connectionState.collectAsState()
    val devices by bridgeManager.pairedDevices.collectAsState()
    HomeWorkspace(state, devices, onConnect = {
        val eligible = devices.filter { !it.isBlocked }
        if (eligible.size == 1) bridgeManager.connectToDevice(eligible.single()) else onNavigateToPairing()
    }, onDisconnect = { bridgeManager.disconnect() }, onClipboard = onNavigateToClipboard,
        onFiles = onNavigateToFiles, modifier = modifier)
}

@Composable
fun HomeWorkspace(state: ConnectionState, devices: List<PairedDevice>, onConnect: () -> Unit,
    onDisconnect: () -> Unit, onClipboard: () -> Unit, onFiles: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenTitle("Home", "Connect your Mac. Pick up where you left off.")
        ConnectionHeroCard(state, devices, onConnect, onDisconnect)
        if (devices.any { !it.isBlocked }) {
            Panel {
                Text("Share", style = MaterialTheme.typography.titleLarge)
                Text("Clipboard text and files, in both directions.", color = Slate400,
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onClipboard, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_clipboard")) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Clipboard")
                }
                OutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_files")) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Files")
                }
            }
        }
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
                    style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(when {
                    connected?.isSimulated == true -> "Demo only. Connect a paired Mac to share."
                    connected != null -> "Verified and ready to share."
                    busy -> "Keep the Mac companion open. You can cancel and try again."
                    eligible.isEmpty() -> "Open the Mac companion and scan its QR code to get started."
                    else -> "Keep your Mac companion open on the same Wi-Fi or local network."
                }, color = Slate200, style = MaterialTheme.typography.bodyMedium)
                if (connected != null || busy) {
                    TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp).testTag("disconnect_button")) {
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
