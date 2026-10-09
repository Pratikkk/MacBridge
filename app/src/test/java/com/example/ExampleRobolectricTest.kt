package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.IdentityManager
import com.example.crypto.SecurityBaselineAuditor
import com.example.network.ProtocolCodec
import com.example.model.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MacBridge", appName)
    }

    @Test
    fun `keystore identity generates valid SHA256 fingerprint`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identityManager = IdentityManager(context, allowSoftwareFallback = true)
        val fingerprint = identityManager.getFingerprint()

        assertNotNull(fingerprint)
        assertTrue("Fingerprint should be colon-separated SHA-256", fingerprint.contains(":"))
        assertEquals("SHA-256 has 32 bytes formatted as 32 hex pairs separated by 31 colons", 95, fingerprint.length)
    }

    @Test
    fun `protocol codec encodes and decodes hello frame`() {
        val hello = ProtocolMessage.Hello(
            deviceId = "test_dev_01",
            deviceName = "Pixel 8",
            fingerprint = "AA:BB:CC:DD"
        )
        val encoded = ProtocolCodec.encode(hello)
        val decoded = ProtocolCodec.decode(encoded) as? ProtocolMessage.Hello

        assertNotNull(decoded)
        assertEquals("test_dev_01", decoded?.deviceId)
        assertEquals("Pixel 8", decoded?.deviceName)
        assertEquals("AA:BB:CC:DD", decoded?.fingerprint)
    }

    @Test
    fun `security baseline reports incomplete controls honestly`() {
        val rules = SecurityBaselineAuditor.getAuditRules()
        assertEquals(8, rules.size)
        assertTrue("Incomplete controls must be visible", rules.any { !it.isCompliant })
    }
}
