package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.IdentityManager
import com.example.network.PairingCode
import com.example.network.PairingFailure
import com.example.network.WireFrames
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecurityProtocolTest {
    @Test
    fun `connection timeout explains the Mac endpoint and recovery steps`() {
        val message = PairingFailure.message(java.net.SocketTimeoutException("raw socket error"), "192.168.0.100:8990")
        assertTrue(message.contains("192.168.0.100:8990"))
        assertTrue(message.contains("companion running"))
        assertTrue(message.contains("same local network"))
        assertFalse(message.contains("raw socket error"))
    }

    @Test
    fun `oversized unterminated frames are rejected before JSON parsing`() {
        try {
            WireFrames.read(ByteArrayInputStream(ByteArray(WireFrames.MAX_BYTES + 1) { 65 }))
            fail("Oversized frame must be rejected")
        } catch (_: SecurityException) { }
    }

    @Test
    fun `UTF8 byte limits and roundtrip preserve text`() {
        val stream = ByteArrayOutputStream()
        WireFrames.write(stream, "Mac 🌉")
        assertEquals("Mac 🌉", WireFrames.read(ByteArrayInputStream(stream.toByteArray())))
        try {
            WireFrames.write(stream, "🌉".repeat(WireFrames.MAX_BYTES / 4 + 1))
            fail("UTF-8 byte limit must be enforced")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `malformed UTF8 is rejected`() {
        try {
            WireFrames.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 10)))
            fail("Invalid UTF-8 must be rejected")
        } catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test
    fun `legacy and manufactured discovery pairing codes are rejected`() {
        for (code in listOf("macbridge://pair?id=demo&secret=000000", "https://example.com", "macbridge://pair?v=2&fingerprint=UNKNOWN")) {
            try { PairingCode.parse(code); fail("Invalid pairing code accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `Android identity fails closed without Keystore by default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try { IdentityManager(context); fail("Software fallback must be explicitly enabled for tests") }
        catch (_: IllegalStateException) { }
        assertEquals(64, IdentityManager(context, true).generateOneTimePairingSecret().length)
    }
}
