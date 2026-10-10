package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.MacBridgeApplication
import com.example.manager.notificationPayload

/** Forwards only explicitly selected apps; never logs or persists notification content. */
class MacBridgeNotificationListener : NotificationListenerService() {
    private val bridge get() = (application as? MacBridgeApplication)?.bridgeManager
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Make current apps selectable, but never replay their existing notification content.
        runCatching { activeNotifications?.forEach { rememberApp(it) } }
    }
    override fun onListenerDisconnected() {
        bridge?.clearNotificationMirrors()
        super.onListenerDisconnected()
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
        if (payload == null) manager.handleNotificationDismissedLocally(sbn.key)
        else manager.handleOutgoingNotification(payload)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && sbn.packageName != packageName) bridge?.handleNotificationDismissedLocally(sbn.key)
    }
    companion object {
        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val own = ComponentName(context, MacBridgeNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}
