package com.example.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.data.FileTransferDao
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private class TransferFailure(val explanation: String) : Exception()
private fun insist(value: Boolean, message: () -> String) { if (!value) throw TransferFailure(message()) }

const val FILE_CHUNK_SIZE = 65536
const val MAX_FILE_BYTES = 100L * 1024 * 1024

data class FileTransferTarget(val device: PairedDevice, val session: Long)

/** Stream a bounded private snapshot of a selected document, waiting for each Mac acknowledgement. */
class FileTransferManager(
    private val context: Context,
    private val fileTransferDao: FileTransferDao,
    private val scope: CoroutineScope,
    private val target: suspend () -> FileTransferTarget?,
    private val send: suspend (ProtocolMessage, FileTransferTarget) -> Boolean,
    private val ackTimeoutMs: Long = 15000
) {
    private val spool = File(context.cacheDir, "outgoing_transfers")
    private val initialized = scope.async(Dispatchers.IO) {
        spool.mkdirs()
        spool.listFiles()?.filter { it.name.startsWith("transfer-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        fileTransferDao.failInterruptedOutgoing()
    }
    private val occupied = AtomicBoolean(false)
    private val acknowledgements = ConcurrentHashMap<String, Channel<ProtocolMessage.FileAck>>()
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeId: String? = null
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()

    fun handleAck(message: ProtocolMessage.FileAck, deviceId: String) {
        val value = expectedPeer
        if (value == deviceId) acknowledgements[message.transferId]?.trySend(message)
    }
    @Volatile private var expectedPeer: String? = null

    fun sendFile(uri: Uri): Boolean {
        if (!occupied.compareAndSet(false, true)) return false
        busyState.value = true
        val id = "file-v1-" + UUID.randomUUID().toString()
        activeId = id
        val ack = Channel<ProtocolMessage.FileAck>(4)
        acknowledgements[id] = ack
        activeJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var item = FileTransferItem(id, "Selected document", 0, direction = TransferDirection.OUTGOING,
                status = TransferStatus.PENDING, sha256Checksum = "")
            var destination: FileTransferTarget? = null
            var snapshot: File? = null
            var complete = false
            var offered = false
            try {
                initialized.await()
                fileTransferDao.insertOrUpdate(item)
                destination = target() ?: throw TransferFailure("Connect to your Mac and enable File sharing in Devices.")
                expectedPeer = destination.device.id
                val resolver = context.contentResolver
                insist(uri.scheme == "content") { "Choose a document with the system file picker." }
                var name = "document"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index)
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (size >= 0 && !cursor.isNull(size)) insist(cursor.getLong(size) <= MAX_FILE_BYTES) { "Files must be 100 MB or smaller." }
                    }
                }
                name = name.replace('\\', '/').substringAfterLast('/').filter { !it.isISOControl() }.take(100).ifBlank { "document" }
                snapshot = File.createTempFile("transfer-", ".part", spool)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                resolver.openInputStream(uri)?.use { source ->
                    snapshot.outputStream().use { out ->
                        val buffer = ByteArray(FILE_CHUNK_SIZE)
                        while (true) {
                            ensureActive()
                            val length = source.read(buffer)
                            if (length < 0) break
                            if (length == 0) continue
                            count += length
                            insist(count <= MAX_FILE_BYTES) { "Files must be 100 MB or smaller." }
                            digest.update(buffer, 0, length)
                            out.write(buffer, 0, length)
                        }
                    }
                } ?: throw TransferFailure("Cannot read this document. Choose it again.")
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                item = item.copy(fileName = name, fileSize = count, sha256Checksum = checksum, status = TransferStatus.TRANSFERRING)
                fileTransferDao.insertOrUpdate(item)
                insist(send(ProtocolMessage.FileInit(id, name, count, checksum), destination)) { "Connection or File sharing permission changed." }
                offered = true
                suspend fun nextAck(expectedBytes: Long, status: String) {
                    val response = withTimeout(ackTimeoutMs) { ack.receive() }
                    insist(response.status == status && response.receivedBytes == expectedBytes) {
                        if (response.status == "REJECTED") "Mac file receiving is off. Enable it in the Mac companion." else "The Mac rejected or could not verify this transfer. Try again."
                    }
                    if (status == "COMPLETED") insist(response.sha256Checksum == checksum) { "Mac checksum verification failed." }
                }
                nextAck(0, if (count == 0L) "COMPLETED" else "READY")
                snapshot.inputStream().use { source ->
                    var offset = 0L
                    var index = 0
                    val total = ((count + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE).toInt()
                    while (offset < count) {
                        ensureActive()
                        val bytes = ByteArray(minOf(FILE_CHUNK_SIZE.toLong(), count - offset).toInt())
                        var read = 0
                        while (read < bytes.size) {
                            val n = source.read(bytes, read, bytes.size - read)
                            insist(n > 0) { "Document snapshot became unreadable." }
                            read += n
                        }
                        insist(send(ProtocolMessage.FileChunk(id, index++, total, offset,
                            Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size), destination)) { "Connection or File sharing permission changed." }
                        offset += bytes.size
                        nextAck(offset, if (offset == count) "COMPLETED" else "IN_PROGRESS")
                        item = item.copy(transferredBytes = offset)
                        fileTransferDao.insertOrUpdate(item)
                    }
                }
                item = item.copy(status = TransferStatus.COMPLETED, calculatedChecksum = checksum)
                fileTransferDao.insertOrUpdate(item)
                complete = true
            } catch (error: Exception) {
                val message = when (error) {
                    is TimeoutCancellationException -> "Mac did not confirm the transfer. Update the companion, enable file receiving, and try again."
                    is CancellationException -> "Cancelled. Any unverified partial file is removed."
                    is SecurityException -> "Document access was revoked. Choose the file again."
                    is TransferFailure -> error.explanation
                    else -> "Could not read or send this file. Check the connection, free space and document access."
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    fileTransferDao.insertOrUpdate(item.copy(status = TransferStatus.FAILED, errorMessage = message))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!complete && offered && destination != null) runCatching { send(ProtocolMessage.FileCancel(id), destination) }
                    snapshot?.delete()
                    acknowledgements.remove(id)?.close()
                    expectedPeer = null
                    activeId = null
                    busyState.value = false
                    occupied.set(false)
                }
            }
        }
        activeJob!!.start()
        return true
    }

    fun cancelTransfer(transferId: String) { if (activeId == transferId) activeJob?.cancel() }
}
