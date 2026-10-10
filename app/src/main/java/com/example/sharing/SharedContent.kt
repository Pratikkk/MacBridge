package com.example.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.manager.MAX_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal sealed interface SharedContent {
    data class File(val uri: Uri) : SharedContent
    data class Text(val text: String?) : SharedContent
    data class Rejected(val message: String) : SharedContent
    data object Ignored : SharedContent
}

/** An attachment takes precedence over its caption; never silently send a different payload. */
internal fun parseSharedContent(intent: Intent?): SharedContent {
    if (intent?.action == Intent.ACTION_SEND_MULTIPLE) return SharedContent.Rejected("Share one file at a time.")
    if (intent?.action != Intent.ACTION_SEND) return SharedContent.Ignored
    return try {
        val clip = intent.clipData
        if ((clip?.itemCount ?: 0) > 1) return SharedContent.Rejected("Share one file at a time.")
        val extra = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (intent.hasExtra(Intent.EXTRA_STREAM) && extra == null) return SharedContent.Rejected("Cannot open this file. Share it again from the source app.")
        val clipped = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        if (extra != null && clipped != null && extra != clipped) return SharedContent.Rejected("Share one file at a time.")
        val uri = extra ?: clipped
        if (uri != null) {
            if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 8192)
                SharedContent.Rejected("Share a document from an app that grants file access.")
            else SharedContent.File(uri)
        } else if (intent.type?.let { !it.startsWith("text/") } == true) {
            SharedContent.Rejected("No file attached. Share it again from the source app.")
        } else SharedContent.Text(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    } catch (_: RuntimeException) {
        SharedContent.Rejected("Cannot open this share. Try sharing it again from the source app.")
    }
}

/** Restores the pending review without replaying the original launch intent after rotation. */
internal class ShareInbox(private val now: () -> Long = SystemClock::elapsedRealtime) {
    var pending by mutableStateOf<Uri?>(null)
        private set
    private var lastUri: String? = null
    private var lastDelivery = Long.MIN_VALUE

    fun accept(uri: Uri): Boolean {
        val time = now()
        if (pending == uri || (lastUri == uri.toString() && time >= lastDelivery && time - lastDelivery < 2_000)) return false
        if (pending != null) return false // Keep the file the user is already reviewing.
        pending = uri
        lastUri = uri.toString()
        lastDelivery = time
        return true
    }

    fun dismiss() { pending = null; lastUri = null }
    fun sent() { pending = null; lastDelivery = now() }
    fun save(out: Bundle) {
        out.putString("share.pending", pending?.toString())
        out.putString("share.lastUri", lastUri)
        out.putLong("share.lastDelivery", lastDelivery)
    }
    fun restore(saved: Bundle) {
        pending = saved.getString("share.pending")?.let(Uri::parse)
        lastUri = saved.getString("share.lastUri")
        lastDelivery = saved.getLong("share.lastDelivery", Long.MIN_VALUE)
    }
}

internal data class SharedDocumentInfo(val name: String = "Shared file", val size: Long? = null, val error: String? = null)

internal suspend fun inspectSharedDocument(context: Context, uri: Uri): SharedDocumentInfo = withContext(Dispatchers.IO) {
    try {
        var name = "Shared file"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    .replace('\\', '/').substringAfterLast('/').filterNot { it.isISOControl() }.take(100).ifBlank { "Shared file" }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        SharedDocumentInfo(name, size, if ((size ?: 0) > MAX_FILE_BYTES) "Files must be 100 MB or smaller." else null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SharedDocumentInfo(error = "File access is unavailable. Share it again from the source app.")
    }
}
