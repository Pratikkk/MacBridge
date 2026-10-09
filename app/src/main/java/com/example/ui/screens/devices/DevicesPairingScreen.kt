package com.example.ui.screens.devices

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.manager.BridgeManager
import com.example.model.DiscoveredPeer
import com.example.model.PairedDevice
import com.example.network.PairingCode
import com.example.network.PairingQrScanner
import com.example.network.PairingScanResult
import kotlinx.coroutines.launch
import com.example.ui.theme.*

@Composable
fun DevicesPairingScreen(
    bridgeManager: BridgeManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("Phone Identity", "Pair Mac", "Local Peers")

    val pairedDevices by bridgeManager.pairedDevices.collectAsState()
    val discoveredPeers by bridgeManager.nsdManager.discoveredPeers.collectAsState()

    var qrInput by remember { mutableStateOf("") }
    var pairingStatusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccessStatus by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "PAIRING & IDENTITIES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Secure Out-of-Band Pairing",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Scan or paste the one-time code from your Mac companion, review its identity, then pair.",
                fontSize = 12.sp,
                color = Slate400
            )
        }

        // Tabs
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Slate900,
                contentColor = CyanNeon,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyanNeon
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) CyanNeon else Slate400
                            )
                        }
                    )
                }
            }
        }

        // Tab Content
        when (selectedTab) {
            0 -> {
                // Show Phone's QR Code
                item {
                    ShowPhoneQrCard(bridgeManager = bridgeManager)
                }
            }
            1 -> {
                // Pair with Mac QR Code
                item {
                    PairWithMacQrCard(
                        qrInput = qrInput,
                        onQrInputChange = { qrInput = it },
                        onPair = {
                            bridgeManager.pairFromQrPayload(qrInput) { success, msg ->
                                isSuccessStatus = success
                                pairingStatusMessage = msg
                                if (success) qrInput = ""
                            }
                        },
                        statusMessage = pairingStatusMessage,
                        isSuccessStatus = isSuccessStatus
                    )
                }
            }
            2 -> {
                // mDNS Discovery
                item {
                    MdnsDiscoveryCard(
                        discoveredPeers = discoveredPeers,
                        onPairPeer = { peer ->
                            qrInput = ""
                            isSuccessStatus = false
                            pairingStatusMessage = "Open ${peer.name}'s Mac companion and paste its pairing code here. Discovery cannot authorize pairing."
                            selectedTab = 1
                        }
                    )
                }
            }
        }

        // Pinned Devices Section Header
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PINNED DEVICES (${pairedDevices.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "TLS + Signed Identity",
                    fontSize = 11.sp,
                    color = EmeraldNeon,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (pairedDevices.isEmpty()) {
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
                        Icon(Icons.Default.Devices, contentDescription = null, tint = Slate600, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Paired Mac Yet", color = Slate200, fontWeight = FontWeight.Bold)
                        Text("Paste the pairing code from the Mac companion above.", color = Slate400, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(pairedDevices) { device ->
                PairedDeviceItemCard(
                    device = device,
                    onConnect = { bridgeManager.connectToDevice(device) },
                    onUnpair = { bridgeManager.unpairDevice(device) },
                    onPermissionsChange = { cb, files, notifs ->
                        bridgeManager.updateDevicePermissions(device, cb, files, notifs)
                    }
                )
            }
        }
    }
}

@Composable
fun ShowPhoneQrCard(bridgeManager: BridgeManager) {
    val clipboard = LocalClipboardManager.current
    val fingerprint = bridgeManager.identityManager.getFingerprint()
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Phone Identity", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("The phone initiates connections to your Mac. Pair using the code displayed by the Mac companion.", color = Slate400, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text(fingerprint, color = CyanNeon, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fingerprint)) }) {
                Text("Copy Phone Fingerprint", color = CyanNeon)
            }
        }
    }
}

