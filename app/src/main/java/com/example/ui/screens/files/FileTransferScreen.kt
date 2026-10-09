package com.example.ui.screens.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.model.TransferStatus
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun FileTransferScreen(bridgeManager: BridgeManager, onDevices: () -> Unit) {
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
        item { ScreenTitle("Files", "From your phone, safely to your Mac.") }
        item {
            FileSendCard(enabled, busy, onChoose = { picker.launch(arrayOf("*/*")) }, onDevices)

        }
        if (history.isEmpty()) item { Text("Your file transfers will appear here.", color = Slate400) }
        items(history, key = { it.transferId }) { item ->
            Panel {
                Text(item.fileName, style = MaterialTheme.typography.titleMedium)
                val label = when (item.status) {
                    TransferStatus.PENDING -> "Preparing document…"
                    TransferStatus.TRANSFERRING -> "Sending · ${item.transferredBytes / 1024} / ${item.fileSize / 1024} KB"
                    TransferStatus.COMPLETED -> if (item.transferId.startsWith("file-v1-")) "Received & verified by Mac" else "Previous transfer record"
                    TransferStatus.FAILED -> item.errorMessage ?: "Transfer failed. Choose the file to retry."
                    TransferStatus.PAUSED -> "Interrupted. Choose the file to retry."
                }
                Text(label, color = if (item.status == TransferStatus.FAILED) RoseNeon else Slate400)
                if (item.status == TransferStatus.TRANSFERRING) {
                    LinearProgressIndicator(progress = { if (item.fileSize > 0) item.transferredBytes.toFloat() / item.fileSize else 0f },
                        modifier = Modifier.fillMaxWidth())
                }
                if (item.status in listOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING)) {
                    TextButton(onClick = { bridgeManager.fileTransferManager.cancelTransfer(item.transferId) }) { Text("Cancel transfer") }
                }
            }
        }
    }
}

@Composable
fun FileSendCard(enabled: Boolean, busy: Boolean, onChoose: () -> Unit, onDevices: () -> Unit) {
            Panel {
                Text("Send a document", style = MaterialTheme.typography.titleLarge)
                Text("Choose a file up to 100 MB. On your Mac, turn on Allow file receiving. Files appear in Received Files only after verification.", color = Slate400)
                Button(onClick = onChoose, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_file_button")) {
                    Text(if (busy) "Transfer in progress…" else "Choose file")
                }
                if (!enabled) {
                    Text("Connect your Mac and turn on File sharing in Devices.", color = Slate400)
                    TextButton(onClick = onDevices) { Text("Open Devices") }
                }
                Text("Phone → Mac for now. Interrupted transfers can be retried by choosing the file again.", color = Slate400,
                    style = MaterialTheme.typography.bodyMedium)
            }
}
