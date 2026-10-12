package com.example.ui.screens.settings

import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.manager.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.ui.components.*
import com.example.ui.screens.security.SecurityAuditScreen
import com.example.ui.theme.*

@Composable
fun SettingsScreen(bridgeManager: BridgeManager, onDevices: () -> Unit) {
    val context = LocalContext.current
    var storage by rememberSaveable { mutableStateOf(false) }
    BackHandler(storage) { storage = false }
    var notifications by rememberSaveable { mutableStateOf(false) }
    BackHandler(notifications) { notifications = false }
    var security by rememberSaveable { mutableStateOf(false) }
    BackHandler(security) { security = false }
    if (storage) {
        StorageScreen(bridgeManager, onBack = { storage = false })
    } else if (notifications) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { notifications = false }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("‹ Settings") }
            com.example.ui.screens.notifications.NotificationsScreen(bridgeManager, Modifier.weight(1f))
        }
    } else if (security) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { security = false }, modifier = Modifier.padding(horizontal = 16.dp).testTag("back_to_settings")) { Text("‹ Settings") }
            SecurityAuditScreen(bridgeManager, Modifier.weight(1f))
        }
    } else {
        SettingsContent(onDevices, onStorage = { storage = true }, onNotifications = { notifications = true }, onSecurity = { security = true }, onBattery = {
            try { context.startActivity(bridgeManager.getBatteryOptimizationIntent()) }
            catch (_: Exception) { Toast.makeText(context, "Open Android Settings → Apps → MacBridge → Battery", Toast.LENGTH_LONG).show() }
        })
    }
}

@Composable
fun SettingsContent(onDevices: () -> Unit, onSecurity: () -> Unit, onBattery: () -> Unit, onNotifications: () -> Unit = {}, onStorage: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenTitle("Settings", "Sharing and background access.")
        Panel {
            Text("Sharing permissions", style = MaterialTheme.typography.titleLarge)
            Text("Choose which Macs can exchange clipboard text and files. New pairings start with sharing off.", color = Slate400)
            OutlinedButton(onClick = onDevices, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage devices") }
        }
        Panel {
            Text("Phone notifications", style = MaterialTheme.typography.titleLarge)
            Text("Choose which apps can send alerts to your Mac. Message previews start hidden.", color = Slate400)
            TextButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Set up notifications") }
        }
        Panel {
            Text("Storage", style = MaterialTheme.typography.titleLarge)
            Text("Manage received copies and local history. Saved exports stay yours.", color = Slate400)
            TextButton(onClick = onStorage, modifier = Modifier.fillMaxWidth().testTag("open_storage")) { Text("Manage storage") }
        }
        Panel {
            Text("Background connection", style = MaterialTheme.typography.titleLarge)
            Text("If Android disconnects while the app is in the background, review battery settings. Clipboard access may still require opening the app.", color = Slate400)
            TextButton(onClick = onBattery, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open battery settings") }
        }
        Panel {
            Text("Security & diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Inspect device identity, implemented protections and local connection logs.", color = Slate400)
            TextButton(onClick = onSecurity, modifier = Modifier.fillMaxWidth().testTag("open_security_details")) { Text("View details") }
        }
        Text("MacBridge · Development build", style = MaterialTheme.typography.bodyMedium, color = Slate400)
    }
}

@Composable
private fun StorageScreen(bridge: BridgeManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() { usage = withContext(Dispatchers.IO) { bridge.fileReceivingManager.storage.usage() } }
    LaunchedEffect(Unit) { runCatching { refresh() }.onFailure { status = "Storage unavailable. Check app storage access." } }
    fun work(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; refresh() }
            catch (error: Exception) { if (error is CancellationException) throw error; status = "Could not update storage. Try again." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        ScreenTitle("Storage", "Keep what you need.")
        Panel {
            Text(usage?.let { "${it.bytes / MIB} MB used · ${it.files}/100 files" } ?: "Checking storage…")
            Text("Received-file limit", style = MaterialTheme.typography.titleMedium)
            Row {
                RECEIVE_QUOTAS.forEach { limit ->
                    TextButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = { work { bridge.fileReceivingManager.storage.setLimit(limit) } }) {
                        Text("${if (usage?.limit == limit) "✓ " else ""}${limit / MIB} MB")
                    }
                }
            }
            Text("When full, new files are rejected. Lowering the limit keeps existing files. Active and paused transfers remain available.", color = Slate400)
            OutlinedButton(enabled = !busy, onClick = { confirmation = true }) { Text("Remove received copies…") }
        }
        Panel {
            Text("History keeps the latest 100 clipboard entries and 200 finished transfer records. Received copies and unfinished transfers keep their records until removed.")
            TextButton(enabled = !busy, onClick = { work {
                val dao = bridge.database.fileTransferDao()
                dao.getAllTransfers().first().filter { it.filePath == null && it.status in listOf(com.example.model.TransferStatus.COMPLETED, com.example.model.TransferStatus.FAILED) }.forEach { dao.deleteById(it.transferId) }
                status = "Finished transfer history cleared. Received copies and active transfers kept."
            } }) { Text("Clear finished transfer history") }
            TextButton(enabled = !busy, onClick = { work { bridge.database.clipboardDao().clearAll(); status = "Clipboard history cleared." } }) { Text("Clear clipboard history") }
        }
        status?.let { Text(it) }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("Remove private received copies?") },
        text = { Text("Save files you need with Save As first. This removes completed copies and their records from MacBridge. Files exported to other apps or folders and active transfers are kept.") },
        confirmButton = { TextButton(onClick = { confirmation = false; work { val count = bridge.fileReceivingManager.clearReceivedCopies(); status = "$count received copies removed." } }) { Text("Remove copies") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("Keep copies") } })
}
