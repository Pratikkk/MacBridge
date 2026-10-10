package com.example

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.example.data.FileTransferDao
import com.example.manager.*
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileSendingTest {
    private class History : FileTransferDao {
        val values = ConcurrentHashMap<String, FileTransferItem>()
        override fun getAllTransfers(): Flow<List<FileTransferItem>> = flowOf(values.values.toList())
        override suspend fun getTransfer(transferId: String) = values[transferId]
        override suspend fun insertOrUpdate(transfer: FileTransferItem) { values[transfer.transferId] = transfer }
        override suspend fun deleteById(transferId: String) { values.remove(transferId) }
        override suspend fun failInterruptedOutgoing() {}
        override suspend fun failInterruptedIncoming() {}
    }
    private class Provider(val file: File, val denied: Boolean = false, val size: Long? = file.length()) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            if (denied) throw SecurityException("private provider detail")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>("🌉 notes.txt", size)) }
        }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
    private fun scenario(data: ByteArray, denied: Boolean = false, reportedSize: Long? = data.size.toLong(),
        targetAllowed: Boolean = true, response: String = "valid", cancel: Boolean = false, oversizedStream: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("file-test", ".bin", context.cacheDir).apply { writeBytes(data) }
        if (oversizedStream) java.io.RandomAccessFile(source, "rw").use { it.setLength(MAX_FILE_BYTES + 1) }
        val provider = Provider(source, denied, reportedSize)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "file-tests"; exported = true })
        ShadowContentResolver.registerProviderInternal("file-tests", provider)
        val history = History()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mac = PairedDevice("mac", "Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true)
        lateinit var manager: FileTransferManager
        val received = java.io.ByteArrayOutputStream()
        var sent = 0
        var cancelled = false
        val waiting = CompletableDeferred<Unit>()
        manager = FileTransferManager(context, history, scope, { if (targetAllowed) FileTransferTarget(mac, 1) else null }, frame@{ message, _ ->
            sent++
            when (message) {
                is ProtocolMessage.FileInit -> {
                    waiting.complete(Unit)
                    if (response != "timeout") manager.handleAck(ProtocolMessage.FileAck(message.transferId, 0,
                        if (response == "reject") "REJECTED" else if (data.isEmpty()) "COMPLETED" else "READY",
                        if (data.isEmpty()) message.sha256Checksum else null), if (response == "wrong-peer") "other" else "mac")
                }
                is ProtocolMessage.FileChunk -> {
                    if (response == "disconnect") return@frame false
                    received.write(android.util.Base64.decode(message.dataBase64, android.util.Base64.NO_WRAP))
                    val last = message.chunkIndex == message.totalChunks - 1
                    manager.handleAck(ProtocolMessage.FileAck(message.transferId, message.offset + message.chunkLength,
                        if (last) "COMPLETED" else "IN_PROGRESS",
                        if (last) if (response == "bad-hash") "wrong" else MessageDigest.getInstance("SHA-256").digest(received.toByteArray()).joinToString("") { "%02x".format(it) } else null), "mac")
                }
                is ProtocolMessage.FileCancel -> cancelled = true
                else -> {}
            }
            true
        }, ackTimeoutMs = 200)
        try {
            assertTrue(manager.sendFile(Uri.parse("content://file-tests/document")))
            assertFalse(manager.sendFile(Uri.parse("content://file-tests/document")))
            if (cancel) {
                withTimeout(5000) { waiting.await() }
                manager.cancelTransfer(history.values.keys.single())
            }
            withTimeout(5000) { manager.busy.first { !it } }
            val item = history.values.values.single()
            val succeeds = !denied && targetAllowed && response == "valid" && !cancel && !oversizedStream && (reportedSize == null || reportedSize <= MAX_FILE_BYTES)
            val paused = !cancel && response in listOf("timeout", "wrong-peer", "disconnect")
            assertEquals(item.errorMessage, if (succeeds) TransferStatus.COMPLETED else if (paused) TransferStatus.PAUSED else TransferStatus.FAILED, item.status)
            if (succeeds) { assertArrayEquals(data, received.toByteArray()); assertEquals(data.size.toLong(), item.transferredBytes) }
            if (!targetAllowed || denied || oversizedStream || reportedSize != null && reportedSize > MAX_FILE_BYTES) assertEquals(0, sent)
            if (cancel || response in listOf("reject", "bad-hash")) assertTrue(cancelled)
            if (paused) {
                assertFalse(cancelled)
                assertTrue(File(context.cacheDir, "outgoing_transfers").listFiles().orEmpty().any { it.name.endsWith(".part") })
                manager.cancelTransfer(item.transferId)
                withTimeout(3000) { while (history.values[item.transferId]!!.status == TransferStatus.PAUSED) delay(10) }
            }
            assertFalse(File(context.cacheDir, "outgoing_transfers").listFiles().orEmpty().any { it.name.endsWith(".part") })
            if (denied) assertFalse(item.errorMessage.orEmpty().contains("private provider detail"))
        } finally { scope.cancel(); source.delete() }
    }
    @Test fun `resume uses confirmed offset original snapshot and original Mac identity`() = resumeScenario(false)
    @Test fun `lost final acknowledgement resumes receipt without duplicate chunks`() = resumeScenario(true)
    @Test fun `corrupt private snapshot is discarded before any resume frame`() = resumeScenario(false, corrupt = true)
    private fun resumeScenario(lostFinal: Boolean, corrupt: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = ByteArray(80000) { (it % 256).toByte() }
        val source = File.createTempFile("resume-source", ".bin", context.cacheDir).apply { writeBytes(bytes) }
        val provider = Provider(source)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "file-tests"; exported = true })
        ShadowContentResolver.registerProviderInternal("file-tests", provider)
        val history = History()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val mac = FileTransferTarget(PairedDevice("mac", "Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true), 1)
        val current = java.util.concurrent.atomic.AtomicReference<FileTransferTarget?>(mac)
        val received = java.io.ByteArrayOutputStream()
        val frames = java.util.concurrent.CopyOnWriteArrayList<ProtocolMessage>()
        var resumed = false
        lateinit var manager: FileTransferManager
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        manager = FileTransferManager(context, history, scope, { current.get() }, frame@{ message, _ ->
            frames.add(message)
            when (message) {
                is ProtocolMessage.FileInit -> {
                    resumed = message.resume
                    manager.handleAck(ProtocolMessage.FileAck(message.transferId, received.size().toLong(), if (resumed && lostFinal) "COMPLETED" else "READY", if (resumed && lostFinal) checksum else null), "mac")
                }
                is ProtocolMessage.FileChunk -> {
                    if (!resumed && !lostFinal && message.offset > 0) return@frame false
                    assertEquals(received.size().toLong(), message.offset)
                    received.write(android.util.Base64.decode(message.dataBase64, android.util.Base64.NO_WRAP))
                    if (!resumed && lostFinal && received.size() == bytes.size) return@frame true
                    manager.handleAck(ProtocolMessage.FileAck(message.transferId, received.size().toLong(), if (received.size() == bytes.size) "COMPLETED" else "IN_PROGRESS", checksum), "mac")
                }
                else -> {}
            }
            true
        }, ackTimeoutMs = 100)
        try {
            assertTrue(manager.sendFile(Uri.parse("content://file-tests/document")))
            withTimeout(5000) { manager.busy.first { !it } }
            val id = history.values.keys.single()
            assertEquals(TransferStatus.PAUSED, history.values[id]!!.status)
            assertFalse(manager.sendFile(Uri.parse("content://file-tests/document")))
            current.set(mac.copy(device = mac.device.copy(fingerprint = "changed"), session = 2))
            assertFalse(manager.resumeTransfer(id))
            current.set(mac.copy(device = mac.device.copy(id = "other"), session = 2))
            assertFalse(manager.resumeTransfer(id))
            current.set(mac.copy(session = 2))
            source.writeBytes(byteArrayOf(99))
            val before = frames.size
            if (corrupt) File(context.cacheDir, "outgoing_transfers").listFiles()!!.single().writeBytes(bytes.reversedArray())
            assertTrue(manager.resumeTransfer(id))
            withTimeout(5000) { manager.busy.first { !it } }
            if (corrupt) {
                assertEquals(TransferStatus.FAILED, history.values[id]!!.status)
                assertEquals(before, frames.size)
            } else {
                assertEquals(TransferStatus.COMPLETED, history.values[id]!!.status)
                assertArrayEquals(bytes, received.toByteArray())
                val newChunks = frames.drop(before).filterIsInstance<ProtocolMessage.FileChunk>()
                if (lostFinal) assertTrue(newChunks.isEmpty()) else assertEquals(65536, newChunks.single().offset)
            }
            assertTrue(File(context.cacheDir, "outgoing_transfers").listFiles().isNullOrEmpty())
        } finally { job.cancelAndJoin(); source.delete() }
    }

    @Test fun `paused outgoing snapshot is removed on expiry or permission revocation`() = runBlocking {
        for (mode in listOf("expiry", "revocation", "shutdown")) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val source = File.createTempFile("resume-expiry", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            val provider = Provider(source)
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "file-tests"; exported = true })
            ShadowContentResolver.registerProviderInternal("file-tests", provider)
            val history = History()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val permitted = java.util.concurrent.atomic.AtomicBoolean(true)
            val mac = FileTransferTarget(PairedDevice("mac", "Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true), 1)
            val manager = FileTransferManager(context, history, scope, { mac }, { _, _ -> true },
                ackTimeoutMs = 50, retain = { permitted.get() }, resumeWindowMs = if (mode == "expiry") 50 else 600000)
            try {
                assertTrue(manager.sendFile(Uri.parse("content://file-tests/document")))
                withTimeout(5000) { manager.busy.first { !it } }
                assertEquals(TransferStatus.PAUSED, history.values.values.single().status)
                if (mode == "revocation") permitted.set(false)
                if (mode == "shutdown") job.cancelAndJoin()
                withTimeout(3000) { while (history.values.values.single().status != TransferStatus.FAILED) delay(10) }
                assertTrue(File(context.cacheDir, "outgoing_transfers").listFiles().isNullOrEmpty())
                assertFalse(manager.resumeTransfer(history.values.keys.single()))
            } finally { job.cancelAndJoin(); source.delete() }
        }
    }

    @Test fun `multiple chunks and unknown provider size are streamed exactly`() = scenario(ByteArray(80000) { (it % 256).toByte() }, reportedSize = null)
    @Test fun `zero byte document completes only after Mac checksum acknowledgement`() = scenario(byteArrayOf())
    @Test fun `disabled sharing never reads or sends document`() = scenario(byteArrayOf(1), targetAllowed = false)
    @Test fun `revoked document access is explained without leaking provider error`() = scenario(byteArrayOf(1), denied = true)
    @Test fun `oversized metadata is rejected before copying`() = scenario(byteArrayOf(1), reportedSize = MAX_FILE_BYTES + 1)
    @Test fun `missing ack cannot become successful history`() = scenario(byteArrayOf(1), response = "timeout")
    @Test fun `Mac permission rejection leaves failed record`() = scenario(byteArrayOf(1), response = "reject")
    @Test fun `incorrect Mac checksum cannot become completed`() = scenario(byteArrayOf(1), response = "bad-hash")
    @Test fun `unreported oversized stream is bounded and deleted`() = scenario(byteArrayOf(1), reportedSize = null, oversizedStream = true)
    @Test fun `disconnect or permission revocation before chunk prevents completion`() = scenario(byteArrayOf(1), response = "disconnect")
    @Test fun `acknowledgements from another peer are ignored`() = scenario(byteArrayOf(1), response = "wrong-peer")
    @Test fun `cancel while waiting cleans snapshot and notifies Mac`() = scenario(byteArrayOf(1), response = "timeout", cancel = true)
}
