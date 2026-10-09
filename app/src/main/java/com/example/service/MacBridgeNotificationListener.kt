package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.MacBridgeApplication
import com.example.manager.DiagnosticLogger
import com.example.model.ProtocolMessage

/**
 * Phase 6: Notification Mirroring.
 * Forwards notifications to Mac as native macOS banners.
 * Handles bidirectional dismiss synchronization and reply actions.
 */
class MacBridgeNotificationListener : NotificationListenerService() {

    private val TAG = "NotificationListener"

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagnosticLogger.i(TAG, "Notification listener connected & active")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // Do not mirror self or ongoing foreground service notifications
        if (pkg == packageName) return
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && text.isBlank()) return

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        val hasReply = hasInlineReply(sbn.notification)

        val app = application as? MacBridgeApplication ?: return
        if (!app.bridgeManager.isAppMirroringEnabled(pkg)) {
            return
        }

        val mirrorMsg = ProtocolMessage.NotificationMirror(
            notificationId = sbn.key,
            packageName = pkg,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            hasReplyAction = hasReply
        )

        app.bridgeManager.handleOutgoingNotification(mirrorMsg)
        DiagnosticLogger.d(TAG, "Mirrored notification from $appName: \"$title\"")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null || sbn.packageName == packageName) return
        val app = application as? MacBridgeApplication ?: return
        app.bridgeManager.handleNotificationDismissedLocally(sbn.key)
    }

    fun dismissNotificationByKey(key: String) {
        try {
            cancelNotification(key)
            DiagnosticLogger.i(TAG, "Dismissed local notification: $key by remote Mac command")
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "Failed to cancel notification: ${e.message}")
        }
    }

    private fun hasInlineReply(notification: Notification): Boolean {
        val actions = notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    companion object {
        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, MacBridgeNotificationListener::class.java).flattenToString()
            return enabledListeners.contains(myComponent)
        }
    }
}
