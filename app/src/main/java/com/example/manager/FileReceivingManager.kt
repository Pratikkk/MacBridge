package com.example.manager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import com.example.data.FileTransferDao
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** Verified files stay private until the user chooses a Save As destination. */
class FileReceivingManager(
    private val context: Context,
    private val history: FileTransferDao,
    private val scope: CoroutineScope,
    private val target: suspend () -> FileTransferTarget?,
    private val send: suspend (ProtocolMessage, FileTransferTarget) -> Boolean,
    private val idleTimeoutMs: Long = 30000,
    private val retain: suspend (FileTransferTarget) -> Boolean = { false },
    private val resumeWindowMs: Long = TRANSFER_RESUME_WINDOW_MS,
    private val progressClock: () -> Long = System::nanoTime
) {
    private val folder = File(context.filesDir, "received_files")
    private val checkpoint = TransferCheckpoint(File(context.filesDir, "transfer_state/incoming.json"))
    private val checkpointUpdates = ProgressUpdates(progressClock)
    private val mutex = Mutex()
    private data class Incoming(val id: String, var source: FileTransferTarget, var item: FileTransferItem,
        val temporary: File, val output: FileOutputStream, val digest: MessageDigest,
        val updates: ProgressUpdates, var index: Int = 0, var activity: Long = System.nanoTime())
    private val activeSignals = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var active: Incoming? = null
        set(value) {
            field = value
            if (value != null) activeSignals.trySend(Unit)
        }
    private var pausedAt: Long? = null
    private val cancelled = LinkedHashMap<String, FileTransferTarget>()
    private val receipts = LinkedHashMap<String, Pair<FileTransferTarget, FileTransferItem>>()
    private val receiptDetails = LinkedHashMap<String, JSONObject>()
    private var deadline = 0L

    private fun saveCheckpoint(force: Boolean = true) {
        if (!force && !checkpointUpdates.due()) return
        var incoming: JSONObject? = null
        active?.let { value ->
            value.output.flush()
            value.output.fd.sync()
            val prefix = try { (value.digest.clone() as MessageDigest).checkpointHash() }
                catch (_: CloneNotSupportedException) { checkpointDigest(value.temporary, value.item.transferredBytes).checkpointHash() }
            incoming = value.item.checkpointItem().put("peer", value.source.checkpointPeer())
                .put("temporary", value.temporary.name).put("prefix", prefix).put("expires", deadline)
        }
        receiptDetails.entries.removeAll { it.value.getLong("expires") <= System.currentTimeMillis() }
        receipts.keys.retainAll(receiptDetails.keys)
        while (receiptDetails.size > 64) { val id = receiptDetails.keys.first(); receiptDetails.remove(id); receipts.remove(id) }
        checkpoint.save(JSONObject().put("incoming", incoming).put("receipts", JSONArray(receiptDetails.values.toList())))
    }
    private fun samePeer(a: FileTransferTarget, b: FileTransferTarget) = a.device.id == b.device.id && a.device.fingerprint == b.device.fingerprint
    private fun sameSession(a: FileTransferTarget?, b: FileTransferTarget): Boolean =
        a != null && a.device.id == b.device.id && a.device.fingerprint == b.device.fingerprint && a.session == b.session
    private val initialized = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        check(folder.mkdirs() || folder.isDirectory)
        history.failInterruptedIncoming()
        val saved = checkpoint.load()
        val storedReceipts = saved.optJSONArray("receipts") ?: JSONArray()
        for (index in 0 until minOf(storedReceipts.length(), 64)) {
            runCatching {
                val record = storedReceipts.getJSONObject(index)
                record.checkpointExpiry(resumeWindowMs)
                val peer = record.checkpointTarget()
                require(retain(peer))
                var item = record.checkpointItem(TransferDirection.INCOMING)
                require(item.transferredBytes == item.fileSize)
                val name = record.getString("destination")
                require(name.matches(Regex("[a-f0-9-]{36}\\.verified")))
                val destination = File(folder, name)
                val source = if (destination.isFile) checkpointFile(folder, name, "") else checkpointFile(folder, record.getString("temporary"), ".incoming-")
                require(source.length() == item.fileSize && (source == destination || checkpointDigest(source).checkpointHash() == item.sha256Checksum))
                if (source != destination) check(source.renameTo(destination))
                item = item.copy(status = TransferStatus.COMPLETED, filePath = destination.absolutePath, calculatedChecksum = item.sha256Checksum)
                history.insertOrUpdate(item)
                val id = item.transferId.removePrefix("incoming-")
                receipts[id] = peer to item
                receiptDetails[id] = record.put("temporary", JSONObject.NULL)
            }
        }
        runCatching {
            val record = saved.getJSONObject("incoming")
            val expires = record.checkpointExpiry(resumeWindowMs)
            val peer = record.checkpointTarget()
            require(retain(peer))
            val item = record.checkpointItem(TransferDirection.INCOMING)
            require(item.transferredBytes < item.fileSize && item.transferredBytes % FILE_CHUNK_SIZE == 0L)
            val temporary = checkpointFile(folder, record.getString("temporary"), ".incoming-")
            val digest = checkpointDigest(temporary, item.transferredBytes)
            require((digest.clone() as MessageDigest).checkpointHash() == record.getString("prefix"))
            java.io.RandomAccessFile(temporary, "rw").use { it.setLength(item.transferredBytes) }
            active = Incoming(item.transferId.removePrefix("incoming-"), peer, item, temporary, FileOutputStream(temporary, true), digest,
                ProgressUpdates(progressClock), index = (item.transferredBytes / FILE_CHUNK_SIZE).toInt())
            deadline = expires
            pausedAt = System.nanoTime() - (resumeWindowMs - (expires - System.currentTimeMillis())) * 1_000_000
            history.insertOrUpdate(item.copy(status = TransferStatus.PAUSED))
        }
        folder.listFiles()?.filter { it.name.startsWith(".incoming-") && it != active?.temporary }?.forEach { it.delete() }
        history.getAllTransfers().first().filter { it.direction == TransferDirection.INCOMING && it.status == TransferStatus.PAUSED }.forEach {
            if (it.transferId != active?.item?.transferId) history.insertOrUpdate(it.copy(status = TransferStatus.FAILED, errorMessage = "Saved transfer expired or is unavailable. Ask your Mac to send it again."))
        }
        saveCheckpoint()
    }
    init {
        scope.launch(Dispatchers.IO) {
            try {
                initialized.await()
                while (isActive) {
                    activeSignals.receive()
                    while (active != null) {
                        delay(250)
                        mutex.withLock {
                            val value = active ?: return@withLock
                            if (!retain(value.source) && (!sameSession(target(), value.source) || pausedAt != null || (System.nanoTime() - value.activity) / 1_000_000 > idleTimeoutMs)) {
                                abort("File sharing permission or identity changed.")
                                send(ProtocolMessage.FileAck(value.id, value.item.transferredBytes, "REJECTED"), value.source)
                            } else if (pausedAt?.let { (System.nanoTime() - it) / 1_000_000 > resumeWindowMs } == true) {
                                abort("Paused transfer expired. Ask your Mac to send it again.")
                            } else if (pausedAt == null && (!sameSession(target(), value.source) || (System.nanoTime() - value.activity) / 1_000_000 > idleTimeoutMs)) {
                                value.output.flush()
                                pausedAt = System.nanoTime()
                                deadline = System.currentTimeMillis() + resumeWindowMs
                                value.item = value.item.copy(status = TransferStatus.PAUSED, errorMessage = null)
                                history.insertOrUpdate(value.item)
                                saveCheckpoint()
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
                    val value = active
                    if (value != null && retain(value.source)) {
                        value.item = value.item.copy(status = TransferStatus.PAUSED)
                        if (pausedAt == null) deadline = System.currentTimeMillis() + resumeWindowMs
                        saveCheckpoint()
                        history.insertOrUpdate(value.item)
                        value.output.close()
                        active = null
                    } else abort("Connection stopped. Send the file again.")
                } }
            }
        }
    }

    suspend fun process(message: ProtocolMessage, source: FileTransferTarget) = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val id = when (message) {
                is ProtocolMessage.FileInit -> message.transferId
                is ProtocolMessage.FileChunk -> message.transferId
                is ProtocolMessage.FileCancel -> message.transferId
                else -> return@withLock
            }
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@withLock
            suspend fun acknowledge(status: String, count: Long = 0, hash: String? = null) {
                send(ProtocolMessage.FileAck(id, count, status, hash), source)
            }
            if (!sameSession(target(), source)) {
                if (active?.let { samePeer(it.source, source) } == true && !retain(source)) abort("File sharing was disabled or the identity changed.")
                acknowledge("REJECTED")
                return@withLock
            }
            try {
                if (message is ProtocolMessage.FileCancel) {
                    cancelled[id] = source
                    receipts.remove(id)
                    receiptDetails.remove(id)
                    if (cancelled.size > 64) cancelled.remove(cancelled.keys.first())
                    if (active?.id == id && active?.let { samePeer(it.source, source) } == true) abort("Cancelled by your Mac.")
                    saveCheckpoint()
                    acknowledge("CANCELLED")
                    return@withLock
                }
                if (message is ProtocolMessage.FileInit) {
                    if (message.resume) {
                        if (cancelled[id]?.let { samePeer(it, source) } == true) { acknowledge("REJECTED"); return@withLock }
                        val receipt = receipts[id]
                        if (receipt != null && receiptDetails[id]?.optLong("expires", 0)?.let { it > System.currentTimeMillis() } == true && samePeer(receipt.first, source) && receipt.second.fileSize == message.fileSize && receipt.second.fileName == safeName(message.fileName) && receipt.second.sha256Checksum == message.sha256Checksum && message.chunkSize == FILE_CHUNK_SIZE) {
                            val verified = checkpointFile(folder, File(requireNotNull(receipt.second.filePath)).name, "")
                            require(verified.length() == receipt.second.fileSize && checkpointDigest(verified).checkpointHash() == receipt.second.sha256Checksum)
                            acknowledge("COMPLETED", receipt.second.fileSize, receipt.second.sha256Checksum)
                            return@withLock
                        }
                        val partial = active
                        if (partial != null && partial.id == id && samePeer(partial.source, source)) {
                            if (partial.item.fileSize != message.fileSize || partial.item.fileName != safeName(message.fileName) || partial.item.sha256Checksum != message.sha256Checksum || message.chunkSize != FILE_CHUNK_SIZE) {
                                acknowledge("REJECTED"); return@withLock
                            }
                            if (pausedAt?.let { (System.nanoTime() - it) / 1_000_000 > resumeWindowMs } == true) {
                                abort("Paused transfer expired.")
                            } else {
                                partial.output.flush()
                                require(partial.temporary.length() == partial.item.transferredBytes)
                                partial.digest.reset()
                                partial.temporary.inputStream().use { prefix ->
                                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                                    while (true) {
                                        ensureActive()
                                        val n = prefix.read(buffer)
                                        if (n < 0) break
                                        partial.digest.update(buffer, 0, n)
                                    }
                                }
                                partial.source = source
                                partial.activity = System.nanoTime()
                                pausedAt = null
                                partial.item = partial.item.copy(status = TransferStatus.TRANSFERRING)
                                history.insertOrUpdate(partial.item)
                                acknowledge("READY", partial.item.transferredBytes)
                                return@withLock
                            }
                        }
                        acknowledge("REJECTED")
                        return@withLock
                    }
                    if (active != null) { acknowledge("BUSY"); return@withLock }
                    require(message.fileSize in 0..MAX_FILE_BYTES && message.chunkSize == FILE_CHUNK_SIZE)
                    require(message.fileName.isNotEmpty() && message.fileName.length <= 256)
                    require(message.sha256Checksum.matches(Regex("[0-9a-f]{64}")))
                    if (history.getTransfer("incoming-$id") != null) { acknowledge("REJECTED"); return@withLock }
                    require(folder.usableSpace >= message.fileSize + 8 * 1024 * 1024)
                    val name = safeName(message.fileName)
                    val temporary = File.createTempFile(".incoming-", ".part", folder)
                    val item = FileTransferItem("incoming-$id", name, message.fileSize,
                        direction = TransferDirection.INCOMING, status = TransferStatus.TRANSFERRING,
                        sha256Checksum = message.sha256Checksum)
                    val output = try { FileOutputStream(temporary) } catch (error: Exception) { temporary.delete(); throw error }
                    active = Incoming(id, source, item, temporary, output, MessageDigest.getInstance("SHA-256"), ProgressUpdates(progressClock))
                    deadline = System.currentTimeMillis() + resumeWindowMs
                    history.insertOrUpdate(item)
                    saveCheckpoint()
                    if (item.fileSize == 0L) finish() else acknowledge("READY")
                    return@withLock
                }
                val value = active
                if (message !is ProtocolMessage.FileChunk || value == null || value.id != id || pausedAt != null || !sameSession(value.source, source)) {
                    acknowledge("REJECTED"); return@withLock
                }
                val length = minOf(FILE_CHUNK_SIZE.toLong(), value.item.fileSize - value.item.transferredBytes).toInt()
                require(message.chunkIndex == value.index && message.offset == value.item.transferredBytes)
                require(message.totalChunks == ((value.item.fileSize + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE).toInt())
                require(message.chunkLength == length && message.dataBase64.length == ((length + 2) / 3) * 4)
                require(message.dataBase64.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
                val bytes = Base64.decode(message.dataBase64, Base64.NO_WRAP)
                require(bytes.size == length)
                value.output.write(bytes)
                value.digest.update(bytes)
                value.index++
                value.activity = System.nanoTime()
                value.item = value.item.copy(transferredBytes = value.item.transferredBytes + length)
                deadline = System.currentTimeMillis() + resumeWindowMs
                if (value.item.transferredBytes < value.item.fileSize && value.updates.due()) history.insertOrUpdate(value.item)
                if (value.item.transferredBytes == value.item.fileSize) finish()
                else { saveCheckpoint(force = false); acknowledge("IN_PROGRESS", value.item.transferredBytes) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                abort("Could not verify or store the file. Ask your Mac to send it again.")
                acknowledge("FAILED")
            }
        }
    }

    private fun safeName(name: String) = name.replace('\\', '/').substringAfterLast('/')
        .filter { !it.isISOControl() }.trim(' ', '.').take(100).dropLastWhile { it.isHighSurrogate() }.ifEmpty { "document" }

    private suspend fun finish() {
        val value = active ?: return
        val hash = value.digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != value.item.sha256Checksum || value.item.transferredBytes != value.item.fileSize) {
            abort("File checksum verification failed. Ask your Mac to retry.")
            send(ProtocolMessage.FileAck(value.id, 0, "VERIFICATION_FAILED"), value.source)
            return
        }
        // Check permission/session again immediately before publishing the verified file.
        require(sameSession(target(), value.source))
        value.output.fd.sync()
        value.output.close()
        val destination = File(folder, UUID.randomUUID().toString() + ".verified")
        receiptDetails[value.id] = value.item.checkpointItem().put("peer", value.source.checkpointPeer())
            .put("destination", destination.name).put("temporary", value.temporary.name).put("expires", System.currentTimeMillis() + resumeWindowMs)
        active = null
        saveCheckpoint()
        check(value.temporary.renameTo(destination))
        try {
            history.insertOrUpdate(value.item.copy(status = TransferStatus.COMPLETED,
                calculatedChecksum = hash, filePath = destination.absolutePath))
        } catch (error: Exception) { destination.delete(); throw error }
        receipts[value.id] = value.source to value.item.copy(status = TransferStatus.COMPLETED, calculatedChecksum = hash, filePath = destination.absolutePath)
        receiptDetails[value.id]?.put("temporary", JSONObject.NULL)
        saveCheckpoint()
        if (receipts.size > 64) receipts.remove(receipts.keys.first())
        active = null
        pausedAt = null
        send(ProtocolMessage.FileAck(value.id, value.item.fileSize, "COMPLETED", hash), value.source)
    }

    private suspend fun abort(reason: String) {
        val value = active ?: return
        active = null
        pausedAt = null
        runCatching { value.output.close() }
        value.temporary.delete()
        saveCheckpoint()
        history.insertOrUpdate(value.item.copy(status = TransferStatus.FAILED, errorMessage = reason))
    }

    suspend fun cancel(transferId: String) = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val value = active?.takeIf { it.item.transferId == transferId } ?: return@withLock
            cancelled[value.id] = value.source
            if (cancelled.size > 64) cancelled.remove(cancelled.keys.first())
            abort("Cancelled. Ask your Mac to send the file again.")
            val current = target()
            if (current != null && samePeer(current, value.source)) {
                send(ProtocolMessage.FileAck(value.id, value.item.transferredBytes, "CANCELLED"), current)
            }
        }
    }

    suspend fun saveAs(transferId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        var outputOpened = false
        try {
            require(uri.scheme == "content")
            val item = history.getTransfer(transferId) ?: return@withContext false
            require(item.direction == TransferDirection.INCOMING && item.status == TransferStatus.COMPLETED)
            val file = File(item.filePath ?: return@withContext false)
            require(file.canonicalFile.parentFile == folder.canonicalFile && file.name.endsWith(".verified"))
            require(file.isFile && file.length() == item.fileSize)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    outputOpened = true
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= item.fileSize)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    require(total == item.fileSize)
                    require(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256Checksum)
                    output.flush()
                } ?: return@withContext false
            }
            true
        } catch (error: Exception) {
            if (outputOpened) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            if (error is CancellationException) throw error
            false
        }
    }
}