@Composable
fun PairWithMacQrCard(
    qrInput: String,
    onQrInputChange: (String) -> Unit,
    onPair: () -> Unit,
    statusMessage: String?,
    isSuccessStatus: Boolean,
    scanCode: (suspend () -> PairingScanResult)? = null
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scanner = remember(context) { PairingQrScanner(context) }
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val preview = remember(qrInput) { runCatching { PairingCode.parse(qrInput) }.getOrNull()?.device }

    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Enter Mac Pairing Code",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Open Pair a phone on your Mac, then scan its QR or paste its code. Codes expire after 5 minutes.",
                fontSize = 12.sp,
                color = Slate400
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = qrInput,
                onValueChange = { scanMessage = null; onQrInputChange(it) },
                enabled = !scanning,
                placeholder = { Text("macbridge://pair?id=...&secret=...", color = Slate700, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().testTag("qr_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanNeon,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = CyanNeon
                ),
                shape = RoundedCornerShape(10.dp),
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !scanning,
                    onClick = {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrBlank()) {
                            scanMessage = null
                            onQrInputChange(text)
                            Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Text("Paste", color = Slate200, fontSize = 12.sp)
                }
                OutlinedButton(
                    enabled = !scanning,
                    onClick = {
                        scanning = true
                        scanMessage = null
                        scope.launch {
                            try {
                                when (val result = scanCode?.invoke() ?: scanner.scan()) {
                                    is PairingScanResult.Code -> {
                                        onQrInputChange(result.value)
                                        scanMessage = "Code scanned. Review the Mac identity below, then tap Verify Identity & Pair."
                                    }
                                    PairingScanResult.Cancelled -> scanMessage = "Scan cancelled. Your existing code is unchanged."
                                    PairingScanResult.Invalid -> scanMessage = "This QR is not a valid MacBridge pairing code. Generate a new code on your Mac."
                                    PairingScanResult.Unavailable -> scanMessage = "Scanner unavailable. Update Google Play services and try again, or paste the Mac code. First use may need internet to download the scanner."
                                }
                            } finally { scanning = false }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("scan_mac_qr_button"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Scan QR", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (scanMessage != null) {
                Text(scanMessage!!, color = Slate200, fontSize = 12.sp, modifier = Modifier.testTag("scan_status"))
                Spacer(Modifier.height(10.dp))
            }
            if (preview != null) {
                Text("${preview.name} • ${preview.lastKnownIp}:${preview.port}", color = Slate200, fontSize = 12.sp)
                Text("Mac public-key fingerprint", color = Slate400, fontSize = 11.sp)
                Text(preview.fingerprint, color = CyanNeon, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pairing_identity_preview"))
                Spacer(Modifier.height(10.dp))
            }

            Button(
                onClick = onPair,
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("confirm_pair_button"),
                enabled = preview != null && !scanning
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Verify Identity & Pair", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSuccessStatus) EmeraldGlow.copy(alpha = 0.15f) else RoseNeon.copy(alpha = 0.15f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = statusMessage,
                        color = if (isSuccessStatus) EmeraldNeon else RoseNeon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun MdnsDiscoveryCard(
    discoveredPeers: List<DiscoveredPeer>,
    onPairPeer: (DiscoveredPeer) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Local mDNS Discovery", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                text = "Rule 2: Discovery is untrusted. Services below are only IP candidates; pairing requires out-of-band secret.",
                color = Slate400,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (discoveredPeers.isEmpty()) {
                Text(
                    text = "Searching for _macbridge._tcp on local network...",
                    color = Slate400,
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                discoveredPeers.forEach { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate800)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(peer.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${peer.host}:${peer.port}", color = Slate400, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = { onPairPeer(peer) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Pair", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun PairedDeviceItemCard(
    device: PairedDevice,
    onConnect: () -> Unit,
    onUnpair: () -> Unit,
    onPermissionsChange: (Boolean, Boolean, Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(EmeraldGlow.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Laptop, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(text = "${device.lastKnownIp}:${device.port}", color = Slate400, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Row {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Connect", color = CyanNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onUnpair) {
                        Icon(Icons.Default.Delete, contentDescription = "Unpair", tint = RoseNeon, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fingerprint
            Text(
                text = "Pinned: ${device.fingerprint}",
                fontSize = 10.sp,
                color = Slate400,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Rule 6: Least Privilege Toggles
            Text(
                text = "Least Privilege Permissions",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Clipboard Sync", color = Slate200, fontSize = 12.sp)
                Switch(
                    checked = device.allowClipboard,
                    onCheckedChange = { onPermissionsChange(it, device.allowFileTransfer, device.allowNotifications) },
                    colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = Slate800)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("File Transfer", color = Slate200, fontSize = 12.sp)
                Switch(
                    checked = device.allowFileTransfer,
                    onCheckedChange = { onPermissionsChange(device.allowClipboard, it, device.allowNotifications) },
                    colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = Slate800)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notification Mirror", color = Slate200, fontSize = 12.sp)
                Switch(
                    checked = device.allowNotifications,
                    onCheckedChange = { onPermissionsChange(device.allowClipboard, device.allowFileTransfer, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = Slate800)
                )
            }
        }
    }
}
