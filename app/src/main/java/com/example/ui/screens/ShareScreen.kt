package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.example.manager.BridgeManager
import com.example.ui.screens.clipboard.ClipboardScreen
import com.example.ui.screens.files.FileTransferScreen

@Composable
fun ShareScreen(manager: BridgeManager, onDevices: () -> Unit, files: Boolean, onSelect: (Boolean) -> Unit) {
    ShareWorkspace(files, onSelect,
        clipboard = { ClipboardScreen(manager, onDevices) },
        filesPage = { FileTransferScreen(manager, onDevices) })
}

@Composable
fun ShareWorkspace(files: Boolean, onSelect: (Boolean) -> Unit,
    clipboard: @Composable () -> Unit, filesPage: @Composable () -> Unit) {
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        ShareTabs(files, onSelect)
        Box(Modifier.weight(1f)) {
            pages.SaveableStateProvider(if (files) "files" else "clipboard") {
                key(files) { if (files) filesPage() else clipboard() }
            }
        }
    }
}

@Composable
fun ShareTabs(files: Boolean, onSelect: (Boolean) -> Unit) {
    Surface(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
        color = Slate900, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Slate800)) {
        Row(Modifier.padding(4.dp)) {
            for ((selectedFiles, label) in listOf(false to "Clipboard", true to "Files")) {
                val selected = files == selectedFiles
                Tab(selected = selected, onClick = { onSelect(selectedFiles) }, modifier = Modifier.weight(1f)) {
                    Surface(Modifier.fillMaxWidth(), color = if (selected) Slate50 else Slate900,
                        shape = RoundedCornerShape(14.dp)) {
                        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selected) Slate950 else Slate400)
                    }
                }
            }
        }
    }
}
