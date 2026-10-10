package com.example.ui.screens.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.model.FileTransferItem
import com.example.model.TransferDirection
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.model.TransferStatus
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun FileTransferScreen(bridgeManager: BridgeManager, onDevices: () -> Unit) {
    val context = LocalContext.current
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingSave
        pendingSave = null
        if (uri != null && id != null) {
            saving = true
            bridgeManager.scope.launch {
                val ok = bridgeManager.fileReceivingManager.saveAs(id, uri)
                saving = false
                Toast.makeText(context, if (ok) "File saved" else "Could not save. Your verified copy is still here; try Save As again.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val state by bridgeManager.secureTransport.connectionState.collectAsState()
    val devices by bridgeManager.pairedDevices.collectAsState()
    val history by bridgeManager.fileTransfers.collectAsState()
    val busy by bridgeManager.fileTransferManager.busy.collectAsState()
    val connected = state as? ConnectionState.Connected
    val device = devices.firstOrNull { it.id == connected?.device?.id }
    val enabled = connected != null && !connected.isSimulated && device?.allowFileTransfer == true && !device.isBlocked
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) bridgeManager.fileTransferManager.sendFile(uri)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenTitle("Files", "Verified sharing in both directions.") }
        item {
            FileSendCard(enabled, busy, onChoose = { picker.launch(arrayOf("*/*")) }, onDevices,
                paused = history.any { it.direction == TransferDirection.OUTGOING && it.status == TransferStatus.PAUSED })
        }
        item { Text("Recent files", style = MaterialTheme.typography.titleLarge) }
        if (history.isEmpty()) item {
            Panel {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Slate400)
                Text("Your shared files will appear here", style = MaterialTheme.typography.titleMedium)
                Text("Choose your first document to get started.", color = Slate400)
            }
        }
        items(history, key = { it.transferId }) { item ->
            FileTransferCard(item, saving,
                canResume = enabled && !busy,
                onResume = { bridgeManager.scope.launch {
                    if (!bridgeManager.fileTransferManager.resumeTransfer(item.transferId)) Toast.makeText(context, "Reconnect the original paired Mac to resume.", Toast.LENGTH_LONG).show()
                } },
                onSave = { pendingSave = item.transferId; saver.launch(item.fileName) },
                onCancel = {
                    if (item.direction == TransferDirection.OUTGOING) bridgeManager.fileTransferManager.cancelTransfer(item.transferId)
                    else bridgeManager.scope.launch { bridgeManager.fileReceivingManager.cancel(item.transferId) }
                })
        }
    }

}

@Composable
fun FileSendCard(enabled: Boolean, busy: Boolean, onChoose: () -> Unit, onDevices: () -> Unit, paused: Boolean = false) {
    Panel {
        FeatureHeading("Send a document", Icons.Outlined.Description)
        Text("Phone → Mac · Up to 100 MB", color = Slate400, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onChoose, enabled = enabled && !busy && !paused,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_file_button")) {
            Text(if (busy) "Transfer in progress…" else if (paused) "Transfer paused" else "Choose file")
        }
        if (paused) Text("Resume or cancel the paused transfer below before choosing another file.", color = Slate400, style = MaterialTheme.typography.bodyMedium)
        if (!enabled) {
            Text("Connect your Mac and turn on File sharing in Devices.", color = Slate400,
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("Open Devices") }
        }
        HorizontalDivider(color = Slate800)
        Text("On your Mac", style = MaterialTheme.typography.titleMedium)
        Text("Turn on Allow file receiving. Open Show Received Files to find your verified documents.",
            color = Slate400, style = MaterialTheme.typography.bodyMedium)
        Text("Receive: Mac menu → Send File to Phone… Then use Save As… below.", color = Slate400,
            style = MaterialTheme.typography.bodyMedium)
        Text("Interrupted? Reconnect the same device and resume within 10 minutes. Keep both apps running.", color = Slate400,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FileTransferCard(item: FileTransferItem, saving: Boolean, onSave: () -> Unit, onCancel: () -> Unit, canResume: Boolean = false, onResume: () -> Unit = {}) {
    Panel {
        Text(item.fileName, style = MaterialTheme.typography.titleMedium)
        val incoming = item.direction == TransferDirection.INCOMING
        Text(if (incoming) "Mac → Phone" else "Phone → Mac", color = Slate400,
            style = MaterialTheme.typography.labelMedium)
        val verified = item.status == TransferStatus.COMPLETED &&
            (if (incoming) item.transferId.startsWith("incoming-") && item.filePath != null && item.calculatedChecksum == item.sha256Checksum else item.transferId.startsWith("file-v1-"))
        val label = when (item.status) {
            TransferStatus.PENDING -> "Preparing document…"
            TransferStatus.TRANSFERRING -> "${if (incoming) "Receiving" else "Sending"} · ${item.transferredBytes / 1024} / ${item.fileSize / 1024} KB"
            TransferStatus.COMPLETED -> if (verified) (if (incoming) "Received & verified on this phone" else "Received & verified by Mac") else "Previous transfer record"
            TransferStatus.FAILED -> item.errorMessage ?: "Transfer failed. Send the file again to retry."
            TransferStatus.PAUSED -> if (incoming) "Paused. Reconnect and choose Resume File Sending on your Mac." else "Paused. Reconnect the same Mac to resume."
        }
        Text(label, color = if (item.status == TransferStatus.FAILED) RoseNeon else Slate400)
        if (item.status == TransferStatus.TRANSFERRING) {
            LinearProgressIndicator(progress = { if (item.fileSize > 0) (item.transferredBytes.toFloat() / item.fileSize).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth())
        }
        if (item.status in listOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING, TransferStatus.PAUSED)) {
            TextButton(onClick = onCancel) { Text("Cancel transfer") }
        }
        if (!incoming && item.status == TransferStatus.PAUSED) {
            OutlinedButton(onClick = onResume, enabled = canResume, modifier = Modifier.fillMaxWidth()) { Text("Resume transfer") }
        }
        if (incoming && verified) {
            OutlinedButton(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("save_file_${item.transferId}")) { Text(if (saving) "Saving…" else "Save As…") }
            Text("This copy stays private until you choose where to save it.", color = Slate400,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
