package com.example.ui.screens.files

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.manager.BridgeManager
import com.example.model.*
import com.example.sharing.SharedDocumentInfo
import com.example.sharing.inspectSharedDocument
import com.example.ui.SharingFeature
import com.example.ui.sharingUiState

@Composable
internal fun SharedFileDialog(manager: BridgeManager, uri: Uri, onDismiss: () -> Unit, onSent: () -> Unit,
    onDevices: () -> Unit, onHome: () -> Unit) {
    val context = LocalContext.current
    val devices by manager.pairedDevices.collectAsStateWithLifecycle()
    val connection by manager.secureTransport.connectionState.collectAsStateWithLifecycle()
    val busy by manager.fileTransferManager.busy.collectAsStateWithLifecycle()
    val transfers by manager.homeFileTransfers.collectAsStateWithLifecycle()
    val document by produceState<SharedDocumentInfo?>(null, uri) { value = inspectSharedDocument(context, uri) }
    var selectedId by rememberSaveable(uri.toString()) { mutableStateOf<String?>(null) }
    var selectedPin by rememberSaveable(uri.toString()) { mutableStateOf<String?>(null) }
    var queued by remember(uri) { mutableStateOf(false) }
    var feedback by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(devices, connection) {
        if (selectedId == null) {
            val connected = connection as? ConnectionState.Connected
            val candidate = devices.firstOrNull { it.id == connected?.device?.id && it.fingerprint == connected.device.fingerprint }
                ?: devices.singleOrNull()
            if (candidate != null) { selectedId = candidate.id; selectedPin = candidate.fingerprint }
        }
    }
    val selected = devices.firstOrNull { it.id == selectedId && it.fingerprint == selectedPin && !it.isBlocked }
    SharedFileReview(document, devices, selected, connection, busy || queued,
        paused = transfers.any { it.direction == TransferDirection.OUTGOING && it.status == TransferStatus.PAUSED },
        feedback = feedback, onSelect = { selectedId = it.id; selectedPin = it.fingerprint; feedback = null },
        onConnect = { selected?.let(manager::connectToDevice) }, onDevices = onDevices, onHome = onHome,
        onSend = {
            if (!queued && selected != null) {
                queued = manager.fileTransferManager.sendFile(uri, selected)
                if (queued) onSent() else feedback = "Resume or cancel the current transfer on Home before sending another file."
            }
        }, onCancel = onDismiss)
}

@Composable
internal fun SharedFileReview(document: SharedDocumentInfo?, devices: List<PairedDevice>, selected: PairedDevice?,
    connection: ConnectionState, busy: Boolean, paused: Boolean, feedback: String?,
    onSelect: (PairedDevice) -> Unit, onConnect: () -> Unit, onDevices: () -> Unit, onHome: () -> Unit,
    onSend: () -> Unit, onCancel: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val state = connection as? ConnectionState.Connected
    val matches = selected != null && state != null && !state.isSimulated &&
        selected.id == state.device.id && selected.fingerprint == state.device.fingerprint
    val permission = sharingUiState(connection, devices, SharingFeature.FILES)
    val canSend = matches && permission.canSend && !busy && !paused && document != null && document.error == null
    AlertDialog(onDismissRequest = onCancel,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Send file to Mac") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(document?.name ?: "Reading file details…", style = MaterialTheme.typography.titleMedium)
                Text(document?.size?.let { "${transferBytes(it)} · up to 100 MB" } ?: "Size checked before sending · up to 100 MB")
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.name ?: "Choose a paired Mac", maxLines = 2)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        devices.filterNot { it.isBlocked }.forEach { peer ->
                            DropdownMenuItem(text = { Text(peer.name) }, onClick = { menuOpen = false; onSelect(peer) })
                        }
                    }
                }
                val guidance = when {
                    document?.error != null -> document.error
                    feedback != null -> feedback
                    busy -> "A file is already being sent. View its progress on Home."
                    paused -> "Resume or cancel your paused send on Home first."
                    selected == null -> if (devices.isEmpty()) "Pair your Mac in Devices, then return to this file." else "Choose a Mac. If its identity changed, review it in Devices."
                    !selected.allowFileTransfer -> "Turn on File sharing for this Mac in Devices."
                    !matches -> "Connect to the selected Mac, then tap Send file."
                    !permission.canSend -> permission.guidance
                    else -> "Delivery is verified on your Mac. Progress appears on Home."
                }
                Text(guidance)
                if (!matches && selected != null && selected.allowFileTransfer && !busy && !paused) {
                    OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth(),
                        enabled = connection !is ConnectionState.Connecting && connection !is ConnectionState.Handshaking && connection !is ConnectionState.Reconnecting) {
                        Text("Connect to ${selected.name}", maxLines = 2)
                    }
                }
                TextButton(onClick = if (busy || paused) onHome else onDevices) {
                    Text(if (busy || paused) "View transfers on Home" else "Manage devices")
                }
            }
        },
        confirmButton = { Button(onClick = { if (canSend) onSend() }, enabled = canSend) { Text("Send file") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
