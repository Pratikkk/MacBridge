package com.example

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.example.model.*
import com.example.ui.screens.files.FileTransferCard
import com.example.ui.screens.files.rememberFileTransferActions
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicReference

/** Exercise the actual shared Home/Files picker callback with the app's Default scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileSaveFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private class Picker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var request = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
        }
    }
    private val item = FileTransferItem("incoming-feedback", "🌉 received.bin", 1, 1,
        TransferDirection.INCOMING, TransferStatus.COMPLETED, "hash", calculatedChecksum = "hash", filePath = "/verified")

    @Before fun resetFeedback() { ShadowToast.reset() }

    private fun saveScenario(success: Boolean) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val picker = Picker()
        val entryLooper = AtomicReference<Looper>()
        val ioThread = AtomicReference<Thread>()
        val completionLooper = AtomicReference<Looper>()
        lateinit var activity: Activity
        try {
            compose.setContent { MyApplicationTheme {
                activity = LocalContext.current as Activity
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    val actions = rememberFileTransferActions(scope, saveFile = { id, uri ->
                        assertEquals(item.transferId, id)
                        assertEquals(Uri.parse("content://save-feedback/result"), uri)
                        entryLooper.set(Looper.myLooper())
                        val result = withContext(Dispatchers.IO) { ioThread.set(Thread.currentThread()); success }
                        completionLooper.set(Looper.myLooper())
                        result
                    }, resumeFile = { false }, cancelFile = {})
                    FileTransferCard(item, actions.savingId == item.transferId, { actions.save(item) }, {},
                        canSave = !actions.savePickerOpen && actions.savingId == null)
                }
            } }
            compose.onNodeWithText("Save As…").performClick().assertIsNotEnabled()
            compose.runOnIdle { picker.dispatchResult(picker.request, Uri.parse("content://save-feedback/result")) }
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                ShadowToast.shownToastCount() == 1
            }
            compose.onNodeWithText("Save As…").assertIsEnabled()
            compose.runOnIdle {
                assertSame("Completion must return to the UI dispatcher", Looper.getMainLooper(), entryLooper.get())
                assertSame(Looper.getMainLooper(), completionLooper.get())
                assertNotSame(Looper.getMainLooper().thread, ioThread.get())
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertEquals(if (success) "File saved" else "Could not save. Your verified copy is still here; try Save As again.", ShadowToast.getTextOfLatestToast())
            }
        } finally { scope.cancel() }
    }

    @Test fun `successful Save As from default app scope shows feedback and keeps activity open`() = saveScenario(true)
    @Test fun `failed Save As from default app scope shows feedback and allows retry`() = saveScenario(false)

    @Test fun `cancelling document picker does not save or show feedback`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val picker = Picker()
        try {
            compose.setContent { MyApplicationTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    val actions = rememberFileTransferActions(scope, saveFile = { _, _ -> fail("Cancelled picker cannot save"); false },
                        resumeFile = { false }, cancelFile = {})
                    FileTransferCard(item, false, { actions.save(item) }, {}, canSave = !actions.savePickerOpen)
                }
            } }
            compose.onNodeWithText("Save As…").performClick()
            compose.runOnIdle { picker.dispatchResult<Uri?>(picker.request, null) }
            compose.onNodeWithText("Save As…").assertIsEnabled()
            compose.runOnIdle { assertEquals(0, ShadowToast.shownToastCount()) }
        } finally { scope.cancel() }
    }

    @Test fun `failed Resume uses main dispatcher for user feedback`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val looper = AtomicReference<Looper>()
        try {
            compose.setContent { MyApplicationTheme {
                val actions = rememberFileTransferActions(scope, saveFile = { _, _ -> false },
                    resumeFile = { looper.set(Looper.myLooper()); false }, cancelFile = {})
                FileTransferCard(item.copy(direction = TransferDirection.OUTGOING, status = TransferStatus.PAUSED), false, {}, {},
                    canResume = true, onResume = { actions.resume(item) })
            } }
            compose.onNodeWithText("Resume transfer").performClick()
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                ShadowToast.shownToastCount() == 1
            }
            compose.runOnIdle {
                assertSame(Looper.getMainLooper(), looper.get())
                assertEquals("Reconnect the original paired Mac to resume.", ShadowToast.getTextOfLatestToast())
            }
        } finally { scope.cancel() }
    }
}
