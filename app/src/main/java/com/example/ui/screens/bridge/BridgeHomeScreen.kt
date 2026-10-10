package com.example.ui.screens.bridge

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.model.*
import com.example.ui.screens.files.FileTransferCard
import com.example.ui.screens.files.rememberFileTransferActions
import com.example.ui.SharingFeature
import com.example.ui.sharingUiState
import com.example.ui.connectionLabel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun BridgeHomeScreen(bridgeManager: BridgeManager, onNavigateToPairing: () -> Unit,
    onNavigateToClipboard: () -> Unit, onNavigateToFiles: () -> Unit, modifier: Modifier = Modifier) {
    val state by bridgeManager.secureTransport.connectionState.collectAsStateWithLifecycle()
    val devices by bridgeManager.pairedDevices.collectAsStateWithLifecycle()
    val transfers by bridgeManager.homeFileTransfers.collectAsStateWithLifecycle()
    val busy by bridgeManager.fileTransferManager.busy.collectAsStateWithLifecycle()
    val actions = rememberFileTransferActions(bridgeManager)
    val availability = sharingUiState(state, devices, SharingFeature.FILES)
    HomeWorkspace(state, devices, onConnect = {
        val eligible = devices.filter { !it.isBlocked }
        if (eligible.size == 1) bridgeManager.connectToDevice(eligible.single()) else onNavigateToPairing()
    }, onDisconnect = { bridgeManager.disconnect() }, onClipboard = onNavigateToClipboard,
        onFiles = onNavigateToFiles, modifier = modifier, transfers = transfers,
        canResume = availability.canSend && !busy, savingId = actions.savingId,
        canSave = !actions.savePickerOpen && actions.savingId == null,
        onResume = actions.resume, onCancel = actions.cancel, onSave = actions.save)
}

@Composable
fun HomeWorkspace(state: ConnectionState, devices: List<PairedDevice>, onConnect: () -> Unit,
    onDisconnect: () -> Unit, onClipboard: () -> Unit, onFiles: () -> Unit, modifier: Modifier = Modifier,
    transfers: List<FileTransferItem> = emptyList(), canResume: Boolean = false, savingId: String? = null,
    canSave: Boolean = true, onResume: (FileTransferItem) -> Unit = {},
    onCancel: (FileTransferItem) -> Unit = {}, onSave: (FileTransferItem) -> Unit = {}) {
    val visibleTransfers = remember(transfers) { homeTransfers(transfers) }
    val active = visibleTransfers.any { it.status in currentTransferStatuses }
    val transferSection: @Composable () -> Unit = {
        if (visibleTransfers.isNotEmpty()) {
            Text(if (active) "Current transfers" else "Latest transfers", style = MaterialTheme.typography.titleLarge)
            visibleTransfers.forEach { item ->
                key(item.transferId) {
                    FileTransferCard(item, savingId == item.transferId, onSave = { onSave(item) },
                        onCancel = { onCancel(item) }, canResume = canResume, onResume = { onResume(item) },
                        canSave = canSave, compact = true)
                }
            }
        }
    }
    Column(modifier.testTag("home_list").fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenTitle("Home", "Your Mac. Your transfers.")
        if (active) transferSection()
        ConnectionHeroCard(state, devices, onConnect, onDisconnect)
        if (!active) transferSection()
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

private val currentTransferStatuses = setOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING, TransferStatus.PAUSED)

/** Keep work in progress visible, with only the latest outcome in each idle direction. */
internal fun homeTransfers(history: List<FileTransferItem>): List<FileTransferItem> {
    val current = history.filter { it.status in currentTransferStatuses }.sortedByDescending { it.timestamp }
    val latest = TransferDirection.entries.mapNotNull { direction ->
        if (current.any { it.direction == direction }) null
        else history.filter { it.direction == direction }.maxByOrNull { it.timestamp }
    }.sortedByDescending { it.timestamp }
    return current + latest
}
