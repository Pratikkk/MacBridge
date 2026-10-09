package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.MacBridgeApplication
import com.example.manager.DiagnosticLogger

/**
 * Phase 3: Android Foreground Service with ongoing persistent notification.
 * Keeps local network socket connection alive and catches dead links.
 */
class MacBridgeForegroundService : Service {

    constructor() : super()

    private val TAG = "ForegroundService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "Mac"

        when (action) {
            ACTION_STOP -> {
                DiagnosticLogger.i(TAG, "Stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_CLIPBOARD -> {
                val app = application as? MacBridgeApplication
                app?.bridgeManager?.pushClipboard()
            }
            else -> {
                val notification = buildForegroundNotification(deviceName)
                startForeground(NOTIFICATION_ID, notification)
                DiagnosticLogger.i(TAG, "Foreground service started for $deviceName")
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MacBridge Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains encrypted link to paired Mac"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(deviceName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val syncIntent = Intent(this, MacBridgeForegroundService::class.java).apply {
            action = ACTION_SYNC_CLIPBOARD
        }
        val syncPendingIntent = PendingIntent.getService(
            this,
            1,
            syncIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MacBridge Active")
            .setContentText("Connected to $deviceName • Pinned TLS 1.3")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_share, "Sync Clipboard", syncPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "macbridge_link_channel"
        const val NOTIFICATION_ID = 8991
        const val ACTION_START = "com.example.START_SERVICE"
        const val ACTION_STOP = "com.example.STOP_SERVICE"
        const val ACTION_SYNC_CLIPBOARD = "com.example.ACTION_SYNC_CLIPBOARD"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, MacBridgeForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MacBridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
