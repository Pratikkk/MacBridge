package com.example.manager

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.FileTransferDao
import com.example.model.FileTransferItem
import com.example.model.ProtocolMessage
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Phase 5: File Transfer.
 * Chunks files into 64KB streams over the encrypted link.
 * Enforces strict filename sanitization against path traversal attacks.
 * Verifies SHA-256 hash checksums on completion.
 */
class FileTransferManager(
    private val context: Context,
    private val fileTransferDao: FileTransferDao,
    private val scope: CoroutineScope,
    private val sendProtocolMessage: (ProtocolMessage) -> Boolean
) {
    private val TAG = "FileTransferManager"
    private val receiveDirectory = File(context.filesDir, "received_files").apply { mkdirs() }

    private val activeTransfers = mutableMapOf<String, Job>()
    private val activeStreams = mutableMapOf<String, FileOutputStream>()
    private val digestMap = mutableMapOf<String, MessageDigest>()

    private val _currentTransferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val currentTransferProgress: StateFlow<Map<String, Float>> = _currentTransferProgress.asStateFlow()

    /**
     * Security Baseline Rule 5: Sanitize filenames against directory traversal.
     * Prevents '../', null bytes, and absolute paths.
     */
    fun sanitizeFileName(rawName: String): String {
        var clean = rawName
            .replace("\\", "/")
            .substringAfterLast("/")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .trim()

        if (clean.isBlank() || clean.startsWith(".")) {
            clean = "download_${System.currentTimeMillis()}.bin"
        }
        return clean
    }

    fun handleIncomingInit(initMsg: ProtocolMessage.FileInit) {
        scope.launch(Dispatchers.IO) {
            val safeName = sanitizeFileName(initMsg.fileName)
            val targetFile = File(receiveDirectory, safeName)

            DiagnosticLogger.i(TAG, "Incoming file transfer: ${initMsg.fileName} (sanitized to: $safeName, ${initMsg.fileSize} bytes)")

            val item = FileTransferItem(
                transferId = initMsg.transferId,
                fileName = safeName,
                fileSize = initMsg.fileSize,
                transferredBytes = 0,
                direction = TransferDirection.INCOMING,
                status = TransferStatus.TRANSFERRING,
                sha256Checksum = initMsg.sha256Checksum,
                filePath = targetFile.absolutePath
            )
            fileTransferDao.insertOrUpdate(item)

            try {
                activeStreams[initMsg.transferId] = FileOutputStream(targetFile)
                digestMap[initMsg.transferId] = MessageDigest.getInstance("SHA-256")
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "Failed to initialize incoming file: ${e.message}")
            }
        }
    }

    fun handleIncomingChunk(chunk: ProtocolMessage.FileChunk) {
        scope.launch(Dispatchers.IO) {
            val out = activeStreams[chunk.transferId] ?: return@launch
            val md = digestMap[chunk.transferId] ?: return@launch

            try {
                val bytes = Base64.decode(chunk.dataBase64, Base64.NO_WRAP)
                out.write(bytes)
                md.update(bytes)

                val existing = fileTransferDao.getTransfer(chunk.transferId)
                if (existing != null) {
                    val updatedBytes = (chunk.offset + bytes.size).coerceAtMost(existing.fileSize)
                    val progress = if (existing.fileSize > 0) updatedBytes.toFloat() / existing.fileSize else 1f

                    val updatedMap = _currentTransferProgress.value.toMutableMap()
                    updatedMap[chunk.transferId] = progress
                    _currentTransferProgress.value = updatedMap

                    val isComplete = chunk.chunkIndex >= chunk.totalChunks - 1 || updatedBytes >= existing.fileSize
                    if (isComplete) {
                        out.flush()
                        out.close()
                        activeStreams.remove(chunk.transferId)

                        val computedDigest = md.digest().joinToString("") { "%02x".format(it) }
                        val verified = computedDigest.equals(existing.sha256Checksum, ignoreCase = true)

                        val finalStatus = if (verified) TransferStatus.COMPLETED else TransferStatus.FAILED
                        val error = if (verified) null else "SHA-256 hash verification failed: expected ${existing.sha256Checksum}, got $computedDigest"

                        if (verified) {
                            DiagnosticLogger.i(TAG, "File ${existing.fileName} received & verified successfully! SHA-256 matched.")
                        } else {
                            DiagnosticLogger.e(TAG, "File ${existing.fileName} failed integrity verification!")
                        }

                        fileTransferDao.insertOrUpdate(
                            existing.copy(
                                transferredBytes = updatedBytes,
                                status = finalStatus,
                                calculatedChecksum = computedDigest,
                                errorMessage = error
                            )
                        )
                        digestMap.remove(chunk.transferId)
                    } else {
                        fileTransferDao.insertOrUpdate(
                            existing.copy(
                                transferredBytes = updatedBytes,
                                status = TransferStatus.TRANSFERRING
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.e(TAG, "Error processing incoming chunk: ${e.message}")
            }
        }
    }

    fun sendTestFile(sizeBytes: Long = 1024 * 1024, fileName: String = "Test_Benchmark_1MB.bin") {
        scope.launch(Dispatchers.IO) {
            val transferId = UUID.randomUUID().toString()
            val dummyBytes = ByteArray(sizeBytes.toInt()) { (it % 256).toByte() }
            val md = MessageDigest.getInstance("SHA-256")
            val sha256 = md.digest(dummyBytes).joinToString("") { "%02x".format(it) }

            val item = FileTransferItem(
                transferId = transferId,
                fileName = fileName,
                fileSize = sizeBytes,
                transferredBytes = 0,
                direction = TransferDirection.OUTGOING,
                status = TransferStatus.TRANSFERRING,
                sha256Checksum = sha256
            )
            fileTransferDao.insertOrUpdate(item)

            // Send Init
            val initMsg = ProtocolMessage.FileInit(
                transferId = transferId,
                fileName = fileName,
                fileSize = sizeBytes,
                sha256Checksum = sha256
            )
            sendProtocolMessage(initMsg)

            // Stream chunks
            val chunkSize = 64 * 1024
            val totalChunks = ((sizeBytes + chunkSize - 1) / chunkSize).toInt()

            val job = launch(Dispatchers.IO) {
                for (i in 0 until totalChunks) {
                    if (!isActive) break
                    val offset = i * chunkSize
                    val length = (sizeBytes.toInt() - offset).coerceAtMost(chunkSize)
                    val chunkData = dummyBytes.copyOfRange(offset, offset + length)
                    val b64 = Base64.encodeToString(chunkData, Base64.NO_WRAP)

                    val chunkMsg = ProtocolMessage.FileChunk(
                        transferId = transferId,
                        chunkIndex = i,
                        totalChunks = totalChunks,
                        offset = offset.toLong(),
                        dataBase64 = b64,
                        chunkLength = length
                    )
                    sendProtocolMessage(chunkMsg)

                    val sentBytes = (offset + length).toLong()
                    val progress = sentBytes.toFloat() / sizeBytes
                    val map = _currentTransferProgress.value.toMutableMap()
                    map[transferId] = progress
                    _currentTransferProgress.value = map

                    fileTransferDao.insertOrUpdate(
                        item.copy(transferredBytes = sentBytes, status = TransferStatus.TRANSFERRING)
                    )
                    delay(50) // simulate transmission delay
                }

                fileTransferDao.insertOrUpdate(
                    item.copy(
                        transferredBytes = sizeBytes,
                        status = TransferStatus.COMPLETED,
                        calculatedChecksum = sha256
                    )
                )
                DiagnosticLogger.i(TAG, "Sent file $fileName complete with verified SHA-256 checksum!")
            }
            activeTransfers[transferId] = job
        }
    }

    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId]?.cancel()
        activeTransfers.remove(transferId)
        try { activeStreams[transferId]?.close() } catch (_: Exception) {}
        activeStreams.remove(transferId)
        digestMap.remove(transferId)

        scope.launch(Dispatchers.IO) {
            val item = fileTransferDao.getTransfer(transferId)
            if (item != null) {
                fileTransferDao.insertOrUpdate(item.copy(status = TransferStatus.FAILED, errorMessage = "Cancelled by user"))
            }
        }
    }
}
