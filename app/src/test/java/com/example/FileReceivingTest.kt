package com.example

import android.app.Application
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class TestFileHistory : FileTransferDao {
    val values = ConcurrentHashMap<String, FileTransferItem>()
    val writes = CopyOnWriteArrayList<FileTransferItem>()
    override fun getAllTransfers(): Flow<List<FileTransferItem>> = flowOf(values.values.toList())
    override suspend fun getTransfer(transferId: String) = values[transferId]
    override suspend fun insertOrUpdate(transfer: FileTransferItem) { values[transfer.transferId] = transfer; writes.add(transfer) }
    override suspend fun deleteById(transferId: String) { values.remove(transferId) }
    override suspend fun failInterruptedOutgoing() {}
    override suspend fun failInterruptedIncoming() {
        values.replaceAll { _, item -> if (item.direction == TransferDirection.INCOMING && item.status in listOf(TransferStatus.PENDING, TransferStatus.TRANSFERRING))
            item.copy(status = TransferStatus.FAILED) else item }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileReceivingTest {
    private class Destination(val file: File, val denied: Boolean) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("private provider detail")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle { file.delete(); return Bundle() }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
    private class Fixture(timeout: Long = 30000, resumable: Boolean = false, resumeWindow: Long = TRANSFER_RESUME_WINDOW_MS, progressClock: () -> Long = System::nanoTime) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = TestFileHistory()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val source = FileTransferTarget(PairedDevice("mac", "Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true), 1)
        val target = AtomicReference<FileTransferTarget?>(source)
        val permitted = java.util.concurrent.atomic.AtomicBoolean(resumable)
        val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val manager = FileReceivingManager(context, history, scope, { target.get() }, { message, _ ->
            acks.add(message as ProtocolMessage.FileAck); true
        }, timeout, retain = { permitted.get() }, resumeWindowMs = resumeWindow, progressClock = progressClock)
        val folder get() = File(context.filesDir, "received_files")
        suspend fun process(message: ProtocolMessage) = manager.process(message, source)
        suspend fun start(data: ByteArray, name: String = "🌉 notes.bin", hash: String = hash(data), id: String = "mac-file") =
            process(ProtocolMessage.FileInit(id, name, data.size.toLong(), hash))
        suspend fun deliver(data: ByteArray, id: String = "mac-file") {
            start(data, id = id)
            for (index in 0 until ((data.size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE)) {
                val offset = index * FILE_CHUNK_SIZE
                val bytes = data.copyOfRange(offset, minOf(offset + FILE_CHUNK_SIZE, data.size))
                process(ProtocolMessage.FileChunk(id, index, (data.size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE,
                    offset.toLong(), android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), bytes.size))
            }
        }
        suspend fun close() { job.cancelAndJoin(); folder.deleteRecursively() }
    }
    companion object {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }
    private fun destination(context: Context, denied: Boolean = false): File {
        val file = File(context.cacheDir, "saved-test.bin").apply { delete() }
        val provider = Destination(file, denied)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "save-tests"; exported = true })
        ShadowContentResolver.registerProviderInternal("save-tests", provider)
        return file
    }

    @Test fun `large receive acknowledges every chunk without writing presentation state per chunk`() = runBlocking {
        val fixture = Fixture(progressClock = { 0L })
        try { with(fixture) {
            val bytes = ByteArray(FILE_CHUNK_SIZE * 128) { (it % 256).toByte() }
            deliver(bytes)
            assertEquals(129, acks.size)
            assertEquals(127, acks.count { it.status == "IN_PROGRESS" })
            assertEquals(2, history.writes.size)
            assertEquals(bytes.size.toLong(), history.values.values.single().transferredBytes)
            assertArrayEquals(bytes, File(history.values.values.single().filePath!!).readBytes())
        } } finally { fixture.close() }
    }

    @Test fun `disconnect resumes same peer offset and lost final acknowledgement returns receipt`() = runBlocking {
        val fixture = Fixture(resumable = true)
        try { with(fixture) {
            val bytes = ByteArray(80000) { (it % 256).toByte() }
            start(bytes)
            val first = bytes.copyOfRange(0, FILE_CHUNK_SIZE)
            process(ProtocolMessage.FileChunk("mac-file", 0, 2, 0, android.util.Base64.encodeToString(first, 2), first.size))
            target.set(null)
            withTimeout(3000) { while (history.values.values.single().status != TransferStatus.PAUSED) delay(10) }
            val wrong = source.copy(device = source.device.copy(fingerprint = "changed"), session = 2)
            target.set(wrong)
            manager.process(ProtocolMessage.FileInit("mac-file", "🌉 notes.bin", bytes.size.toLong(), hash(bytes), resume = true), wrong)
            assertEquals("REJECTED", acks.last().status)
            val reconnected = source.copy(session = 2)
            target.set(reconnected)
            val init = ProtocolMessage.FileInit("mac-file", "🌉 notes.bin", bytes.size.toLong(), hash(bytes), resume = true)
            manager.process(init.copy(sha256Checksum = "0".repeat(64)), reconnected)
            assertEquals("REJECTED", acks.last().status)
            manager.process(init, reconnected)
            assertEquals(FILE_CHUNK_SIZE.toLong(), acks.last().receivedBytes)
            val last = bytes.copyOfRange(FILE_CHUNK_SIZE, bytes.size)
            manager.process(ProtocolMessage.FileChunk("mac-file", 1, 2, FILE_CHUNK_SIZE.toLong(), android.util.Base64.encodeToString(last, 2), last.size), reconnected)
            assertEquals("COMPLETED", acks.last().status)
            assertArrayEquals(bytes, File(history.values.values.single().filePath!!).readBytes())
            manager.process(init, reconnected)
            assertEquals("COMPLETED", acks.last().status)
            assertEquals(hash(bytes), acks.last().sha256Checksum)
            assertEquals(1, folder.listFiles()!!.size)
        } } finally { fixture.close() }
    }
    @Test fun `revoking permission while disconnected removes resumable partial`() = runBlocking {
        val fixture = Fixture(resumable = true)
        try { with(fixture) {
            start(byteArrayOf(1)); target.set(null)
            withTimeout(3000) { while (history.values.values.single().status != TransferStatus.PAUSED) delay(10) }
            permitted.set(false)
            withTimeout(3000) { while (history.values.values.single().status != TransferStatus.FAILED) delay(10) }
            assertTrue(folder.listFiles().isNullOrEmpty())
        } } finally { fixture.close() }
    }
    @Test fun `expired paused receive is removed and shutdown retains verified copies only`() = runBlocking {
        val fixture = Fixture(resumable = true, resumeWindow = 50)
        try { with(fixture) {
            start(byteArrayOf(1)); target.set(null)
            withTimeout(3000) { while (history.values.values.single().status != TransferStatus.FAILED) delay(10) }
            assertTrue(folder.listFiles().isNullOrEmpty())
        } } finally { fixture.close() }
    }
    @Test fun `cancel paused incoming transfer cannot be resumed by Mac`() = runBlocking {
        val fixture = Fixture(resumable = true)
        try { with(fixture) {
            start(byteArrayOf(1)); target.set(null)
            withTimeout(3000) { while (history.values.values.single().status != TransferStatus.PAUSED) delay(10) }
            manager.cancel("incoming-mac-file")
            target.set(source)
            process(ProtocolMessage.FileInit("mac-file", "🌉 notes.bin", 1, hash(byteArrayOf(1)), resume = true))
            assertEquals("REJECTED", acks.last().status)
            assertTrue(folder.listFiles().isNullOrEmpty())
        } } finally { fixture.close() }
    }

    @Test fun `verified multichunk unicode file stays private and Save As copies exact bytes`() = scenario {
        val bytes = ByteArray(80000) { (it % 256).toByte() }
        deliver(bytes)
        val item = history.values.values.single()
        assertEquals(TransferStatus.COMPLETED, item.status)
        assertEquals("🌉 notes.bin", item.fileName)
        assertEquals(hash(bytes), acks.last().sha256Checksum)
        assertArrayEquals(bytes, File(item.filePath!!).readBytes())
        val saved = destination(context)
        assertTrue(manager.saveAs(item.transferId, Uri.parse("content://save-tests/document/new")))
        assertArrayEquals(bytes, saved.readBytes())
    }
    @Test fun `empty files are verified before completion`() = scenario {
        deliver(byteArrayOf())
        assertEquals("COMPLETED", acks.single().status)
        assertEquals(hash(byteArrayOf()), acks.single().sha256Checksum)
        assertEquals(0, File(history.values.values.single().filePath!!).length())
    }
    @Test fun `disabled sharing rejects without creating history or partial`() = scenario {
        target.set(null); start(byteArrayOf(1))
        assertEquals("REJECTED", acks.last().status)
        assertTrue(history.values.isEmpty()); assertTrue(folder.listFiles().isNullOrEmpty())
    }
    @Test fun `invalid size checksum and chunk size cannot allocate a partial`() = scenario {
        for (message in listOf(ProtocolMessage.FileInit("bad", "file", MAX_FILE_BYTES + 1, hash(byteArrayOf())),
            ProtocolMessage.FileInit("bad", "file", 1, "invalid"), ProtocolMessage.FileInit("bad", "file", 1, hash(byteArrayOf()), chunkSize = 1))) {
            process(message); assertEquals("FAILED", acks.last().status)
        }
        assertTrue(history.values.isEmpty()); assertTrue(folder.listFiles().isNullOrEmpty())
    }
    @Test fun `out of order malformed and bad checksum never publish a file`() = scenario {
        val bytes = byteArrayOf(1)
        for ((id, message) in listOf("offset" to ProtocolMessage.FileChunk("offset", 0, 1, 2, "AQ==", 1),
            "base64" to ProtocolMessage.FileChunk("base64", 0, 1, 0, "!!!!", 1))) {
            start(bytes, id = id); process(message)
            assertEquals("FAILED", acks.last().status)
        }
        start(bytes, hash = hash(byteArrayOf(2)), id = "hash")
        process(ProtocolMessage.FileChunk("hash", 0, 1, 0, "AQ==", 1))
        assertEquals("VERIFICATION_FAILED", acks.last().status)
        assertTrue(history.values.values.all { it.status == TransferStatus.FAILED && it.filePath == null })
        assertTrue(folder.listFiles().isNullOrEmpty())
    }
    @Test fun `duplicate offer and wrong identity cannot overwrite active transfer`() = scenario {
        start(byteArrayOf(1))
        start(byteArrayOf(2), id = "other"); assertEquals("BUSY", acks.last().status)
        process(ProtocolMessage.FileChunk("other", 0, 1, 0, "Ag==", 1)); assertEquals("REJECTED", acks.last().status)
        process(ProtocolMessage.FileChunk("mac-file", 0, 1, 0, "AQ==", 1))
        assertEquals("COMPLETED", acks.last().status)
        start(byteArrayOf(2)); assertEquals("REJECTED", acks.last().status)
        assertArrayEquals(byteArrayOf(1), File(history.values.values.single().filePath!!).readBytes())
    }
    @Test fun `path traversal and matching names produce separate private copies`() = scenario {
        start(byteArrayOf(), name = "../../🌉 notes.bin")
        deliver(byteArrayOf(), id = "second")
        assertEquals(2, folder.listFiles()!!.size)
        assertTrue(history.values.values.all { !it.fileName.contains('/') && File(it.filePath!!).canonicalFile.parentFile == folder.canonicalFile })
    }
    @Test fun `cancel removes only unverified partial and emits cancellation`() = scenario {
        start(byteArrayOf(1)); manager.cancel("incoming-mac-file")
        assertEquals("CANCELLED", acks.last().status)
        assertEquals(TransferStatus.FAILED, history.values.values.single().status)
        assertTrue(folder.listFiles().isNullOrEmpty())
    }
    @Test fun `revoked permission and reconnected session discard partials`() = scenario {
        for (session in listOf<FileTransferTarget?>(null, source.copy(session = 2))) {
            target.set(source); start(byteArrayOf(1), id = if (session == null) "permission" else "session")
            target.set(session)
            withTimeout(3000) { while (history.values.values.any { it.status == TransferStatus.TRANSFERRING }) delay(10) }
            assertEquals("REJECTED", acks.last().status)
            assertTrue(folder.listFiles().isNullOrEmpty())
        }
    }
    @Test fun `idle timeout and process shutdown discard partials`() = runBlocking {
        val fixture = Fixture(50)
        try {
            fixture.start(byteArrayOf(1))
            withTimeout(3000) { while (fixture.history.values.values.single().status != TransferStatus.FAILED) delay(10) }
            fixture.start(byteArrayOf(1), id = "shutdown")
            fixture.job.cancelAndJoin()
            assertTrue(fixture.folder.listFiles().isNullOrEmpty())
            assertTrue(fixture.history.values.values.all { it.status == TransferStatus.FAILED })
        } finally { fixture.close() }
    }
    @Test fun `startup removes stale partials marks interrupted history and retains verified files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.filesDir, "received_files").apply { mkdirs() }
        File(folder, ".incoming-stale").writeBytes(byteArrayOf(1))
        val saved = File(folder, "saved.verified").apply { writeBytes(byteArrayOf(2)) }
        val history = TestFileHistory()
        history.insertOrUpdate(FileTransferItem("interrupted", "file", 1, direction = TransferDirection.INCOMING,
            status = TransferStatus.TRANSFERRING, sha256Checksum = "hash"))
        val job = SupervisorJob()
        val source = FileTransferTarget(PairedDevice("mac", "Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true), 1)
        val manager = FileReceivingManager(context, history, CoroutineScope(job + Dispatchers.IO), { source }, { _, _ -> true })
        try {
            manager.process(ProtocolMessage.FileCancel("noop"), source)
            assertFalse(File(folder, ".incoming-stale").exists())
            assertTrue(saved.exists())
            assertEquals(TransferStatus.FAILED, history.values["interrupted"]!!.status)
        } finally { job.cancelAndJoin(); folder.deleteRecursively() }
    }
    @Test fun `unrelated clipboard permission changes do not cancel incoming files`() = scenario {
        start(byteArrayOf(1))
        target.set(source.copy(device = source.device.copy(allowClipboard = true)))
        delay(350)
        process(ProtocolMessage.FileChunk("mac-file", 0, 1, 0, "AQ==", 1))
        assertEquals("COMPLETED", acks.last().status)
    }
    @Test fun `Save As permission failure preserves verified private copy`() = scenario {
        deliver(byteArrayOf(1)); val item = history.values.values.single()
        destination(context, denied = true)
        assertFalse(manager.saveAs(item.transferId, Uri.parse("content://save-tests/document/new")))
        assertTrue(File(item.filePath!!).exists())
        assertEquals(TransferStatus.COMPLETED, history.values.values.single().status)
    }
    @Test fun `Save As rejects unfinished or tampered source`() = scenario {
        start(byteArrayOf(1))
        val saved = destination(context)
        assertFalse(manager.saveAs("incoming-mac-file", Uri.parse("content://save-tests/document/new")))
        assertFalse(saved.exists())
        process(ProtocolMessage.FileChunk("mac-file", 0, 1, 0, "AQ==", 1))
        File(history.values.values.single().filePath!!).writeBytes(byteArrayOf(2))
        assertFalse(manager.saveAs("incoming-mac-file", Uri.parse("content://save-tests/document/new")))
        assertFalse("Failed export should be discarded when the provider supports deletion", saved.exists())
    }
}
