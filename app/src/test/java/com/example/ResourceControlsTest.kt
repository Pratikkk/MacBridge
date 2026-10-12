package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MacBridgeDatabase
import com.example.manager.*
import com.example.model.*
import com.example.network.WorkBudget
import com.example.network.PeerRateLimit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResourceControlsTest {
    @Test fun `actual database bounds clipboard and terminal histories and retains actionable files`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MacBridgeDatabase::class.java).build()
        try {
            repeat(120) { db.clipboardDao().insert(ClipboardItem(content = "世界 $it", sourceDeviceName = "Fixture", timestamp = it.toLong())) }
            assertEquals(100, db.clipboardDao().getAllClips().first().size)
            db.clipboardDao().insertRaw(ClipboardItem(content = "世".repeat(22000), sourceDeviceName = "Legacy", timestamp = 999))
            db.clipboardDao().prune()
            assertEquals(100, db.clipboardDao().getAllClips().first().size)
            assertTrue(db.clipboardDao().getAllClips().first().all { it.content.toByteArray().size <= 65536 })
            val file = FileTransferItem("retained", "🌉", 1, direction = TransferDirection.INCOMING, status = TransferStatus.COMPLETED, sha256Checksum = "hash", filePath = "private-fixture", timestamp = 0)
            val paused = file.copy(transferId = "paused", filePath = null, status = TransferStatus.PAUSED)
            db.fileTransferDao().insertOrUpdate(file); db.fileTransferDao().insertOrUpdate(paused)
            repeat(230) { db.fileTransferDao().insertOrUpdate(file.copy(transferId = "old$it", filePath = null, timestamp = it.toLong())) }
            assertEquals(202, db.fileTransferDao().getAllTransfers().first().size)
            assertNotNull(db.fileTransferDao().getTransfer("retained")); assertNotNull(db.fileTransferDao().getTransfer("paused"))
            assertNull(db.fileTransferDao().getTransfer("old0"))
            repeat(70) { db.notificationDao().insert(MirroredNotification("n$it", "fixture", "Fixture", "", "", timestamp = it.toLong())) }
            val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM mirrored_notifications").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(50, count)
        } finally { db.close() }
    }

    @Test fun `file count quota handles empty files and setting survives restart without deleting files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = StoragePolicy(context)
        policy.folder.mkdirs()
        try {
            repeat(100) { File(policy.folder, "$it.verified").createNewFile() }
            try { policy.requireSpace(0, Long.MAX_VALUE); fail("expected quota") } catch (_: StorageFull) {}
            policy.setLimit(RECEIVE_QUOTAS.first())
            assertEquals(RECEIVE_QUOTAS.first(), StoragePolicy(context).limit)
            assertEquals(100, policy.usage().files)
            try { policy.setLimit(1); fail("unsupported quota") } catch (_: IllegalArgumentException) {}
            File(policy.folder, "0.verified").delete()
            try { policy.requireSpace(1, 0); fail("near full disk") } catch (_: StorageFull) {}
            policy.requireSpace(1, Long.MAX_VALUE)
        } finally { policy.folder.deleteRecursively(); policy.setLimit(RECEIVE_QUOTAS[1]) }
    }

    @Test fun `clipboard notification and byte budgets bound work across reconnect and refill`() {
        var now = 0L
        val budget = WorkBudget { now }
        repeat(10) { budget.message("CLIPBOARD") }
        repeat(2) { try { budget.message("CLIPBOARD"); fail("flood") } catch (_: PeerRateLimit) {} }
        now += 500_000_000; budget.message("CLIPBOARD")
        repeat(60) { budget.message("NOTIFICATION") }
        try { budget.message("NOTIFICATION"); fail("flood") } catch (_: PeerRateLimit) {}
        repeat(32) { budget.frame(1024 * 1024) }
        try { budget.frame(1); fail("byte flood") } catch (_: PeerRateLimit) {}
        now += 3_000_000_000
        repeat(2000) { now += 10_000_000; budget.frame(90000); budget.message("FILE_CHUNK"); budget.message("FILE_CANCEL") }
        budget.message("HEARTBEAT")
    }
}
