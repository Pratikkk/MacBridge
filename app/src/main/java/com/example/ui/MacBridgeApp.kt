package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.ui.screens.bridge.BridgeHomeScreen
import com.example.ui.screens.clipboard.ClipboardScreen
import com.example.ui.screens.devices.DevicesPairingScreen
import com.example.ui.screens.files.FileTransferScreen
import com.example.ui.screens.notifications.NotificationsScreen
import com.example.ui.screens.security.SecurityAuditScreen
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldNeon
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

enum class NavigationDestination(val label: String, val icon: ImageVector, val tag: String) {
    BRIDGE("Bridge", Icons.Default.Link, "nav_bridge"),
    DEVICES("Devices", Icons.Default.QrCode, "nav_devices"),
    CLIPBOARD("Clipboard", Icons.Default.ContentCopy, "nav_clipboard"),
    FILES("Files", Icons.Default.Folder, "nav_files"),
    NOTIFICATIONS("Alerts", Icons.Default.Notifications, "nav_alerts"),
    SECURITY("Security", Icons.Default.Security, "nav_security")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacBridgeApp(bridgeManager: BridgeManager) {
    var currentDestination by remember { mutableStateOf(NavigationDestination.BRIDGE) }
    val connectionState by bridgeManager.secureTransport.connectionState.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanNeon.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Laptop,
                                contentDescription = null,
                                tint = CyanNeon,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "MacBridge",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    // Live connection status chip in top bar
                    ConnectionStatusChip(connectionState)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Slate900,
                    titleContentColor = Color.White
                ),
                modifier = Modifier
                    .statusBarsPadding()
                    .border(1.dp, Slate800, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Slate900,
                contentColor = Slate400,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .border(1.dp, Slate800, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationDestination.values().forEach { destination ->
                    val isSelected = currentDestination == destination
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentDestination = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanNeon,
                            selectedTextColor = CyanNeon,
                            indicatorColor = Slate800,
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag(destination.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(innerPadding),
            label = "ScreenTransition"
        ) { destination ->
            when (destination) {
                NavigationDestination.BRIDGE -> BridgeHomeScreen(
                    bridgeManager = bridgeManager,
                    onNavigateToPairing = { currentDestination = NavigationDestination.DEVICES }
                )
                NavigationDestination.DEVICES -> DevicesPairingScreen(
                    bridgeManager = bridgeManager
                )
                NavigationDestination.CLIPBOARD -> ClipboardScreen(
                    bridgeManager = bridgeManager
                )
                NavigationDestination.FILES -> FileTransferScreen(
                    bridgeManager = bridgeManager
                )
                NavigationDestination.NOTIFICATIONS -> NotificationsScreen(
                    bridgeManager = bridgeManager
                )
                NavigationDestination.SECURITY -> SecurityAuditScreen(
                    bridgeManager = bridgeManager
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusChip(connectionState: ConnectionState) {
    val isConnected = connectionState is ConnectionState.Connected
    val isReconnecting = connectionState is ConnectionState.Reconnecting

    val chipColor = when {
        isConnected -> EmeraldNeon
        isReconnecting -> Color(0xFFF59E0B)
        else -> Slate400
    }

    val label = when (connectionState) {
        is ConnectionState.Connected -> "M3 Max Pinned"
        is ConnectionState.Reconnecting -> "Reconnecting"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Handshaking -> "TLS Handshake"
        is ConnectionState.Discovering -> "Searching"
        is ConnectionState.Disconnected -> "Idle"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Slate800)
            .border(1.dp, chipColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(chipColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = chipColor
        )
    }
}
