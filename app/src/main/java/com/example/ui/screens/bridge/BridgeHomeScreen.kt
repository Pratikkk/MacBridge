package com.example.ui.screens.bridge

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.ui.theme.*

@Composable
fun BridgeHomeScreen(
    bridgeManager: BridgeManager,
    onNavigateToPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by bridgeManager.secureTransport.connectionState.collectAsState()
    val pairedDevices by bridgeManager.pairedDevices.collectAsState()
    val clips by bridgeManager.clipboardHistory.collectAsState()
    val transfers by bridgeManager.fileTransfers.collectAsState()
    val notifications by bridgeManager.mirroredNotifications.collectAsState()

    val isIgnoringBattery = bridgeManager.isIgnoringBatteryOptimizations()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Connection State Hero Card
        ConnectionHeroCard(
            connectionState = connectionState,
            pairedDevicesCount = pairedDevices.size,
            onConnect = {
                val dev = pairedDevices.firstOrNull()
                if (dev != null) {
                    bridgeManager.connectToDevice(dev)
                } else {
                    onNavigateToPairing()
                }
            },
            onDisconnect = { bridgeManager.disconnect() },
            onQuickSimulate = {
                // Instantly pair and connect simulated Mac
                val simDev = bridgeManager.macSimulator.createPairedDeviceRecord()
                bridgeManager.secureTransport.setSimulatedConnected(simDev)
                Toast.makeText(context, "Connected to simulated MacBook Pro (M3 Max)", Toast.LENGTH_SHORT).show()
            }
        )

        // Battery Optimization Warning Card (Phase 3 Onboarding requirement)
        if (!isIgnoringBattery) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate850),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("battery_warning_card")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = "Battery Optimization",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Background Link Retention",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Disable battery optimization so Android won't terminate TLS heartbeats in background.",
                            color = Slate200,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = {
                            try {
                                context.startActivity(bridgeManager.getBatteryOptimizationIntent())
                            } catch (_: Exception) {
                                Toast.makeText(context, "Please disable optimization in Settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("fix_battery_button")
                    ) {
                        Text("Disable", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Live Telemetry & Quick Actions Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "BRIDGE TELEMETRY & ACTIONS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TelemetryItem(
                        icon = Icons.Default.Speed,
                        label = "Ping / RTT",
                        value = if (connectionState is ConnectionState.Connected) "${(connectionState as ConnectionState.Connected).roundTripTimeMs} ms" else "--"
                    )
                    TelemetryItem(
                        icon = Icons.Default.Security,
                        label = "Transport",
                        value = "Pinned TLS"
                    )
                    TelemetryItem(
                        icon = Icons.Default.Sync,
                        label = "Heartbeat",
                        value = if (connectionState is ConnectionState.Connected) "Active (10s)" else "Idle"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            bridgeManager.pushClipboard { ok ->
                            if (ok) Toast.makeText(context, "Clipboard pushed to Mac", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Clipboard unavailable, permission disabled, or Mac disconnected", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                        modifier = Modifier.weight(1f).testTag("sync_clipboard_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = CyanNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Clip", color = Color.White, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            bridgeManager.fileTransferManager.sendTestFile()
                            Toast.makeText(context, "Sending 1 MB benchmark file...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                        modifier = Modifier.weight(1f).testTag("send_test_file_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = EmeraldNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send 1MB", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        // Live Stat Overview Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "Synced Clips",
                count = clips.size.toString(),
                subtitle = "Bidirectional",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Transfers",
                count = transfers.size.toString(),
                subtitle = "SHA-256 Verified",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Mirrored",
                count = notifications.size.toString(),
                subtitle = "App alerts",
                modifier = Modifier.weight(1f)
            )
        }

        // Security Baseline Summary Badge
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(EmeraldGlow.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Security Audit",
                        tint = EmeraldNeon,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Security Controls In Progress",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Pinned TLS • Signed Identity • Keystore Protected",
                        color = Slate400,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionHeroCard(
    connectionState: ConnectionState,
    pairedDevicesCount: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onQuickSimulate: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isReconnecting = connectionState is ConnectionState.Reconnecting

    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isConnected) EmeraldNeon.copy(alpha = 0.5f) else Slate800,
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> EmeraldNeon
                                    isReconnecting -> Color(0xFFF59E0B)
                                    else -> Slate600
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "ENCRYPTED LINK ACTIVE"
                            is ConnectionState.Reconnecting -> "RECONNECTING (${connectionState.attempt})"
                            is ConnectionState.Connecting -> "CONNECTING..."
                            is ConnectionState.Handshaking -> "VERIFYING TLS PIN..."
                            is ConnectionState.Discovering -> "DISCOVERING..."
                            is ConnectionState.Disconnected -> "LINK DISCONNECTED"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isConnected -> EmeraldNeon
                            isReconnecting -> Color(0xFFF59E0B)
                            else -> Slate400
                        },
                        letterSpacing = 1.sp
                    )
                }

                if (isConnected) {
                    Text(
                        text = "Pinned TLS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanNeon,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Slate800)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main device representation
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isConnected) CyanNeon.copy(alpha = 0.15f) else Slate800),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Laptop,
                        contentDescription = "Mac",
                        tint = if (isConnected) CyanNeon else Slate400,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) (connectionState as ConnectionState.Connected).device.name else "MacBook Pro",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isConnected) {
                            val dev = (connectionState as ConnectionState.Connected).device
                            "Fingerprint: ${dev.fingerprint.take(16)}..."
                        } else if (pairedDevicesCount > 0) {
                            "$pairedDevicesCount paired Mac configured"
                        } else {
                            "No Mac paired yet"
                        },
                        fontSize = 12.sp,
                        color = Slate400,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Primary control button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isConnected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("disconnect_button"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RoseNeon.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect", tint = RoseNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect", color = RoseNeon, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("connect_button")
                    ) {
                        Icon(Icons.Default.Link, contentDescription = "Connect", tint = Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (pairedDevicesCount > 0) "Connect to Mac" else "Pair with Mac (QR)",
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onQuickSimulate,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                        modifier = Modifier.testTag("simulate_mac_button")
                    ) {
                        Text("Demo Peer", color = Slate200, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = CyanNeon, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(text = label, fontSize = 11.sp, color = Slate400)
    }
}

@Composable
fun StatCard(
    title: String,
    count: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, Slate800, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = count, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(text = subtitle, fontSize = 10.sp, color = Slate400)
        }
    }
}
