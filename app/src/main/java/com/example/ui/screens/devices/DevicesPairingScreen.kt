package com.example.ui.screens.devices

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.manager.BridgeManager
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.network.PairingCode
import com.example.network.PairingQrScanner
import com.example.network.PairingScanResult
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DevicesPairingScreen(bridgeManager: BridgeManager, modifier: Modifier = Modifier) {
    val devices by bridgeManager.pairedDevices.collectAsStateWithLifecycle()
    val state by bridgeManager.secureTransport.connectionState.collectAsStateWithLifecycle()
    var showPairing by rememberSaveable { mutableStateOf(false) }
    // Keep one-time secrets in memory only, never in saved instance state.
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            ScreenTitle("Devices", "Pair once. Choose what to share.")
        }
        if (status != null && success) item {
            Panel { Text(status!!, modifier = Modifier.testTag("pairing_success")) }
        }
        if (devices.isNotEmpty()) item { Text("Your Macs", style = MaterialTheme.typography.titleLarge) }
        items(devices, key = { it.id }) { device ->
            val connected = (state as? ConnectionState.Connected)?.let { it.device.id == device.id && !it.isSimulated } == true
            val busy = (state is ConnectionState.Connecting || state is ConnectionState.Handshaking ||
                state is ConnectionState.Reconnecting) && bridgeManager.secureTransport.isTargetDevice(device.id)
            PairedDeviceItemCard(device, { bridgeManager.connectToDevice(device) },
                { bridgeManager.unpairDevice(device) },
                { cb, files, alerts -> bridgeManager.updateDevicePermissions(device, cb, files, alerts) },
                connected, busy, onDisconnect = { bridgeManager.disconnect() })
        }
        if (devices.isEmpty() || showPairing) {
            item {
                PairWithMacQrCard(input, { input = it; status = null }, onPair = {
                    pairing = true
                    status = null
                    bridgeManager.pairFromQrPayload(input) { ok, message ->
                        pairing = false
                        success = ok
                        status = message
                        if (ok) { input = ""; showPairing = false }
                    }
                }, statusMessage = if (success) null else status, isSuccessStatus = success, pairing = pairing)
                if (devices.isNotEmpty() && !pairing) TextButton(onClick = { showPairing = false; input = ""; status = null }) { Text("Cancel adding Mac") }
            }
        } else item {
            OutlinedButton(onClick = { showPairing = true; status = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .testTag("add_mac_button")) { Text("Pair another Mac") }
        }
        item {
            Text("Keep both devices on the same local network. The Mac companion must be running to connect.", color = Slate400,
                style = MaterialTheme.typography.bodyMedium)
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
    scanCode: (suspend () -> PairingScanResult)? = null,
    pairing: Boolean = false
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
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Pair a Mac",
                style = MaterialTheme.typography.titleLarge,
                color = Slate50
            )
            Text(
                text = "Open Pair a phone on your Mac, then scan its QR or paste its code. Codes expire after 5 minutes.",
                fontSize = 14.sp,
                color = Slate400
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = qrInput,
                onValueChange = { scanMessage = null; onQrInputChange(it) },
                enabled = !scanning && !pairing,
                placeholder = { Text("Paste a MacBridge pairing code", color = Slate400, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth().testTag("qr_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanNeon,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = CyanNeon
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !scanning && !pairing,
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
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("scan_mac_qr_button")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Scan QR", fontSize = 14.sp)
                }
                OutlinedButton(
                    enabled = !scanning && !pairing,
                    onClick = {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrBlank()) {
                            scanMessage = null
                            onQrInputChange(text)
                            Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Text("Paste", color = Slate200, fontSize = 14.sp)
                }

            }

            Spacer(modifier = Modifier.height(10.dp))
            if (scanMessage != null) {
                Text(scanMessage!!, color = Slate200, fontSize = 14.sp, modifier = Modifier.testTag("scan_status"))
                Spacer(Modifier.height(10.dp))
            }
            if (preview != null) {
                Text("${preview.name} • ${preview.lastKnownIp}:${preview.port}", color = Slate200, fontSize = 14.sp)
                Text("Mac public-key fingerprint", color = Slate400, fontSize = 11.sp)
                Text(preview.fingerprint, color = CyanNeon, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pairing_identity_preview"))
                Spacer(Modifier.height(10.dp))
            }

            Button(
                onClick = onPair,
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("confirm_pair_button"),
                enabled = preview != null && !scanning && !pairing
            ) {
                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (pairing) "Pairing…" else "Verify Identity & Pair", fontWeight = FontWeight.Bold)
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun PairedDeviceItemCard(device: PairedDevice, onConnect: () -> Unit, onUnpair: () -> Unit,
    onPermissionsChange: (Boolean, Boolean, Boolean) -> Unit, isConnected: Boolean = false,
    isConnecting: Boolean = false, onDisconnect: () -> Unit = {}) {
    var confirmForget by remember(device.id) { mutableStateOf(false) }
    var identityExpanded by remember(device.id) { mutableStateOf(false) }
    Panel {
        Icon(Icons.Outlined.LaptopMac, contentDescription = null, modifier = Modifier.size(32.dp))
        Text(device.name, style = MaterialTheme.typography.titleLarge)
        Text(when { device.isBlocked -> "Blocked"; isConnected -> "Connected"; isConnecting -> "Connecting…"; else -> "Paired · Not connected" },
            color = Slate400, style = MaterialTheme.typography.bodyMedium)
        Text("${device.lastKnownIp}:${device.port}", color = Slate400, style = MaterialTheme.typography.bodyMedium)
        if (isConnected || isConnecting) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (isConnecting) "Cancel connection" else "Disconnect")
            }
        } else {
            Button(onClick = onConnect, enabled = !device.isBlocked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("device_connect_${device.id}")) { Text("Connect") }
        }
        HorizontalDivider(color = Slate800)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Clipboard sharing", style = MaterialTheme.typography.titleMedium)
                Text("Send and receive text with this Mac.", color = Slate400, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = device.allowClipboard, enabled = !device.isBlocked,
                onCheckedChange = { onPermissionsChange(it, device.allowFileTransfer, device.allowNotifications) },
                modifier = Modifier.testTag("clipboard_permission_${device.id}"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("File sharing", style = MaterialTheme.typography.titleMedium)
                Text("Send and receive documents.", color = Slate400, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = device.allowFileTransfer, enabled = !device.isBlocked,
                onCheckedChange = { onPermissionsChange(device.allowClipboard, it, device.allowNotifications) },
                modifier = Modifier.testTag("file_permission_${device.id}"))
        }
        TextButton(onClick = { identityExpanded = !identityExpanded }) { Text(if (identityExpanded) "Hide identity" else "View Mac identity") }
        if (identityExpanded) Text(device.fingerprint, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = Slate400)
        TextButton(onClick = { confirmForget = true }, modifier = Modifier.testTag("forget_mac_${device.id}")) {
            Text("Forget this Mac", color = RoseNeon)
        }
    }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text("Forget ${device.name}?") },
        text = { Text("Sharing with this Mac will stop. You will need its pairing code to connect again.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; onUnpair() }, modifier = Modifier.testTag("confirm_forget_mac")) { Text("Forget Mac", color = RoseNeon) } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } })
}
