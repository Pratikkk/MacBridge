package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClipboardDao
import com.example.manager.ClipboardSyncManager
import com.example.manager.TextSendResult
import com.example.model.ClipboardItem
import com.example.model.ProtocolMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardSharingTest {
    private class History : ClipboardDao {
        val items = mutableListOf<ClipboardItem>()
        override fun getAllClips(): Flow<List<ClipboardItem>> = flowOf(items)
        override suspend fun insert(item: ClipboardItem): Long {
            items.add(item)
            return items.size.toLong()
        }
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun clearAll() { items.clear() }
    }

    @Test
    fun `shared text is sent exactly without replacing or reading clipboard`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "Unrelated clipboard text"))
        val history = History()
        val sent = mutableListOf<ProtocolMessage>()
        val text = "  Shared text 🌉\nwith a second line  "

        runBlocking {
            val manager = ClipboardSyncManager(context, history, this) { sent.add(it); true }
            assertEquals(TextSendResult.SENT, manager.sendTextToMac(text, "My Mac"))
        }

        assertEquals(text, (sent.single() as ProtocolMessage.ClipboardSync).content)
        assertEquals("Unrelated clipboard text", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(text, history.items.single().content)
        assertEquals("Sent to My Mac", history.items.single().sourceDeviceName)
        assertTrue(history.items.single().isOutgoing)
    }

    @Test
    fun `failed send creates no successful history entry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = History()
        runBlocking {
            val manager = ClipboardSyncManager(context, history, this) { false }
            assertEquals(TextSendResult.SEND_FAILED, manager.sendTextToMac("Shared text", "Mac"))
        }
        assertTrue(history.items.isEmpty())
    }

    @Test
    fun `empty shared text never sends a frame`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = History()
        var sends = 0
        runBlocking {
            val manager = ClipboardSyncManager(context, history, this) { sends++; true }
            for (text in listOf(null, "", " \n\t")) {
                assertEquals(TextSendResult.EMPTY_TEXT, manager.sendTextToMac(text, "Mac"))
            }
        }
        assertEquals(0, sends)
        assertTrue(history.items.isEmpty())
    }

    @Test
    fun `manual clipboard push still sends current clipboard text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "Current clipboard"))
        val history = History()
        val sent = mutableListOf<ProtocolMessage>()
        runBlocking {
            val manager = ClipboardSyncManager(context, history, this) { sent.add(it); true }
            assertTrue(manager.pushCurrentClipboardToMac("Mac"))
        }
        assertEquals("Current clipboard", (sent.single() as ProtocolMessage.ClipboardSync).content)
        assertEquals("Current clipboard", history.items.single().content)
    }
}
