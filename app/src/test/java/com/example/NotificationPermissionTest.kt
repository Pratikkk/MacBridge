package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.service.MacBridgeNotificationListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPermissionTest {
    @Test fun `listener permission requires exact component and notices revocation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, MacBridgeNotificationListener::class.java)
        try {
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString() + "Impostor")
            assertFalse(MacBridgeNotificationListener.isPermissionGranted(context))
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "another.app/.Listener:" + component.flattenToShortString())
            assertTrue(MacBridgeNotificationListener.isPermissionGranted(context))
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
            assertFalse(MacBridgeNotificationListener.isPermissionGranted(context))
        } finally { Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "") }
    }
}
