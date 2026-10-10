package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.ui.screens.bridge.BridgeHomeScreen
import com.example.ui.screens.ShareScreen
import com.example.ui.screens.devices.DevicesPairingScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.*

enum class NavigationDestination(val label: String, val icon: ImageVector, val tag: String) {
    HOME("Home", Icons.Outlined.Home, "nav_bridge"),
    CLIPBOARD("Share", Icons.Outlined.ContentCopy, "nav_clipboard"),
    DEVICES("Devices", Icons.Outlined.Devices, "nav_devices"),
    SETTINGS("Settings", Icons.Outlined.Settings, "nav_settings")
}

@Composable
fun MacBridgeApp(bridgeManager: BridgeManager) {
    var shareFiles by rememberSaveable { mutableStateOf(false) }
    val connection by bridgeManager.secureTransport.connectionState.collectAsState()
    AppShell(connection) { destination, navigate ->
        when (destination) {
            NavigationDestination.HOME -> BridgeHomeScreen(bridgeManager,
                onNavigateToPairing = { navigate(NavigationDestination.DEVICES) },
                onNavigateToClipboard = { shareFiles = false; navigate(NavigationDestination.CLIPBOARD) },
                onNavigateToFiles = { shareFiles = true; navigate(NavigationDestination.CLIPBOARD) })
            NavigationDestination.CLIPBOARD -> ShareScreen(bridgeManager,
                onDevices = { navigate(NavigationDestination.DEVICES) }, files = shareFiles, onSelect = { shareFiles = it })
            NavigationDestination.DEVICES -> DevicesPairingScreen(bridgeManager)
            NavigationDestination.SETTINGS -> SettingsScreen(bridgeManager,
                onDevices = { navigate(NavigationDestination.DEVICES) })
        }
    }
}

@Composable
fun AppShell(connection: ConnectionState,
    content: @Composable (NavigationDestination, (NavigationDestination) -> Unit) -> Unit) {
    var current by rememberSaveable { mutableStateOf(NavigationDestination.HOME) }
    val screens = rememberSaveableStateHolder()
    val largeText = LocalDensity.current.fontScale > 1.3f
    BackHandler(current != NavigationDestination.HOME) { current = NavigationDestination.HOME }
    Scaffold(containerColor = Slate950,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MacBridge", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = 12.dp))
                ConnectionStatusChip(connection)
            }
        },
        bottomBar = {
            Surface(color = Slate900, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = BorderStroke(1.dp, Slate800)) {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp,
                    modifier = Modifier.heightIn(min = if (largeText) 112.dp else 80.dp)) {
                    NavigationDestination.entries.forEach { destination ->
                        NavigationBarItem(selected = current == destination, onClick = { current = destination },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelMedium, maxLines = 2) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Slate950,
                                selectedTextColor = Slate50, indicatorColor = Slate50,
                                unselectedIconColor = Slate400, unselectedTextColor = Slate400),
                            modifier = Modifier.testTag(destination.tag))
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Keyed content disposes camera work when navigating away.
            screens.SaveableStateProvider(current.name) {
                key(current) { content(current) { current = it } }
            }
        }
    }
}

@Composable
fun ConnectionStatusChip(connectionState: ConnectionState) {
    Surface(color = Slate800, shape = RoundedCornerShape(50)) {
        Text(connectionLabel(connectionState), Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium, color = Slate200)
    }
}
