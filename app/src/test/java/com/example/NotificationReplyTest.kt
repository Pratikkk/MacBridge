package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.*
import androidx.test.core.app.ApplicationProvider
import com.example.manager.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class NotificationReplyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun intent(id: Int = 1, immutable: Boolean = false, activity: Boolean = false): PendingIntent {
        val flags = if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
        val value = Intent("com.example.TEST_REPLY_$id").setPackage(context.packageName)
        return if (activity) PendingIntent.getActivity(context, id, value, flags) else PendingIntent.getBroadcast(context, id, value, flags)
    }
    private fun action(pi: PendingIntent = intent(), free: Boolean = true, auth: Boolean = false, key: String = "reply"): Notification.Action =
        Notification.Action.Builder(null, "Reply", pi).addRemoteInput(RemoteInput.Builder(key).setAllowFreeFormInput(free).build())
            .setAuthenticationRequired(auth).build()
    private fun notification(vararg actions: Notification.Action) = Notification.Builder(context, "test").apply { actions.forEach { addAction(it) } }.build()

    @Test fun `reply capability rejects unsafe ambiguous and unsupported actions`() {
        assertNull(notificationReplyTarget(notification(), context.packageName))
        assertNotNull(notificationReplyTarget(notification(action()), context.packageName))
        assertNull(notificationReplyTarget(notification(action(free=false)), context.packageName))
        assertNull(notificationReplyTarget(notification(action(auth=true)), context.packageName))
        assertNull(notificationReplyTarget(notification(action(pi=intent(2, immutable=true))), context.packageName))
        assertNull(notificationReplyTarget(notification(action(pi=intent(3, activity=true))), context.packageName))
        assertNull(notificationReplyTarget(notification(action()), "com.other.app"))
        assertNull(notificationReplyTarget(notification(action(), action(pi=intent(4))), context.packageName))
        assertNull(notificationReplyTarget(notification(action(key=" ")), context.packageName))
    }
    @Test fun `generation retains original action and rejects substituted app capability`() {
        val original = notificationReplyTarget(notification(action()), context.packageName)!!
        assertTrue(original.sameAs(notificationReplyTarget(notification(action()), context.packageName)!!))
        assertFalse(original.sameAs(notificationReplyTarget(notification(action(pi=intent(5))), context.packageName)!!))
        assertFalse(original.sameAs(notificationReplyTarget(notification(action(key="new")), context.packageName)!!))
        val registry = NotificationActions()
        val target = NotificationDestination("mac", "pin", 1, true)
        val token = registry.observe("key", context.packageName, 100, true, original)!!
        registry.bind("key", token, target)
        assertNull(registry.take("key", token, target.copy(session=2)))
        assertNull(registry.take("key", token, target.copy(allowed=false)))
        assertEquals(original, registry.take("key", token, target)?.reply)
        assertNull(registry.take("key", token, target))
    }
    @Test fun `Unicode reply reaches original pending intent through RemoteInput and cancellation fails`() {
        var received: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString() }
        }
        context.registerReceiver(receiver, IntentFilter("com.example.TEST_REPLY_7"))
        try {
            val pi = intent(7)
            val reply = notificationReplyTarget(notification(action(pi)), context.packageName)!!
            reply.send(context, "Hello 世界 🌉\nThanks")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Hello 世界 🌉\nThanks", received)
            pi.cancel()
            assertThrows(PendingIntent.CanceledException::class.java) { reply.send(context, "again") }
        } finally { context.unregisterReceiver(receiver) }
    }
    @Test fun `reply text bounds account for Unicode blank controls and broken surrogates`() {
        for (text in listOf(null, "", " \n\t", "bad\u0000", "bad\u007f", "\uD800", "\uDC00", "🌉".repeat(1025))) assertFalse(validNotificationReply(text))
        assertTrue(validNotificationReply("🌉".repeat(1024)))
        assertTrue(validNotificationReply("Hi 世界\nThanks\t!"))
    }
    @Test @Config(sdk = [24]) fun `older Android without capability inspection offers no reply`() {
        val pi = PendingIntent.getBroadcast(context, 9, Intent("old"), 0)
        val a = Notification.Action.Builder(null, "Reply", pi).addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val n = Notification.Builder(context).addAction(a).build()
        assertNull(notificationReplyTarget(n, context.packageName))
    }
}
