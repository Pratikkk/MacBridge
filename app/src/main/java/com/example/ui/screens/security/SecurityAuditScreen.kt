package com.example.ui.screens.security

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.crypto.SecurityBaselineAuditor
import com.example.crypto.SecurityRule
import com.example.manager.BridgeManager
import com.example.manager.DiagnosticLogger
import com.example.model.LogLevel
import com.example.model.SystemLogEntry
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.EmeraldNeon
import com.example.ui.theme.RoseNeon
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate900
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SecurityAuditScreen(
    bridgeManager: BridgeManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rules = remember { SecurityBaselineAuditor.getAuditRules() }

    var selectedSection by remember { mutableIntStateOf(0) }
    val logs = if (selectedSection == 1) {
        val visibleLogs by DiagnosticLogger.logsFlow.collectAsStateWithLifecycle()
        visibleLogs
    } else emptyList()
    val sections = listOf("Control Status", "Diagnostic Logs", "Keystore")

    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, selectedLogLevel, searchQuery) {
        logs.filter { log ->
            (selectedLogLevel == null || log.level == selectedLogLevel) &&
                (searchQuery.isBlank() || log.message.contains(searchQuery, ignoreCase = true) || log.tag.contains(searchQuery, ignoreCase = true))
        }
    }

    val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "SECURITY & DIAGNOSTICS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Security Baseline & Logs",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Implementation status and local diagnostic logs. An independent security audit is still pending.",
                fontSize = 12.sp,
                color = Slate400
            )
        }

        item {
            TabRow(
                selectedTabIndex = selectedSection,
                containerColor = Slate900,
                contentColor = CyanNeon,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedSection]),
                        color = CyanNeon
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                sections.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSection == index,
                        onClick = { selectedSection = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedSection == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedSection == index) CyanNeon else Slate400
                            )
                        }
                    )
                }
            }
        }

        when (selectedSection) {
            0 -> {
                // 8 Security Baseline Rules
                items(rules) { rule ->
                    SecurityRuleCard(rule)
                }
            }
            1 -> {
                // Diagnostic Logs Controls
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val exported = DiagnosticLogger.exportFormatted()
                                    clipboard.setText(AnnotatedString(exported))
                                    Toast.makeText(context, "Exported logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("export_logs_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export / Copy", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { DiagnosticLogger.clear() },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                                modifier = Modifier.testTag("clear_logs_button")
                            ) {
                                Text("Clear", color = Slate400, fontSize = 12.sp)
                            }
                        }

                        // Search Field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter logs...", color = Slate700, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanNeon,
                                unfocusedBorderColor = Slate700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        // Level Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(null to "ALL", LogLevel.INFO to "INFO", LogLevel.WARN to "WARN", LogLevel.ERROR to "ERROR").forEach { (lvl, label) ->
                                FilterChip(
                                    selected = selectedLogLevel == lvl,
                                    onClick = { selectedLogLevel = lvl },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanNeon,
                                        selectedLabelColor = Color(0xFF0F172A),
                                        containerColor = Slate900,
                                        labelColor = Slate400
                                    )
                                )
                            }
                        }
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item {
                        Text(
                            text = "No log records matching filter",
                            color = Slate400,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                } else {
                    items(filteredLogs) { log ->
                        LogEntryItem(log, sdf.format(Date(log.timestamp)))
                    }
                }
            }
            2 -> {
                // Keystore & Identity Details
                item {
                    KeystoreDetailsCard(bridgeManager)
                }
            }
        }
    }
}

@Composable
fun SecurityRuleCard(rule: SecurityRule) {
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
                            .background(EmeraldGlow.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rule.ruleNumber.toString(),
                            color = EmeraldNeon,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = rule.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = if (rule.isCompliant) "IMPLEMENTED" else "PENDING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (rule.isCompliant) EmeraldNeon else RoseNeon,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background((if (rule.isCompliant) EmeraldGlow else RoseNeon).copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = rule.description,
                color = Slate200,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Slate850)
                    .padding(8.dp)
            ) {
                Text(
                    text = rule.technicalAudit,
                    color = CyanNeon,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun LogEntryItem(log: SystemLogEntry, timeStr: String) {
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Slate400
        LogLevel.INFO -> CyanNeon
        LogLevel.WARN -> Color(0xFFF59E0B)
        LogLevel.ERROR -> RoseNeon
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Slate900)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            color = Slate400,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = log.level.name.take(1),
            color = levelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.tag,
                color = Slate400,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = log.message,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun KeystoreDetailsCard(bridgeManager: BridgeManager) {
    val fingerprint = remember(bridgeManager.identityManager) { bridgeManager.identityManager.getFingerprint() }
    val pubKeyB64 = remember(bridgeManager.identityManager) { bridgeManager.identityManager.getPublicKeyBase64() }

    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Android Keystore Identity", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(12.dp))

            KeystoreDetailRow("Keystore Provider", "AndroidKeyStore")
            KeystoreDetailRow("Key Alias", "macbridge_identity_key")
            KeystoreDetailRow("Algorithm", "EC (Elliptic Curve SECP256R1)")
            KeystoreDetailRow("Digest", "SHA-256")
            KeystoreDetailRow("Cloud Backup Status", "EXCLUDED (autoBackup disabled)")
            KeystoreDetailRow("Device ID", bridgeManager.identityManager.deviceId)

            Spacer(modifier = Modifier.height(10.dp))

            Text("Device Public Fingerprint:", color = Slate400, fontSize = 11.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Slate850)
                    .padding(8.dp)
            ) {
                Text(fingerprint, color = CyanNeon, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Public Key (Base64 X.509 SubjectPublicKeyInfo):", color = Slate400, fontSize = 11.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Slate850)
                    .padding(8.dp)
            ) {
                Text(pubKeyB64.take(90) + "...", color = Slate400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun KeystoreDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Slate400, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
