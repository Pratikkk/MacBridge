package com.example.ui.screens.clipboard

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ClipboardItem
import com.example.ui.SharingUiState
import com.example.ui.sharingUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ClipboardScreen(bridgeManager: BridgeManager, onManageDevices: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val systemClipboard = LocalClipboardManager.current
    val history by bridgeManager.clipboardHistory.collectAsState()
    val state by bridgeManager.secureTransport.connectionState.collectAsState()
    val devices by bridgeManager.pairedDevices.collectAsState()
    var sending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    ClipboardWorkspace(history, sharingUiState(state, devices), sending, feedback,
        onSend = {
            sending = true
            feedback = null
            bridgeManager.pushClipboard { ok ->
                sending = false
                feedback = if (ok) "Clipboard sent to Mac." else "Couldn't send. Copy some text, check your connection and sharing permission, then try again."
            }
        }, onManageDevices = onManageDevices,
        onClear = { bridgeManager.scope.launch { bridgeManager.database.clipboardDao().clearAll() } },
        onCopy = {
            systemClipboard.setText(AnnotatedString(it.content))
            Toast.makeText(context, "Copied to phone clipboard", Toast.LENGTH_SHORT).show()
        }, modifier = modifier)
}

@Composable
fun ClipboardWorkspace(history: List<ClipboardItem>, availability: SharingUiState, sending: Boolean,
    feedback: String?, onSend: () -> Unit, onManageDevices: () -> Unit, onClear: () -> Unit,
    onCopy: (ClipboardItem) -> Unit, modifier: Modifier = Modifier) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().testTag("clipboard_list"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenTitle("Clipboard", "Copy on one device. Paste on the other.") }
        item {
            Panel {
                FeatureHeading("Phone → Mac", Icons.Outlined.ContentPaste)
                if (!availability.canSend) Text(availability.guidance, color = Slate400)
                Button(onClick = onSend, enabled = availability.canSend && !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("push_clipboard_button")) {
                    Text(if (sending) "Sending…" else "Send clipboard to Mac")
                }
                if (availability.needsDevices) {
                    TextButton(onClick = onManageDevices, modifier = Modifier.fillMaxWidth().testTag("clipboard_manage_devices")) {
                        Text("Open Devices")
                    }
                }
                if (feedback != null) Text(feedback, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("clipboard_feedback"))
                SharingHelp("How clipboard sharing works") {
                    Text("Copy text on your phone, then tap Send clipboard to Mac. You can also share text from another app.", color = Slate400)
                    Text("Mac → Phone: choose Send Clipboard to Phone in the Mac menu. Received text goes to your phone clipboard when Clipboard sharing is on.", color = Slate400)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent text", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("clear_history_button")) {
                    Text("Clear")
                }
            }
        }
        if (history.isEmpty()) item {
            Panel {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = Slate400)
                Text("Your shared text will appear here", style = MaterialTheme.typography.titleMedium)
                Text("Send or receive your first clipboard to get started.", color = Slate400)
            }
        }
        items(history, key = { it.id }) { item ->
            ClipboardHistoryCard(item,
                remember(item.timestamp) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp)) }) { onCopy(item) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear clipboard history?") },
        text = { Text("This removes the history saved on this phone. Your current clipboard stays unchanged.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, modifier = Modifier.testTag("confirm_clear_history")) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
fun ClipboardHistoryCard(item: ClipboardItem, formattedDate: String, onCopy: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (item.isOutgoing) "Sent to Mac" else "Received from ${item.sourceDeviceName}", style = MaterialTheme.typography.titleMedium)
                Text(formattedDate, color = Slate400, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy shared text")
            }
        }
        SelectionContainer {
            Text(item.content, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
        }
        if (item.content.length > 160 || item.content.count { it == '\n' } >= 4) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show full text") }
        }
    }
}
