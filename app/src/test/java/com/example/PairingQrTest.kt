package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.network.PairingCode
import com.example.network.PairingScanResult
import com.example.ui.screens.devices.PairWithMacQrCard
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PairingQrTest {
    @get:Rule val compose = createComposeRule()
    private val code = "macbridge://pair?v=2&id=mac-test&name=Mac%20%F0%9F%8C%89&ip=192.168.0.100&port=8990&fingerprint=" +
        List(32) { "AB" }.joinToString(":") + "&secret=" + "a".repeat(64)

    @Test fun `scan stages Unicode identity without automatically pairing`() {
        var input = ""
        var pairs = 0
        compose.setContent {
            var value by remember { mutableStateOf(input) }
            PairWithMacQrCard(value, { value = it; input = it }, { pairs++ }, null, true,
                scanCode = { PairingScanResult.fromRaw(code) })
        }
        compose.onNodeWithTag("confirm_pair_button").assertIsNotEnabled()
        compose.onNodeWithTag("scan_mac_qr_button").performClick()
        compose.onNodeWithText("Mac 🌉 • 192.168.0.100:8990").assertExists()
        compose.onNodeWithTag("pairing_identity_preview").assertExists()
        compose.runOnIdle { assertEquals(code, input); assertEquals(0, pairs) }
        compose.onNodeWithTag("confirm_pair_button").performClick()
        compose.runOnIdle { assertEquals(1, pairs) }
    }

    private fun assertPreserved(result: PairingScanResult, message: String) {
        var input = code
        compose.setContent {
            PairWithMacQrCard(input, { input = it }, { fail("Must not pair automatically") }, null, true,
                scanCode = { result })
        }
        compose.onNodeWithTag("scan_mac_qr_button").performClick()
        compose.onNodeWithTag("scan_status").assertTextContains(message, substring = true)
        compose.onNodeWithTag("scan_mac_qr_button").assertIsEnabled()
        compose.runOnIdle { assertEquals(code, input) }
    }

    @Test fun `cancelled scan preserves pasted code`() = assertPreserved(PairingScanResult.Cancelled, "Scan cancelled")
    @Test fun `wrong QR preserves pasted code and offers recovery`() = assertPreserved(PairingScanResult.fromRaw("https://example.org"), "not a valid")
    @Test fun `missing services preserves paste fallback`() = assertPreserved(PairingScanResult.Unavailable, "paste the Mac code")

    @Test fun `pending scan blocks duplicate scans and pairing`() {
        val result = CompletableDeferred<PairingScanResult>()
        var calls = 0
        compose.setContent {
            PairWithMacQrCard(code, {}, {}, null, true, scanCode = { calls++; result.await() })
        }
        compose.onNodeWithTag("scan_mac_qr_button").performClick()
        compose.onNodeWithTag("scan_mac_qr_button").assertIsNotEnabled()
        compose.onNodeWithTag("confirm_pair_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); result.complete(PairingScanResult.Cancelled) }
        compose.onNodeWithTag("scan_mac_qr_button").assertIsEnabled()
    }

    @Test fun `leaving pairing discards late camera result`() {
        val result = CompletableDeferred<PairingScanResult>()
        var visible by mutableStateOf(true)
        var changes = 0
        compose.setContent {
            if (visible) PairWithMacQrCard("", { changes++ }, {}, null, true, scanCode = { result.await() })
        }
        compose.onNodeWithTag("scan_mac_qr_button").performClick()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { result.complete(PairingScanResult.Code(code)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun `empty oversized and malformed QR payloads fail safely`() {
        for (raw in listOf(null, "", "a".repeat(4097), code.replace("port=8990", "port=65536"),
            code.replace("secret=" + "a".repeat(64), "secret=short"), code.replace("ip=192.168.0.100", "ip=bad%2Fhost"))) {
            assertEquals(PairingScanResult.Invalid, PairingScanResult.fromRaw(raw))
        }
    }

    @Test fun `ambiguous authority and duplicate fields are rejected`() {
        for (raw in listOf(code + "&secret=" + "b".repeat(64), code + "&v=1", code + "#fragment",
            code.replace("//pair", "//user@pair"), code.replace("//pair", "//pair:8990"),
            code.replace("//pair?", "//pair/path?"), code.replace("name=Mac%20%F0%9F%8C%89", "name=Mac%0Aevil"))) {
            assertEquals(PairingScanResult.Invalid, PairingScanResult.fromRaw(raw))
        }
    }

    @Test fun `scanned and pasted payloads resolve identical pins and secrets`() {
        val result = PairingScanResult.fromRaw("  $code\n") as PairingScanResult.Code
        val scanned = PairingCode.parse(result.value)
        val pasted = PairingCode.parse(code)
        assertEquals(scanned.device.copy(pairedTimestamp = 0), pasted.device.copy(pairedTimestamp = 0))
        assertEquals(scanned.secret, pasted.secret)
        assertEquals(code, result.value)
    }
}
