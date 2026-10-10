package com.example.ui.screens.notifications

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.manager.BridgeManager
import com.example.model.MirroredNotification
import com.example.model.ProtocolMessage
import com.example.service.MacBridgeNotificationListener
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun NotificationsScreen(
    bridgeManager: BridgeManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notifications by bridgeManager.mirroredNotifications.collectAsStateWithLifecycle()
    val isPermissionGranted = MacBridgeNotificationListener.isPermissionGranted(context)
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val commonApps = listOf(
        Pair("Messages", "com.google.android.apps.messaging"),
        Pair("WhatsApp", "com.whatsapp"),
        Pair("Slack", "com.Slack"),
        Pair("Telegram", "org.telegram.messenger"),
        Pair("Gmail", "com.google.android.gm"),
        Pair("Discord", "com.discord")
    )

    val appToggleStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            commonApps.forEach { (_, pkg) ->
                put(pkg, bridgeManager.isAppMirroringEnabled(pkg))
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "NOTIFICATION MIRRORING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Native macOS Mirror",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Android notification listener forwards notifications to Mac as native banners with bidirectional dismiss synchronization.",
                fontSize = 12.sp,
                color = Slate400
            )
        }

        // Permission Banner
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isPermissionGranted) Slate900 else Slate850),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isPermissionGranted) EmeraldNeon.copy(alpha = 0.5f) else Color(0xFFF59E0B),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = if (isPermissionGranted) EmeraldNeon else Color(0xFFF59E0B),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPermissionGranted) "Notification Access Enabled" else "Permission Required",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isPermissionGranted) "Listening for incoming status bar alerts." else "Grant notification listener access in Android System Settings.",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }
                    if (!isPermissionGranted) {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Open Settings > Notification Access", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("grant_notification_permission_button")
                        ) {
                            Text("Grant", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Test Simulation Actions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "TEST BENCH ACTIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val dummyId = UUID.randomUUID().toString()
                                val msg = ProtocolMessage.NotificationMirror(
                                    notificationId = dummyId,
                                    packageName = "com.Slack",
                                    appName = "Slack",
                                    title = "Alex Chen (#general)",
                                    text = "PR #42 for TLS 1.3 mutual auth has been approved!",
                                    hasReplyAction = true
                                )
                                bridgeManager.handleOutgoingNotification(msg)
                                Toast.makeText(context, "Dispatched Slack notification to Mac!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("trigger_slack_notification_button")
                        ) {
                            Text("Simulate Slack Alert", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val last = notifications.firstOrNull { !it.isDismissed }
                                if (last != null) {
                                    bridgeManager.macSimulator.simulateNotificationDismissFromMac(last.notificationId)
                                    Toast.makeText(context, "Mac dismissed: ${last.title}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No active notification to dismiss", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("simulate_mac_dismiss_button")
                        ) {
                            Text("Mac Dismiss Sync", color = Slate200, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Per-App Toggles (Least Privilege)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "APP-SPECIFIC PERMISSION FILTERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    commonApps.forEach { (name, pkg) ->
                        val isChecked = appToggleStates[pkg] ?: true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = name, color = Slate200, fontSize = 13.sp)
                            Switch(
                                checked = isChecked,
                                onCheckedChange = { newState ->
                                    appToggleStates[pkg] = newState
                                    bridgeManager.setAppMirroringEnabled(pkg, newState)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = Slate800)
                            )
                        }
                    }
                }
            }
        }

        // Live Feed Header
        item {
            Text(
                text = "MIRRORED NOTIFICATIONS FEED (${notifications.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
        }

        if (notifications.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = Slate600, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Mirrored Notifications Yet", color = Slate200, fontWeight = FontWeight.Bold)
                        Text("Tap 'Simulate Slack Alert' above or wait for system notifications.", color = Slate400, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(notifications) { item ->
                NotificationMirrorCard(
                    item = item,
                    timeStr = sdf.format(Date(item.timestamp)),
                    onDismiss = {
                        bridgeManager.handleNotificationDismissedLocally(item.notificationId)
                    }
                )
            }
        }
    }
}

@Composable
fun NotificationMirrorCard(
    item: MirroredNotification,
    timeStr: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(CyanNeon.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.appName.take(1).uppercase(),
                            color = CyanNeon,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.appName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = timeStr, fontSize = 11.sp, color = Slate400)
                    Spacer(modifier = Modifier.width(6.dp))
                    if (item.isDismissed) {
                        Text(
                            text = "DISMISSED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate400,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Slate800)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    } else {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Slate400, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 14.sp
            )

            if (item.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.text,
                    color = Slate200,
                    fontSize = 13.sp
                )
            }
        }
    }
}
