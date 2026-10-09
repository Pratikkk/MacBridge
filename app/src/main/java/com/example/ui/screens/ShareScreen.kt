package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.manager.BridgeManager
import com.example.ui.screens.clipboard.ClipboardScreen
import com.example.ui.screens.files.FileTransferScreen

@Composable
fun ShareScreen(manager: BridgeManager, onDevices: () -> Unit) {
    var files by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (files) 1 else 0) {
            Tab(selected = !files, onClick = { files = false }, text = { Text("Clipboard") })
            Tab(selected = files, onClick = { files = true }, text = { Text("Files") })
        }
        Box(Modifier.weight(1f)) {
            if (files) FileTransferScreen(manager, onDevices) else ClipboardScreen(manager, onDevices)
        }
    }
}
