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
import com.example.model.TransferStatus
import com.example.ui.SharingFeature
import com.example.ui.sharingUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun FileTransferScreen(bridgeManager: BridgeManager, onDevices: () -> Unit) {
    val context = LocalContext.current
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var savingId by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingSave
        pendingSave = null
        if (uri != null && id != null) {
            savingId = id
            bridgeManager.scope.launch {
                val ok = bridgeManager.fileReceivingManager.saveAs(id, uri)
                savingId = null
                Toast.makeText(context, if (ok) "File saved" else "Could not save. Your verified copy is still here; try Save As again.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val state by bridgeManager.secureTransport.connectionState.collectAsState()
    val devices by bridgeManager.pairedDevices.collectAsState()
    val history by bridgeManager.fileTransfers.collectAsState()
    val busy by bridgeManager.fileTransferManager.busy.collectAsState()
    val availability = sharingUiState(state, devices, SharingFeature.FILES)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !bridgeManager.fileTransferManager.sendFile(uri)) Toast.makeText(context, "Resume or cancel your current transfer first.", Toast.LENGTH_LONG).show()
    }
    FileWorkspace(history, availability.canSend, busy, savingId, pendingSave != null,
        onChoose = { picker.launch(arrayOf("*/*")) }, onDevices = onDevices,
        onSave = { item -> pendingSave = item.transferId; saver.launch(item.fileName) },
        onResume = { item -> bridgeManager.scope.launch {
            if (!bridgeManager.fileTransferManager.resumeTransfer(item.transferId)) Toast.makeText(context, "Reconnect the original paired Mac to resume.", Toast.LENGTH_LONG).show()
        } }, onCancel = { item ->
            if (item.direction == TransferDirection.OUTGOING) bridgeManager.fileTransferManager.cancelTransfer(item.transferId)
            else bridgeManager.scope.launch { bridgeManager.fileReceivingManager.cancel(item.transferId) }
        }, guidance = availability.guidance)
}

@Composable
fun FileWorkspace(history: List<FileTransferItem>, enabled: Boolean, busy: Boolean, savingId: String?, savePickerOpen: Boolean,
    onChoose: () -> Unit, onDevices: () -> Unit, onSave: (FileTransferItem) -> Unit,
    onResume: (FileTransferItem) -> Unit, onCancel: (FileTransferItem) -> Unit, guidance: String? = null) {
    val (current, recent) = remember(history) { history.partition { it.status in listOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING, TransferStatus.PAUSED) } }
    val card: @Composable (FileTransferItem) -> Unit = { item ->
        FileTransferCard(item, savingId == item.transferId, onSave = { onSave(item) }, onCancel = { onCancel(item) },
            canResume = enabled && !busy, onResume = { onResume(item) }, canSave = !savePickerOpen && savingId == null)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("file_list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Files", "Send here. Save there.") }
        if (current.isNotEmpty()) {
            item { Text("Current transfers", style = MaterialTheme.typography.titleLarge) }
            items(current, key = { it.transferId }) { card(it) }
        }
        item { FileSendCard(enabled, busy, onChoose, onDevices,
            paused = current.any { it.direction == TransferDirection.OUTGOING && it.status == TransferStatus.PAUSED }, guidance = guidance) }
        if (recent.isNotEmpty()) {
            item { Text("Recent files", style = MaterialTheme.typography.titleLarge) }
            items(recent, key = { it.transferId }) { card(it) }
        } else if (current.isEmpty()) item {
            Panel {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Slate400)
                Text("No shared files yet", style = MaterialTheme.typography.titleMedium)
                Text("Choose a file above, or send one from your Mac.", color = Slate400)
            }
        }
    }
}

