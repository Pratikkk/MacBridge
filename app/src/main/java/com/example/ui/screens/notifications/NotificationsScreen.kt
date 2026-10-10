package com.example.ui.screens.notifications

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.manager.BridgeManager
import com.example.service.MacBridgeNotificationListener
import com.example.ui.components.*
import com.example.ui.theme.Slate400

@Composable
fun NotificationsScreen(bridgeManager: BridgeManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(MacBridgeNotificationListener.isPermissionGranted(context)) }
    var apps by remember { mutableStateOf(bridgeManager.notificationApps()) }
    var previews by remember { mutableStateOf(bridgeManager.notificationPreviewsEnabled()) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            access = MacBridgeNotificationListener.isPermissionGranted(context)
            apps = bridgeManager.notificationApps()
            if (!access) bridgeManager.clearNotificationMirrors()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            ScreenTitle("Notifications", "Choose what appears on your Mac.")
            Text("Enable notification sharing for your Mac in Devices, then allow notifications in MacBridge’s Mac settings. Only new updates from selected apps are sent while connected.", color = Slate400)
        }
        item { Panel {
            Text(if (access) "Android access enabled" else "Android access required", style = MaterialTheme.typography.titleMedium)
            Text("Android grants access to notifications. MacBridge shares only the apps you select below. Secret, ongoing and group-summary alerts are excluded.", color = Slate400)
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: Exception) { Toast.makeText(context, "Open Android Settings → Notification access", Toast.LENGTH_LONG).show() }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open notification access") }
        } }
        item { NotificationPreviewControl(previews) {
            previews = it; bridgeManager.setNotificationPreviews(it)
        } }
        item {
            Text("Selected apps", style = MaterialTheme.typography.titleLarge)
            Text("Apps appear here after posting a notification while Android access is enabled. Apps start off. Existing alerts are never replayed.", color = Slate400)
            TextButton(onClick = { apps = bridgeManager.notificationApps(); revision++ }) { Text("Refresh apps") }
        }
        if (apps.isEmpty()) item { Text("No apps seen yet. Enable Android access, then wait for a notification and refresh.", color = Slate400) }
        items(apps, key = { it.first }) { (pkg, name) ->
            var enabled by remember(pkg, revision) { mutableStateOf(bridgeManager.isAppMirroringEnabled(pkg)) }
            Panel { Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleMedium); Text(pkg, style = MaterialTheme.typography.bodySmall, color = Slate400) }
                Switch(checked = enabled, enabled = access, onCheckedChange = { enabled = it; bridgeManager.setAppMirroringEnabled(pkg, it) })
            } }
        }
        item { Text("Previews may contain private messages or codes. Mac notification visibility is also controlled by macOS. Removing a mirrored alert on Mac does not dismiss it on your phone; reply and dismiss actions are planned.", color = Slate400) }
    }
}

@Composable
internal fun NotificationPreviewControl(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Panel { Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Show message previews", style = MaterialTheme.typography.titleMedium)
            Text("Off sends only the app name and a generic alert. Turning this off clears current Mac alerts.", color = Slate400)
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    } }
}
