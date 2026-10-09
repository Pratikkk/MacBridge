package com.example

import android.app.Application
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.*
import com.example.ui.screens.bridge.ConnectionHeroCard
import com.example.ui.screens.clipboard.ClipboardWorkspace
import com.example.ui.screens.devices.PairedDeviceItemCard
import com.example.ui.screens.settings.SettingsContent
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModernUiTest {
    @get:Rule val compose = createComposeRule()
    private val mac = PairedDevice("mac1", "My Mac", "AB:".repeat(31) + "AB", "pin", "192.168.0.2", allowClipboard = true)
    private val connected = ConnectionState.Connected(mac, mac.lastKnownIp, mac.port)

    @Test fun `sharing follows current stored permissions not stale session copy`() {
        assertTrue(sharingUiState(connected, listOf(mac)).canSend)
        for (devices in listOf(emptyList(), listOf(mac.copy(isBlocked = true)), listOf(mac.copy(allowClipboard = false)))) {
            assertFalse(sharingUiState(connected, devices).canSend)
        }
        for (state in listOf(ConnectionState.Disconnected, ConnectionState.Reconnecting("Mac", 1, 1000),
            connected.copy(isSimulated = true))) assertFalse(sharingUiState(state, listOf(mac)).canSend)
    }

    @Test fun `four clear destinations navigate and restore after recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MyApplicationTheme {
            AppShell(ConnectionState.Disconnected) { destination, _ -> Text("Screen ${destination.label}") }
        } }
        for (destination in NavigationDestination.entries) {
            compose.onNodeWithTag(destination.tag).performClick().assertIsSelected()
            compose.onNodeWithText("Screen ${destination.label}").assertExists()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.onNodeWithText("Screen Settings").assertExists()
        compose.onNodeWithTag("nav_files").assertDoesNotExist()
        compose.onNodeWithTag("nav_alerts").assertDoesNotExist()
    }

    @Test fun `tab changes preserve screen state without retaining disposed content`() {
        compose.setContent { MyApplicationTheme {
            AppShell(ConnectionState.Disconnected) { destination, _ ->
                var count by rememberSaveable { mutableStateOf(0) }
                androidx.compose.material3.TextButton(onClick = { count++ }) { Text("${destination.label} count $count") }
            }
        } }
        compose.onNodeWithText("Home count 0").performClick()
        compose.onNodeWithTag("nav_devices").performClick()
        compose.onNodeWithText("Devices count 0").assertExists()
        compose.onNodeWithTag("nav_bridge").performClick()
        compose.onNodeWithText("Home count 1").assertExists()
    }

    @Test fun `system back from a secondary tab returns Home`() {
        var owner: OnBackPressedDispatcherOwner? = null
        compose.setContent { MyApplicationTheme {
            AppShell(ConnectionState.Disconnected) { destination, _ ->
                owner = LocalOnBackPressedDispatcherOwner.current
                Text("Screen ${destination.label}")
            }
        } }
        compose.onNodeWithTag("nav_devices").performClick()
        compose.runOnIdle { owner!!.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav_bridge").assertIsSelected()
        compose.onNodeWithText("Screen Home").assertExists()
    }

    @Test fun `unpaired home explains next step without demo controls`() {
        var pairs = 0
        compose.setContent { MyApplicationTheme { ConnectionHeroCard(ConnectionState.Disconnected, emptyList(), { pairs++ }, {}) } }
        compose.onNodeWithText("Pair a Mac").performClick()
        compose.runOnIdle { assertEquals(1, pairs) }
        compose.onNodeWithTag("simulate_mac_button").assertDoesNotExist()
        compose.onNodeWithText("MacBook Pro").assertDoesNotExist()
    }

    @Test fun `multiple Macs require choosing a device and pending connection can cancel`() {
        var state by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        var cancels = 0
        compose.setContent { MyApplicationTheme {
            ConnectionHeroCard(state, listOf(mac, mac.copy(id = "mac2")), {}, { cancels++ })
        } }
        compose.onNodeWithText("Choose a Mac").assertExists()
        compose.runOnIdle { state = ConnectionState.Reconnecting("Mac", 2, 1000) }
        compose.onNodeWithText("Cancel connection").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
        compose.onNodeWithTag("connect_button").assertDoesNotExist()
    }

    @Test fun `clipboard is disabled offline and offers direct recovery route`() {
        var routes = 0
        compose.setContent { MyApplicationTheme {
            ClipboardWorkspace(emptyList(), sharingUiState(ConnectionState.Disconnected, listOf(mac)), false, null,
                { fail("Cannot send offline") }, { routes++ }, {}, {})
        } }
        compose.onNodeWithTag("push_clipboard_button").assertIsNotEnabled()
        compose.onNodeWithTag("clipboard_manage_devices").performClick()
        compose.runOnIdle { assertEquals(1, routes) }
        compose.onNodeWithTag("simulate_mac_clip_button").assertDoesNotExist()
    }

    @Test fun `sending prevents repeats and feedback survives operation`() {
        var sending by mutableStateOf(false)
        var count = 0
        compose.setContent { MyApplicationTheme {
            ClipboardWorkspace(emptyList(), sharingUiState(connected, listOf(mac)), sending, "Clipboard sent to Mac.",
                { sending = true; count++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("push_clipboard_button").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("clipboard_feedback").assertTextEquals("Clipboard sent to Mac.")
        compose.runOnIdle { assertEquals(1, count) }
    }

    @Test fun `clearing history requires confirmation and cancellation preserves it`() {
        var cleared = 0
        compose.setContent { MyApplicationTheme {
            ClipboardWorkspace(listOf(ClipboardItem(id = 1, content = "🌉", sourceDeviceName = "Mac")),
                sharingUiState(connected, listOf(mac)), false, null, {}, {}, { cleared++ }, {})
        } }
        compose.onNodeWithTag("clipboard_list").performScrollToNode(hasTestTag("clear_history_button"))
        compose.onNodeWithTag("clear_history_button").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithTag("clear_history_button").performClick()
        compose.onNodeWithTag("confirm_clear_history").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun `forget Mac requires confirmation and toggles preserve unrelated permissions`() {
        var forgotten = 0
        var clipboard: Boolean? = null
        compose.setContent { MyApplicationTheme {
            PairedDeviceItemCard(mac.copy(allowFileTransfer = true), {}, { forgotten++ }, { cb, files, alerts ->
                clipboard = cb; assertTrue(files); assertFalse(alerts)
            })
        } }
        compose.onNodeWithTag("clipboard_permission_mac1").performClick()
        compose.runOnIdle { assertEquals(false, clipboard) }
        compose.onNodeWithTag("forget_mac_mac1").performClick()
        compose.runOnIdle { assertEquals(0, forgotten) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("forget_mac_mac1").performClick()
        compose.onNodeWithTag("confirm_forget_mac").performClick()
        compose.runOnIdle { assertEquals(1, forgotten) }
    }

    @Test fun `blocked Mac cannot connect or change sharing`() {
        compose.setContent { MyApplicationTheme {
            PairedDeviceItemCard(mac.copy(isBlocked = true), { fail("Blocked") }, {}, { _, _, _ -> fail("Blocked") })
        } }
        compose.onNodeWithTag("device_connect_mac1").assertIsNotEnabled()
        compose.onNodeWithTag("clipboard_permission_mac1").assertIsNotEnabled()
    }

    @Test fun `small screen and large text retain accessible settings navigation`() {
        var details = 0
        compose.setContent { MyApplicationTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                Box(Modifier.width(320.dp).height(600.dp)) { SettingsContent({}, { details++ }, {}) }
            }
        } }
        compose.onNodeWithTag("open_security_details").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, details) }
        compose.onNodeWithText("File sharing · Coming later").performScrollTo().assertIsDisplayed()
    }
}
