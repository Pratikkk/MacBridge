package com.example.service

import android.app.Notification
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.MacBridgeApplication
import com.example.manager.notificationReplyTarget
import com.example.manager.validNotificationReply
import com.example.manager.notificationPayload
import com.example.manager.notificationStillDismissible
import com.example.manager.NotificationDestination
import com.example.manager.BridgeManager

/** Forwards only explicitly selected apps; never logs or persists notification content. */
class MacBridgeNotificationListener : NotificationListenerService() {
    private val bridge get() = (application as? MacBridgeApplication)?.bridgeManager
    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
        bridge?.clearNotificationMirrors()
        // Make current apps selectable, but never replay their existing notification content.
        runCatching { activeNotifications?.forEach { rememberApp(it) } }
    }
    override fun onListenerDisconnected() {
        if (active === this) active = null
        bridge?.clearNotificationMirrors()
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        if (active === this) active = null
        bridge?.clearNotificationMirrors()
        super.onDestroy()
    }
    private fun rememberApp(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        if (pkg != packageName) bridge?.rememberNotificationApp(pkg, label.take(100))
        return label
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.packageName == packageName) return
        val manager = bridge ?: return
        val name = rememberApp(sbn)
        if (!isPermissionGranted(this) || !manager.isAppMirroringEnabled(sbn.packageName)) return
        val n = sbn.notification
        val payload = notificationPayload(sbn.key, sbn.packageName, name,
            n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            (n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            n.visibility == Notification.VISIBILITY_SECRET, sbn.isOngoing,
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0, manager.notificationPreviewsEnabled())
        if (payload == null) {
            manager.notificationActions.remove(sbn.key); manager.notificationReplies.remove(sbn.key)
            manager.handleNotificationDismissedLocally(sbn.key)
        } else {
            val token = manager.notificationActions.observe(sbn.key, sbn.packageName, sbn.postTime, sbn.isClearable)
            val reply = if (manager.notificationPreviewsEnabled()) notificationReplyTarget(n, sbn.packageName) else null
            val replyToken = manager.notificationReplies.observe(sbn.key, sbn.packageName, sbn.postTime, reply != null, reply)
            manager.handleOutgoingNotification(payload.copy(dismissToken = token, replyToken = replyToken, hasReplyAction = replyToken != null))
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && sbn.packageName != packageName) {
            bridge?.notificationActions?.remove(sbn.key); bridge?.notificationReplies?.remove(sbn.key)
            bridge?.handleNotificationDismissedLocally(sbn.key)
        }
    }
    companion object {
        @Volatile private var active: MacBridgeNotificationListener? = null
        internal fun dismiss(manager: BridgeManager, key: String, token: String, target: NotificationDestination): String {
            val listener = active ?: return "UNAVAILABLE"
            if (listener.bridge !== manager || !isPermissionGranted(listener) || !target.allowed) return "DENIED"
            val record = manager.notificationActions.take(key, token, target) ?: return "STALE"
            if (!manager.isAppMirroringEnabled(record.pkg)) return "DENIED"
            return try {
                val sbn = listener.getActiveNotifications(arrayOf(key))?.singleOrNull() ?: return "STALE"
                val n = sbn.notification
                if (!notificationStillDismissible(record, sbn.packageName, sbn.postTime, sbn.isClearable,
                    n.visibility == Notification.VISIBILITY_SECRET, sbn.isOngoing, n.flags and Notification.FLAG_GROUP_SUMMARY != 0)) return "STALE"
                listener.cancelNotification(key)
                "REQUESTED" // Removal is confirmed by onNotificationRemoved, never assumed here.
            } catch (_: SecurityException) { "DENIED" }
              catch (_: RuntimeException) { "UNAVAILABLE" }
        }

        internal fun reply(manager: BridgeManager, key: String, token: String, target: NotificationDestination, text: String?): String {
            if (!validNotificationReply(text)) return "INVALID"
            val listener = active ?: return "UNAVAILABLE"
            if (listener.bridge !== manager || !isPermissionGranted(listener) || !target.allowed || !manager.notificationPreviewsEnabled()) return "DENIED"
            if (listener.getSystemService(KeyguardManager::class.java).isDeviceLocked) return "LOCKED"
            val record = manager.notificationReplies.take(key, token, target) ?: return "STALE"
            if (!manager.isAppMirroringEnabled(record.pkg)) return "DENIED"
            val original = record.reply ?: return "STALE"
            return try {
                val sbn = listener.getActiveNotifications(arrayOf(key))?.singleOrNull() ?: return "STALE"
                val n = sbn.notification
                if (record.pkg != sbn.packageName || record.posted != sbn.postTime || n.visibility == Notification.VISIBILITY_SECRET ||
                    sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return "STALE"
                val current = notificationReplyTarget(n, sbn.packageName) ?: return "STALE"
                if (!original.sameAs(current)) return "STALE"
                original.send(listener, text!!)
                "REQUESTED" // The source app owns actual message delivery; never assume recipient delivery.
            } catch (_: PendingIntent.CanceledException) { "UNAVAILABLE" }
              catch (_: SecurityException) { "DENIED" }
              catch (_: RuntimeException) { "UNAVAILABLE" }
        }

        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val own = ComponentName(context, MacBridgeNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}
