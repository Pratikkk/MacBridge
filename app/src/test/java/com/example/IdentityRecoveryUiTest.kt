package com.example

import android.app.Application
import android.content.Intent
import com.example.crypto.IdentityStartup
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.crypto.IdentityFailure
import com.example.ui.screens.recovery.IdentityRecoveryScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `temporary unavailability offers retry without reset or sharing`() {
        var retries = 0; var resets = 0
        compose.setContent { MyApplicationTheme {
            IdentityRecoveryScreen(IdentityFailure.UNAVAILABLE, { retries++ }, { resets++ })
        } }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Reset identity…").assertDoesNotExist()
        compose.onNodeWithTag("nav_bridge").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, resets) }
    }
    @Test fun `missing identity reset requires confirmation and cancellation preserves state`() {
        var resets = 0
        compose.setContent { MyApplicationTheme {
            IdentityRecoveryScreen(IdentityFailure.MISSING, {}, { resets++ })
        } }
        compose.onNodeWithText("Reset identity…").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Reset identity…").performClick()
        compose.onNodeWithText("Reset and unpair").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }
    @Test fun `activity recreation dismisses destructive confirmation`() {
        var resets = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MyApplicationTheme {
            IdentityRecoveryScreen(IdentityFailure.MISSING, {}, { resets++ })
        } }
        compose.onNodeWithText("Reset identity…").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Reset and unpair").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, resets) }
    }
    @Test fun `interrupted reset is explicit and loading exposes no actions`() {
        var reason by mutableStateOf<IdentityFailure?>(IdentityFailure.RESET_INCOMPLETE)
        var resets = 0
        compose.setContent { MyApplicationTheme {
            IdentityRecoveryScreen(reason, {}, { resets++ })
        } }
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Finish identity reset…").performClick()
        compose.onNodeWithText("Finish reset").performClick()
        compose.runOnIdle { assertEquals(1, resets); reason = null }
        compose.onNodeWithText("Reset identity…").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Checking identity access…").assertExists()
    }
    @Test fun `startup intent waits for ready and later shares are delivered once`() {
        var startup by mutableStateOf<IdentityStartup<String>>(IdentityStartup.Loading)
        var pending by mutableStateOf<Intent?>(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "first"))
        val delivered = mutableListOf<String>()
        compose.setContent {
            StartupIntentEffect(startup, pending, { pending = null }, { intent, _ ->
                delivered.add(intent.getStringExtra(Intent.EXTRA_TEXT)!!)
            }, {})
        }
        compose.runOnIdle { assertTrue(delivered.isEmpty()); startup = IdentityStartup.Ready("ready") }
        compose.runOnIdle { assertEquals(listOf("first"), delivered); pending = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "second") }
        compose.runOnIdle { assertEquals(listOf("first", "second"), delivered); assertNull(pending) }
    }
    @Test fun `identity failure discards queued shares rather than replaying after retry`() {
        var startup by mutableStateOf<IdentityStartup<String>>(IdentityStartup.Loading)
        var pending by mutableStateOf<Intent?>(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "private fixture"))
        var sends = 0
        compose.setContent { StartupIntentEffect(startup, pending, { pending = null }, { _, _ -> sends++ }, {}) }
        compose.runOnIdle { startup = IdentityStartup.Recovery(IdentityFailure.MISSING) }
        compose.runOnIdle { assertNull(pending); startup = IdentityStartup.Ready("restored") }
        compose.runOnIdle { assertEquals(0, sends) }
    }

}