@Composable
fun FileSendCard(enabled: Boolean, busy: Boolean, onChoose: () -> Unit, onDevices: () -> Unit, paused: Boolean = false, guidance: String? = null) {
    Panel {
        FeatureHeading("Phone → Mac", Icons.Outlined.Description)
        Text("Files up to 100 MB", color = Slate400, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onChoose, enabled = enabled && !busy && !paused,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_file_button")) {
            Text(if (busy) "Transfer in progress…" else if (paused) "Transfer paused" else "Choose file")
        }
        if (paused) Text("Resume or cancel your paused transfer before choosing another file.", color = Slate400, style = MaterialTheme.typography.bodyMedium)
        if (!enabled) {
            Text(guidance ?: "Connect your Mac and turn on File sharing in Devices.", color = Slate400,
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("Open Devices") }
        }
        SharingHelp("How file sharing works") {
            Text("Phone → Mac: turn on Allow File Receiving in the Mac menu, then choose a file here. Find verified files in Show Received Files.", color = Slate400)
            Text("Mac → Phone: choose Send File to Phone… in the Mac menu, then Save As… on the received file below.", color = Slate400)
            Text("Interrupted or reopened the app? Reconnect the same device and resume within 10 minutes.", color = Slate400)
        }
    }
}

@Composable
fun FileTransferCard(item: FileTransferItem, saving: Boolean, onSave: () -> Unit, onCancel: () -> Unit, canResume: Boolean = false, onResume: () -> Unit = {}, canSave: Boolean = true) {
    Panel {
        Text(item.fileName, style = MaterialTheme.typography.titleMedium)
        val incoming = item.direction == TransferDirection.INCOMING
        Text(if (incoming) "Mac → Phone" else "Phone → Mac", color = Slate400,
            style = MaterialTheme.typography.labelMedium)
        val verified = item.status == TransferStatus.COMPLETED &&
            (if (incoming) item.transferId.startsWith("incoming-") && item.filePath != null && item.calculatedChecksum == item.sha256Checksum else item.transferId.startsWith("file-v1-"))
        val label = when (item.status) {
            TransferStatus.PENDING -> "Preparing document…"
            TransferStatus.TRANSFERRING -> "${if (incoming) "Receiving" else "Sending"} · ${transferPercent(item)}% · ${transferBytes(item.transferredBytes.coerceIn(0, item.fileSize.coerceAtLeast(0)))} / ${transferBytes(item.fileSize)}"
            TransferStatus.COMPLETED -> if (verified) (if (incoming) "Received & verified on this phone" else "Received & verified by Mac") else "Previous transfer record"
            TransferStatus.FAILED -> item.errorMessage ?: "Transfer failed. Send the file again to retry."
            TransferStatus.PAUSED -> if (incoming) "Paused. Reconnect and choose Resume File Sending on your Mac." else "Paused. Reconnect the same Mac to resume."
        }
        Text(label, color = if (item.status == TransferStatus.FAILED && item.errorMessage?.startsWith("Cancelled") != true) RoseNeon else Slate400)
        if (item.status == TransferStatus.TRANSFERRING) {
            LinearProgressIndicator(progress = { if (item.fileSize > 0) (item.transferredBytes.toFloat() / item.fileSize).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth())
        }
        if (!incoming && item.status == TransferStatus.PAUSED) {
            OutlinedButton(onClick = onResume, enabled = canResume, modifier = Modifier.fillMaxWidth()) { Text("Resume transfer") }
        }
        if (item.status in listOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING, TransferStatus.PAUSED)) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (item.direction == TransferDirection.INCOMING) "Cancel receiving" else "Cancel sending") }
        }
        if (incoming && verified) {
            OutlinedButton(onClick = onSave, enabled = canSave && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("save_file_${item.transferId}")) { Text(if (saving) "Saving…" else "Save As…") }
            Text("Choose where to save your verified copy.", color = Slate400, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun transferPercent(item: FileTransferItem): Int =
    if (item.fileSize > 0) (item.transferredBytes.coerceIn(0, item.fileSize).toDouble() / item.fileSize * 100).toInt() else 0

internal fun transferBytes(bytes: Long): String {
    val count = bytes.coerceAtLeast(0)
    return when {
        count < 1024 -> "$count B"
        count < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", count / 1024.0)
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", count / (1024.0 * 1024))
    }
}
