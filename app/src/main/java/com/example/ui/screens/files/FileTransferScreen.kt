package com.example.ui.screens.files

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.model.FileTransferItem
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import com.example.ui.theme.*

@Composable
fun FileTransferScreen(
    bridgeManager: BridgeManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transfers by bridgeManager.fileTransfers.collectAsState()
    val progressMap by bridgeManager.fileTransferManager.currentTransferProgress.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "ENCRYPTED FILE STREAMING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Chunked File Transfer",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Stream files in 64KB encrypted chunks with automatic resume, strict filename sanitization against path traversal, and SHA-256 verification.",
                fontSize = 12.sp,
                color = Slate400
            )
        }

        // Security Baseline Rule 5 Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate850),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Rule 5 active: All filenames sanitized (../ stripped) and isolated in the app sandbox. 100% SHA-256 integrity validation.",
                        fontSize = 12.sp,
                        color = Slate200
                    )
                }
            }
        }

        // Transfer Action Hub
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Slate800, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DISPATCH FILE TRANSFER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                bridgeManager.fileTransferManager.sendTestFile(1024 * 1024, "Roadmap_Benchmark_1MB.bin")
                                Toast.makeText(context, "Streaming 1MB benchmark file...", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("send_1mb_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send 1 MB", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                bridgeManager.fileTransferManager.sendTestFile(5 * 1024 * 1024, "Dataset_5MB.bin")
                                Toast.makeText(context, "Streaming 5MB benchmark file...", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("send_5mb_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send 5 MB", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            bridgeManager.macSimulator.simulateIncomingFileFromMac()
                            Toast.makeText(context, "Simulating incoming file from Mac...", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("simulate_mac_file_button"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simulate Incoming Mac File (Architecture.pdf)", color = Slate200, fontSize = 12.sp)
                    }
                }
            }
        }

        // Transfers Header
        item {
            Text(
                text = "TRANSFER QUEUE (${transfers.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
        }

        if (transfers.isEmpty()) {
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
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Slate600, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Files Transferred Yet", color = Slate200, fontWeight = FontWeight.Bold)
                        Text("Send a benchmark file or receive a document from Mac.", color = Slate400, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(transfers) { item ->
                val currentProgress = progressMap[item.transferId]
                    ?: if (item.fileSize > 0) item.transferredBytes.toFloat() / item.fileSize else 0f

                FileTransferCard(
                    item = item,
                    progress = currentProgress,
                    onCancel = { bridgeManager.fileTransferManager.cancelTransfer(item.transferId) }
                )
            }
        }
    }
}

@Composable
fun FileTransferCard(
    item: FileTransferItem,
    progress: Float,
    onCancel: () -> Unit
) {
    val isIncoming = item.direction == TransferDirection.INCOMING
    val isComplete = item.status == TransferStatus.COMPLETED
    val isFailed = item.status == TransferStatus.FAILED

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
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isComplete -> EmeraldGlow.copy(alpha = 0.2f)
                                    isFailed -> RoseNeon.copy(alpha = 0.2f)
                                    isIncoming -> CyanNeon.copy(alpha = 0.2f)
                                    else -> Slate800
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isComplete -> Icons.Default.CheckCircle
                                isFailed -> Icons.Default.Error
                                isIncoming -> Icons.Default.Download
                                else -> Icons.Default.Upload
                            },
                            contentDescription = null,
                            tint = when {
                                isComplete -> EmeraldNeon
                                isFailed -> RoseNeon
                                else -> CyanNeon
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = item.fileName,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${formatBytes(item.transferredBytes)} / ${formatBytes(item.fileSize)} • ${if (isIncoming) "Incoming" else "Outgoing"}",
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }

                if (!isComplete && !isFailed) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = RoseNeon, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Text(
                        text = if (isComplete) "VERIFIED" else "FAILED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isComplete) EmeraldNeon else RoseNeon,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isComplete) EmeraldGlow.copy(alpha = 0.2f) else RoseNeon.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (isComplete) EmeraldNeon else CyanNeon,
                trackColor = Slate800
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Checksum line
            Text(
                text = "SHA-256: ${item.sha256Checksum.take(24)}...",
                fontSize = 10.sp,
                color = Slate400,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}
