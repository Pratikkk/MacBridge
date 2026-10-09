package com.example.manager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import com.example.data.FileTransferDao
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Verified files stay private until the user chooses a Save As destination. */
class FileReceivingManager(
    private val context: Context,
    private val history: FileTransferDao,
    private val scope: CoroutineScope,
    private val target: suspend () -> FileTransferTarget?,
    private val send: suspend (ProtocolMessage, FileTransferTarget) -> Boolean,
    private val idleTimeoutMs: Long = 30000
) {
    private val folder = File(context.filesDir, "received_files")
    private val mutex = Mutex()
    private data class Incoming(val id: String, val source: FileTransferTarget, var item: FileTransferItem,
        val temporary: File, val output: FileOutputStream, val digest: MessageDigest,
        var index: Int = 0, var activity: Long = System.nanoTime())
    private var active: Incoming? = null
    private fun sameSession(a: FileTransferTarget?, b: FileTransferTarget): Boolean =
        a != null && a.device.id == b.device.id && a.device.fingerprint == b.device.fingerprint && a.session == b.session
    private val initialized = scope.async(Dispatchers.IO) {
        check(folder.mkdirs() || folder.isDirectory)
        folder.listFiles()?.filter { it.name.startsWith(".incoming-") }?.forEach { it.delete() }
        history.failInterruptedIncoming()
    }
    init {
        scope.launch(Dispatchers.IO) {
            try {
                initialized.await()
                while (isActive) {
                    delay(250)
                    mutex.withLock {
                        val value = active ?: return@withLock
                        if (!sameSession(target(), value.source) || (System.nanoTime() - value.activity) / 1_000_000 > idleTimeoutMs) {
                            abort("Interrupted. Ask your Mac to send the file again.")
                            send(ProtocolMessage.FileAck(value.id, value.item.transferredBytes, "REJECTED"), value.source)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { abort("Connection stopped. Send the file again.") } }
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
                if (active?.let { sameSession(it.source, source) } == true) abort("File sharing was disabled or the connection changed.")
                acknowledge("REJECTED")
                return@withLock
            }
            try {
                if (message is ProtocolMessage.FileCancel) {
                    if (active?.id == id && active?.let { sameSession(it.source, source) } == true) abort("Cancelled by your Mac.")
                    acknowledge("CANCELLED")
                    return@withLock
                }
                if (message is ProtocolMessage.FileInit) {
                    if (active != null) { acknowledge("BUSY"); return@withLock }
                    require(message.fileSize in 0..MAX_FILE_BYTES && message.chunkSize == FILE_CHUNK_SIZE)
                    require(message.fileName.isNotEmpty() && message.fileName.length <= 256)
                    require(message.sha256Checksum.matches(Regex("[0-9a-f]{64}")))
                    if (history.getTransfer("incoming-$id") != null) { acknowledge("REJECTED"); return@withLock }
                    require(folder.usableSpace >= message.fileSize + 8 * 1024 * 1024)
                    val name = message.fileName.replace('\\', '/').substringAfterLast('/')
                        .filter { !it.isISOControl() }.trim(' ', '.').take(100).dropLastWhile { it.isHighSurrogate() }.ifEmpty { "document" }
                    val temporary = File.createTempFile(".incoming-", ".part", folder)
                    val item = FileTransferItem("incoming-$id", name, message.fileSize,
                        direction = TransferDirection.INCOMING, status = TransferStatus.TRANSFERRING,
                        sha256Checksum = message.sha256Checksum)
                    val output = try { FileOutputStream(temporary) } catch (error: Exception) { temporary.delete(); throw error }
                    active = Incoming(id, source, item, temporary, output, MessageDigest.getInstance("SHA-256"))
                    history.insertOrUpdate(item)
                    if (item.fileSize == 0L) finish() else acknowledge("READY")
                    return@withLock
                }
                val value = active
                if (message !is ProtocolMessage.FileChunk || value == null || value.id != id || !sameSession(value.source, source)) {
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
                history.insertOrUpdate(value.item)
                if (value.item.transferredBytes == value.item.fileSize) finish()
                else acknowledge("IN_PROGRESS", value.item.transferredBytes)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                abort("Could not verify or store the file. Ask your Mac to send it again.")
                acknowledge("FAILED")
            }
        }
    }

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
        check(value.temporary.renameTo(destination))
        try {
            history.insertOrUpdate(value.item.copy(status = TransferStatus.COMPLETED,
                calculatedChecksum = hash, filePath = destination.absolutePath))
        } catch (error: Exception) { destination.delete(); throw error }
        active = null
        send(ProtocolMessage.FileAck(value.id, value.item.fileSize, "COMPLETED", hash), value.source)
    }

    private suspend fun abort(reason: String) {
        val value = active ?: return
        active = null
        runCatching { value.output.close() }
        value.temporary.delete()
        history.insertOrUpdate(value.item.copy(status = TransferStatus.FAILED, errorMessage = reason))
    }

    suspend fun cancel(transferId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val value = active?.takeIf { it.item.transferId == transferId } ?: return@withLock
            abort("Cancelled. Ask your Mac to send the file again.")
            send(ProtocolMessage.FileAck(value.id, value.item.transferredBytes, "CANCELLED"), value.source)
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
