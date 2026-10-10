package com.example

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.example.manager.MAX_FILE_BYTES
import com.example.sharing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareSheetTest {
    private val uri = Uri.parse("content://share-tests/🌉 document")
    private fun fileIntent() = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun `stream and clip-only files are reviewed instead of sending captions`() {
        assertEquals(SharedContent.File(uri), parseSharedContent(fileIntent().putExtra(Intent.EXTRA_TEXT, "caption")))
        val clipOnly = Intent(Intent.ACTION_SEND).setType("image/png").apply { clipData = ClipData.newRawUri("file", uri) }
        assertEquals(SharedContent.File(uri), parseSharedContent(clipOnly))
        assertEquals(SharedContent.File(uri), parseSharedContent(fileIntent().apply { clipData = ClipData.newRawUri("file", uri) }))
        assertEquals(SharedContent.Text("🌉 hello\n"), parseSharedContent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "🌉 hello\n")))
        assertEquals(SharedContent.Ignored, parseSharedContent(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `malformed unsafe ambiguous and multi-file shares are rejected`() {
        val unsafe = listOf("file:///private/test.txt", "https://example.com/file", "content:///missing-authority", "content://share-tests/" + "x".repeat(8200))
        for (value in unsafe) assertTrue(parseSharedContent(fileIntent().putExtra(Intent.EXTRA_STREAM, Uri.parse(value))) is SharedContent.Rejected)
        assertTrue(parseSharedContent(fileIntent().putExtra(Intent.EXTRA_STREAM, "wrong type")) is SharedContent.Rejected)
        assertTrue(parseSharedContent(fileIntent().apply { clipData = ClipData.newRawUri("file", Uri.parse("content://share-tests/other")) }) is SharedContent.Rejected)
        assertTrue(parseSharedContent(fileIntent().apply { clipData = ClipData.newRawUri("files", uri).apply { addItem(ClipData.Item(uri)) } }) is SharedContent.Rejected)
        assertEquals(SharedContent.Rejected("Share one file at a time."), parseSharedContent(Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertTrue(parseSharedContent(Intent(Intent.ACTION_SEND).setType("application/pdf")) is SharedContent.Rejected)
    }

    @Test fun `pending review survives recreation without duplicate or replacement delivery`() {
        var now = 100L
        val inbox = ShareInbox { now }
        assertTrue(inbox.accept(uri))
        assertFalse(inbox.accept(uri))
        assertFalse(inbox.accept(Uri.parse("content://share-tests/other")))
        val saved = Bundle().also(inbox::save)
        val recreated = ShareInbox { now }.also { it.restore(saved) }
        assertEquals(uri, recreated.pending)
        assertFalse(recreated.accept(uri))
        recreated.dismiss()
        assertNull(recreated.pending)
        assertTrue(recreated.accept(uri)) // Cancellation does not prevent an immediate retry.
        now += 10_000 // Reviewing for a while must not defeat the guard after sending.
        recreated.sent()
        assertFalse(recreated.accept(uri))
        now += 2_001
        assertTrue(recreated.accept(uri)) // A later deliberate share of the same document is valid.
        recreated.sent()
        val consumed = Bundle().also(recreated::save)
        assertNull(ShareInbox { now }.also { it.restore(consumed) }.pending)
    }

    private class MetadataProvider(val size: Long?, val denied: Boolean) : ContentProvider() {
        var onMain: Boolean? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            if (denied) throw SecurityException("private provider path")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>("../🌉 notes.txt\n", size)) }
        }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun `preview reads metadata off main and handles unknown oversized and revoked access`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((size, denied) in listOf(null to false, -1L to false, 42L to false, MAX_FILE_BYTES + 1 to false, 42L to true)) {
            val provider = MetadataProvider(size, denied)
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "share-tests"; exported = true })
            ShadowContentResolver.registerProviderInternal("share-tests", provider)
            val result = inspectSharedDocument(context, uri)
            assertEquals(false, provider.onMain)
            if (denied || (size ?: 0) > MAX_FILE_BYTES) {
                assertNotNull(result.error)
                assertFalse(result.error!!.contains("private provider"))
            } else {
                assertEquals("🌉 notes.txt", result.name)
                assertEquals(size?.takeIf { it >= 0 }, result.size)
                assertNull(result.error)
            }
        }
    }
}
