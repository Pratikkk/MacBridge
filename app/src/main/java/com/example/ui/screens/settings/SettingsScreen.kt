package com.example.ui.screens.settings

import android.widget.Toast
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
    var security by rememberSaveable { mutableStateOf(false) }
    BackHandler(security) { security = false }
    if (security) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { security = false }, modifier = Modifier.padding(horizontal = 16.dp).testTag("back_to_settings")) { Text("‹ Settings") }
            SecurityAuditScreen(bridgeManager, Modifier.weight(1f))
        }
    } else {
        SettingsContent(onDevices, onSecurity = { security = true }, onBattery = {
            try { context.startActivity(bridgeManager.getBatteryOptimizationIntent()) }
            catch (_: Exception) { Toast.makeText(context, "Open Android Settings → Apps → MacBridge → Battery", Toast.LENGTH_LONG).show() }
        })
    }
}

@Composable
fun SettingsContent(onDevices: () -> Unit, onSecurity: () -> Unit, onBattery: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenTitle("Settings", "Your connection, your choices.")
        Panel {
            Text("Sharing permissions", style = MaterialTheme.typography.titleLarge)
            Text("Choose which Macs can exchange clipboard text and files. New pairings start with sharing off.", color = Slate400)
            OutlinedButton(onClick = onDevices, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage devices") }
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
        Text("On the roadmap", style = MaterialTheme.typography.titleLarge)
        Panel {
            Text("File sharing · Available in Share", style = MaterialTheme.typography.titleMedium)
            Text("Exchange files with your Mac, verify delivery and save received documents.", color = Slate400)
            HorizontalDivider(color = Slate800)
            Text("Notification mirroring · Coming later", style = MaterialTheme.typography.titleMedium)
            Text("View and manage phone notifications on your Mac.", color = Slate400)
        }
        Text("MacBridge · Development build", style = MaterialTheme.typography.bodyMedium, color = Slate400)
    }
}
