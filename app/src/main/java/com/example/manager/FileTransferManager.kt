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
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private class TransferInterrupted : Exception()
private class TransferFailure(val explanation: String) : Exception()
private fun insist(value: Boolean, message: () -> String) { if (!value) throw TransferFailure(message()) }

const val FILE_CHUNK_SIZE = 65536
const val TRANSFER_RESUME_WINDOW_MS = 600000L
const val MAX_FILE_BYTES = 100L * 1024 * 1024

data class FileTransferTarget(val device: PairedDevice, val session: Long)

/** Stream a bounded private snapshot of a selected document, waiting for each Mac acknowledgement. */
class FileTransferManager(
    private val context: Context,
    private val fileTransferDao: FileTransferDao,
    private val scope: CoroutineScope,
    private val target: suspend () -> FileTransferTarget?,
    private val send: suspend (ProtocolMessage, FileTransferTarget) -> Boolean,
    private val ackTimeoutMs: Long = 15000,
    private val retain: suspend (FileTransferTarget) -> Boolean = { true },
    private val resumeWindowMs: Long = TRANSFER_RESUME_WINDOW_MS
) {
    private val spool = File(context.cacheDir, "outgoing_transfers")
    private val initialized = scope.async(Dispatchers.IO) {
        spool.mkdirs()
        spool.listFiles()?.filter { it.name.startsWith("transfer-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        fileTransferDao.failInterruptedOutgoing()
        fileTransferDao.getAllTransfers().first().filter { it.direction == TransferDirection.OUTGOING && it.status == TransferStatus.PAUSED }.forEach {
            fileTransferDao.insertOrUpdate(it.copy(status = TransferStatus.FAILED, errorMessage = "App restarted. Choose the file again."))
        }
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

    private data class Paused(val item: FileTransferItem, val destination: FileTransferTarget, val snapshot: File, val since: Long = System.nanoTime())
    @Volatile private var paused: Paused? = null

    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    delay(250)
                    if (occupied.get()) continue
                    val value = paused ?: continue
                    if ((System.nanoTime() - value.since) / 1_000_000 > resumeWindowMs || !retain(value.destination)) {
                        if (paused === value && !occupied.get()) {
                            paused = null
                            value.snapshot.delete()
                            fileTransferDao.insertOrUpdate(value.item.copy(status = TransferStatus.FAILED, errorMessage = "Paused transfer expired or File sharing permission changed."))
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val value = paused
                    paused = null
                    if (value != null) {
                        value.snapshot.delete()
                        fileTransferDao.insertOrUpdate(value.item.copy(status = TransferStatus.FAILED, errorMessage = "App stopped. Choose the file again."))
                    }
                }
            }
        }
    }

    fun sendFile(uri: Uri): Boolean = start(uri, null)

    suspend fun resumeTransfer(id: String): Boolean {
        val value = paused?.takeIf { it.item.transferId == id } ?: return false
        if ((System.nanoTime() - value.since) / 1_000_000 > resumeWindowMs) return false
        val current = target() ?: return false
        if (current.device.id != value.destination.device.id || current.device.fingerprint != value.destination.device.fingerprint || !retain(value.destination)) return false
        return start(null, value)
    }

    private fun start(uri: Uri?, resumed: Paused?): Boolean {
        if (paused != null && resumed == null) return false
        if (!occupied.compareAndSet(false, true)) return false
        busyState.value = true
        val id = resumed?.item?.transferId ?: ("file-v1-" + UUID.randomUUID().toString())
        activeId = id
        val ack = Channel<ProtocolMessage.FileAck>(4)
        acknowledgements[id] = ack
        activeJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var item = resumed?.item ?: FileTransferItem(id, "Selected document", 0, direction = TransferDirection.OUTGOING,
                status = TransferStatus.PENDING, sha256Checksum = "")
            var destination: FileTransferTarget? = null
            var snapshot: File? = resumed?.snapshot
            var keep = false
            var complete = false
            var offered = false
            try {
                initialized.await()
                fileTransferDao.insertOrUpdate(item)
                destination = target() ?: throw TransferFailure("Connect to your Mac and enable File sharing in Devices.")
                expectedPeer = destination.device.id
                if (resumed != null) {
                    insist(destination.device.id == resumed.destination.device.id && destination.device.fingerprint == resumed.destination.device.fingerprint) { "Reconnect the original paired Mac to resume." }
                    insist(snapshot!!.isFile && snapshot.length() == item.fileSize) { "Snapshot unavailable. Choose the file again." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    snapshot.inputStream().use { source ->
                        val buffer = ByteArray(FILE_CHUNK_SIZE)
                        while (true) {
                            ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    insist(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256Checksum) { "Snapshot changed. Choose the file again." }
                    paused = null
                } else {
                    val document = requireNotNull(uri)
                    val resolver = context.contentResolver
                    insist(document.scheme == "content") { "Choose a document with the system file picker." }
                    var name = "document"
                    resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
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
                    resolver.openInputStream(document)?.use { source ->
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
                }
                val name = item.fileName
                val count = item.fileSize
                val checksum = item.sha256Checksum
                item = item.copy(status = TransferStatus.TRANSFERRING, errorMessage = null)
                fileTransferDao.insertOrUpdate(item)
                if (!send(ProtocolMessage.FileInit(id, name, count, checksum, resume = resumed != null), destination)) throw TransferInterrupted()
                offered = true
                suspend fun nextAck(expectedBytes: Long, status: String) {
                    val response = withTimeout(ackTimeoutMs) { ack.receive() }
                    insist(response.status == status && response.receivedBytes == expectedBytes) {
                        if (response.status == "REJECTED") "Mac file receiving is off. Enable it in the Mac companion." else "The Mac rejected or could not verify this transfer. Try again."
                    }
                    if (status == "COMPLETED") insist(response.sha256Checksum == checksum) { "Mac checksum verification failed." }
                }
                val ready = withTimeout(ackTimeoutMs) { ack.receive() }
                var offset = ready.receivedBytes
                if (ready.status == "COMPLETED") {
                    insist(offset == count && ready.sha256Checksum == checksum) { "Mac checksum verification failed." }
                } else {
                    insist(ready.status == "READY" && offset >= 0 && offset < count && offset % FILE_CHUNK_SIZE == 0L && (resumed != null || offset == 0L)) { "The Mac rejected this transfer." }
                }
                snapshot!!.inputStream().use { source ->
                    source.channel.position(offset)
                    var index = (offset / FILE_CHUNK_SIZE).toInt()
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
                        if (!send(ProtocolMessage.FileChunk(id, index++, total, offset,
                            Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size), destination)) throw TransferInterrupted()
                        offset += bytes.size
                        nextAck(offset, if (offset == count) "COMPLETED" else "IN_PROGRESS")
                        item = item.copy(transferredBytes = offset)
                        fileTransferDao.insertOrUpdate(item)
                    }
                }
                item = item.copy(status = TransferStatus.COMPLETED, transferredBytes = count, calculatedChecksum = checksum)
                fileTransferDao.insertOrUpdate(item)
                complete = true
            } catch (error: Exception) {
                keep = snapshot != null && item.sha256Checksum.isNotEmpty() && destination != null &&
                    (error is TimeoutCancellationException || error is TransferInterrupted) && scope.isActive && retain(destination)
                if (keep) paused = Paused(item, destination!!, snapshot!!)
                val message = when (error) {
                    is TransferInterrupted -> "Connection interrupted. Reconnect the same Mac and resume within 10 minutes."
                    is TimeoutCancellationException -> "Transfer paused. Reconnect the same Mac and resume within 10 minutes."
                    is CancellationException -> "Cancelled. Any unverified partial file is removed."
                    is SecurityException -> "Document access was revoked. Choose the file again."
                    is TransferFailure -> error.explanation
                    else -> "Could not read or send this file. Check the connection, free space and document access."
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    fileTransferDao.insertOrUpdate(item.copy(status = if (keep) TransferStatus.PAUSED else TransferStatus.FAILED, errorMessage = message))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (keep && !scope.isActive) {
                        keep = false
                        paused = null
                        fileTransferDao.insertOrUpdate(item.copy(status = TransferStatus.FAILED, errorMessage = "App stopped. Choose the file again."))
                    }
                    if (!complete && !keep && offered && destination != null) runCatching { send(ProtocolMessage.FileCancel(id), destination) }
                    if (!keep) { snapshot?.delete(); if (paused === resumed) paused = null }
                    acknowledgements.remove(id)?.close()
                    expectedPeer = null
                    activeId = null
                    occupied.set(false)
                    busyState.value = false
                }
            }
        }
        activeJob!!.start()
        return true
    }

    fun cancelTransfer(transferId: String) {
        if (activeId == transferId) activeJob?.cancel()
        val value = paused?.takeIf { it.item.transferId == transferId } ?: return
        paused = null
        scope.launch(Dispatchers.IO) {
            value.snapshot.delete()
            val current = target()
            if (current != null && current.device.id == value.destination.device.id && current.device.fingerprint == value.destination.device.fingerprint) send(ProtocolMessage.FileCancel(transferId), current)
            fileTransferDao.insertOrUpdate(value.item.copy(status = TransferStatus.FAILED, errorMessage = "Cancelled."))
        }
    }
}
