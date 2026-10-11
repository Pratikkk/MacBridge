package com.example

import android.content.Intent
import com.example.crypto.IdentityStartup
import com.example.service.MacBridgeForegroundService
import com.example.service.MacBridgeNotificationListener
import com.example.service.MacBridgeTileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MacBridgeApplication::class)
class IdentityRecoveryApplicationTest {
    @Test fun `real application and exported entry points survive unavailable Keystore without a bridge`() = runBlocking {
        // Robolectric has no AndroidKeyStore provider; no software fallback is enabled.
        val app = RuntimeEnvironment.getApplication() as MacBridgeApplication
        withTimeout(5000) { app.identityStartup.first { it is IdentityStartup.Recovery } }
        assertNull(app.bridgeManager)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "PRIVATE_FIXTURE_CONTENT"))
        assertNull(app.bridgeManager)
        val foreground = Robolectric.buildService(MacBridgeForegroundService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY, foreground.get().onStartCommand(Intent(), 0, 1))
        val tile = Robolectric.buildService(MacBridgeTileService::class.java).create()
        tile.get().onStartListening(); tile.get().onClick()
        val listener = Robolectric.buildService(MacBridgeNotificationListener::class.java).create()
        listener.get().onListenerConnected(); listener.get().onListenerDisconnected()
        listener.destroy(); tile.get().onStopListening()
        // Robolectric's ShadowTileService is not a ShadowService, so its inherited
        // destroy callback fails inside the framework before app code can run.
        foreground.destroy(); activity.pause().stop().destroy()
        app.applicationScope.cancel()
    }
}
