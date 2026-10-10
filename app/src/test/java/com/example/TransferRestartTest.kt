package com.example

import android.app.Application
import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.example.manager.*
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferRestartTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val peer = FileTransferTarget(PairedDevice("mac", "Mac", "pin", "key", "localhost", allowFileTransfer = true), 1)
    private val data = ByteArray(FILE_CHUNK_SIZE + 10) { (it % 256).toByte() }
    private val hash get() = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun chunk(index: Int): ProtocolMessage.FileChunk {
        val offset = index * FILE_CHUNK_SIZE
        val bytes = data.copyOfRange(offset, minOf(offset + FILE_CHUNK_SIZE, data.size))
        return ProtocolMessage.FileChunk("restart", index, 2, offset.toLong(), android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), bytes.size)
    }
    private fun init() = ProtocolMessage.FileInit("restart", "🌉 file.txt", data.size.toLong(), hash)
    private fun clean() {
        listOf("received_files", "outgoing_transfers", "transfer_state").forEach { File(context.filesDir, it).deleteRecursively() }
    }
    private fun receiver(history: TestFileHistory, job: Job, acks: MutableList<ProtocolMessage.FileAck>, allowed: Boolean = true, source: FileTransferTarget = peer) =
        FileReceivingManager(context, history, CoroutineScope(job + Dispatchers.IO), { source }, { message, _ -> acks.add(message as ProtocolMessage.FileAck); true }, retain = { allowed })

    @Test fun `incoming restart verifies saved prefix truncates trailing bytes and resumes exactly`() = runBlocking {
        clean()
        val history = TestFileHistory(); val firstJob = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, firstJob, acks)
        first.process(init(), peer); first.process(chunk(0), peer); firstJob.cancelAndJoin()
        assertEquals(TransferStatus.PAUSED, history.values["incoming-restart"]!!.status)
        File(context.filesDir, "received_files").listFiles()!!.single().appendBytes(byteArrayOf(7, 8))
        val secondJob = SupervisorJob(); val current = peer.copy(session = 2)
        val second = receiver(history, secondJob, acks, source = current)
        try {
            second.process(init().copy(resume = true), current)
            assertEquals("READY", acks.last().status); assertEquals(FILE_CHUNK_SIZE.toLong(), acks.last().receivedBytes)
            second.process(chunk(1), current)
            assertEquals("COMPLETED", acks.last().status)
            assertArrayEquals(data, File(history.values["incoming-restart"]!!.filePath!!).readBytes())
        } finally { secondJob.cancelAndJoin(); clean() }
    }
    @Test fun `abrupt stop recovers older checkpoint and safely retransmits uncommitted chunks`() = runBlocking {
        clean()
        val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, job, acks)
        first.process(init(), peer)
        val state = File(context.filesDir, "transfer_state/incoming.json")
        val initial = state.readBytes()
        first.process(chunk(0), peer); job.cancelAndJoin()
        state.writeBytes(initial) // Represents the last durable checkpoint before a process exit.
        val restartedJob = SupervisorJob(); val second = receiver(history, restartedJob, acks)
        try {
            second.process(init().copy(resume = true), peer)
            assertEquals(0L, acks.last().receivedBytes)
            second.process(chunk(0), peer); second.process(chunk(1), peer)
            assertEquals("COMPLETED", acks.last().status)
            assertArrayEquals(data, File(history.values["incoming-restart"]!!.filePath!!).readBytes())
        } finally { restartedJob.cancelAndJoin(); clean() }
    }
    @Test fun `reopening without resuming does not extend the incoming recovery deadline`() = runBlocking {
        clean()
        val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, job, acks)
        first.process(init(), peer); first.process(chunk(0), peer); job.cancelAndJoin()
        val state = File(context.filesDir, "transfer_state/incoming.json")
        val expires = JSONObject(state.readText()).getJSONObject("incoming").getLong("expires")
        val secondJob = SupervisorJob(); val second = receiver(history, secondJob, acks)
        try {
            second.process(ProtocolMessage.FileCancel("unrelated"), peer)
            secondJob.cancelAndJoin()
            assertEquals(expires, JSONObject(state.readText()).getJSONObject("incoming").getLong("expires"))
        } finally { secondJob.cancelAndJoin(); clean() }
    }
    @Test fun `completed receipt survives receiver restart and avoids duplicate saved file`() = runBlocking {
        clean()
        val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, job, acks)
        first.process(init(), peer); first.process(chunk(0), peer); first.process(chunk(1), peer); job.cancelAndJoin()
        val secondJob = SupervisorJob(); val second = receiver(history, secondJob, acks)
        try {
            second.process(init().copy(resume = true), peer)
            assertEquals("COMPLETED", acks.last().status); assertEquals(data.size.toLong(), acks.last().receivedBytes)
            assertEquals(1, File(context.filesDir, "received_files").listFiles()!!.size)
        } finally { secondJob.cancelAndJoin(); clean() }
    }
    @Test fun `publication journal recovers verified file before rename and history commit`() = runBlocking {
        clean()
        val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, job, acks)
        first.process(init(), peer); first.process(chunk(0), peer); first.process(chunk(1), peer); job.cancelAndJoin()
        val item = history.values["incoming-restart"]!!
        val temporary = File(context.filesDir, "received_files/.incoming-journal.part")
        assertTrue(File(item.filePath!!).renameTo(temporary))
        val state = File(context.filesDir, "transfer_state/incoming.json")
        val saved = JSONObject(state.readText())
        saved.getJSONArray("receipts").getJSONObject(0).put("temporary", temporary.name)
        state.writeText(saved.toString()); history.values.clear()
        val secondJob = SupervisorJob(); val second = receiver(history, secondJob, acks)
        try {
            second.process(init().copy(resume = true), peer)
            assertEquals("COMPLETED", acks.last().status)
            assertArrayEquals(data, File(history.values["incoming-restart"]!!.filePath!!).readBytes())
        } finally { secondJob.cancelAndJoin(); clean() }
    }
    @Test fun `incoming corrupted expired malformed oversized and revoked checkpoints cannot resume`() = runBlocking {
        for (mode in listOf("corrupt", "expired", "malformed", "path", "oversized", "revoked", "identity")) {
            clean()
            val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
            val first = receiver(history, job, acks)
            first.process(init(), peer); first.process(chunk(0), peer); job.cancelAndJoin()
            val state = File(context.filesDir, "transfer_state/incoming.json")
            val saved = JSONObject(state.readText()); val partial = saved.getJSONObject("incoming")
            when (mode) {
                "corrupt" -> File(context.filesDir, "received_files").listFiles()!!.single().writeBytes(ByteArray(FILE_CHUNK_SIZE))
                "expired" -> partial.put("expires", 1)
                "path" -> partial.put("temporary", "../outside")
                "oversized" -> partial.put("size", MAX_FILE_BYTES + 1)
                "identity" -> partial.getJSONObject("peer").put("pin", "changed")
            }
            state.writeText(if (mode == "malformed") "{" else saved.toString())
            val secondJob = SupervisorJob()
            val second = FileReceivingManager(context, history, CoroutineScope(secondJob + Dispatchers.IO), { peer }, { m, _ -> acks.add(m as ProtocolMessage.FileAck); true },
                retain = { mode != "revoked" && it.device.fingerprint == peer.device.fingerprint })
            try {
                second.process(init().copy(resume = true), peer)
                assertEquals(mode, "REJECTED", acks.last().status)
                assertTrue(mode, File(context.filesDir, "received_files").listFiles().isNullOrEmpty())
                assertEquals(mode, TransferStatus.FAILED, history.values["incoming-restart"]!!.status)
            } finally { secondJob.cancelAndJoin(); clean() }
        }
    }
    @Test fun `completed receipt rejects a changed verified file after restart`() = runBlocking {
        clean()
        val history = TestFileHistory(); val job = SupervisorJob(); val acks = CopyOnWriteArrayList<ProtocolMessage.FileAck>()
        val first = receiver(history, job, acks)
        first.process(init(), peer); first.process(chunk(0), peer); first.process(chunk(1), peer); job.cancelAndJoin()
        File(history.values["incoming-restart"]!!.filePath!!).writeBytes(ByteArray(data.size))
        val secondJob = SupervisorJob(); val second = receiver(history, secondJob, acks)
        try {
            second.process(init().copy(resume = true), peer)
            assertNotEquals("COMPLETED", acks.last().status)
            assertEquals(1, File(context.filesDir, "received_files").listFiles()!!.size)
        } finally { secondJob.cancelAndJoin(); clean() }
    }
    private class Source(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("🌉 original.bin", file.length())) }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
    @Test fun `outgoing restart retains original snapshot and resumes from receiver offset`() = outgoingRestart("valid")
    @Test fun `sender exits during acknowledgement wait and recovers snapshot`() = outgoingRestart("active-exit")
    @Test fun `outgoing corrupt snapshot cannot restore`() = outgoingRestart("corrupt")
    @Test fun `outgoing revoked peer cannot restore`() = outgoingRestart("revoked")
    @Test fun `outgoing expired checkpoint cannot restore`() = outgoingRestart("expired")
    private fun outgoingRestart(mode: String) = runBlocking {
        clean()
        val source = File(context.cacheDir, "restart-source").apply { writeBytes(data) }
        val provider = Source(source)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "restart-source"; exported = true })
        ShadowContentResolver.registerProviderInternal("restart-source", provider)
        val history = TestFileHistory(); val firstJob = SupervisorJob(); val offered = CompletableDeferred<Unit>()
        lateinit var first: FileTransferManager
        first = FileTransferManager(context, history, CoroutineScope(firstJob + Dispatchers.IO), { peer }, { frame, _ ->
            if (frame is ProtocolMessage.FileInit) {
                offered.complete(Unit)
                if (mode != "active-exit") first.handleAck(ProtocolMessage.FileAck(frame.transferId, 0, "READY"), peer.device.id)
            }
            frame !is ProtocolMessage.FileChunk
        }, ackTimeoutMs = 100)
        assertTrue(first.sendFile(Uri.parse("content://restart-source/file")))
        offered.await()
        if (mode != "active-exit") withTimeout(5000) { first.busy.first { !it } }
        firstJob.cancelAndJoin()
        source.writeBytes(byteArrayOf(99))
        val item = history.values.values.single(); val state = File(context.filesDir, "transfer_state/outgoing.json")
        assertTrue(state.exists())
        if (mode == "corrupt") File(context.filesDir, "outgoing_transfers").listFiles()!!.single().writeBytes(ByteArray(data.size))
        if (mode == "expired") state.writeText(JSONObject(state.readText()).put("expires", 1).toString())
        val secondJob = SupervisorJob(); val chunks = CopyOnWriteArrayList<ProtocolMessage.FileChunk>()
        lateinit var second: FileTransferManager
        second = FileTransferManager(context, history, CoroutineScope(secondJob + Dispatchers.IO), { peer.copy(session = 2) }, { frame, _ ->
            when (frame) {
                is ProtocolMessage.FileInit -> { assertTrue(frame.resume); second.handleAck(ProtocolMessage.FileAck(frame.transferId, FILE_CHUNK_SIZE.toLong(), "READY"), peer.device.id) }
                is ProtocolMessage.FileChunk -> { chunks.add(frame); second.handleAck(ProtocolMessage.FileAck(frame.transferId, data.size.toLong(), "COMPLETED", hash), peer.device.id) }
                else -> {}
            }; true
        }, retain = { mode != "revoked" })
        try {
            val valid = mode in listOf("valid", "active-exit")
            assertEquals(mode, valid, second.resumeTransfer(item.transferId))
            if (valid) {
                withTimeout(5000) { second.busy.first { !it } }
                assertEquals(TransferStatus.COMPLETED, history.values[item.transferId]!!.status)
                assertEquals(FILE_CHUNK_SIZE.toLong(), chunks.single().offset)
                assertArrayEquals(data.copyOfRange(FILE_CHUNK_SIZE, data.size), android.util.Base64.decode(chunks.single().dataBase64, android.util.Base64.NO_WRAP))
            } else { assertTrue(chunks.isEmpty()); assertEquals(TransferStatus.FAILED, history.values[item.transferId]!!.status) }
            assertFalse(state.exists())
        } finally { secondJob.cancelAndJoin(); source.delete(); clean() }
    }
}
