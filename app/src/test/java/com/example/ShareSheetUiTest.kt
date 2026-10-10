package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.model.*
import com.example.sharing.SharedDocumentInfo
import com.example.ui.screens.files.SharedFileReview
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private val mac = PairedDevice("mac", "My Mac", "pin", "key", "127.0.0.1", allowFileTransfer = true)

    @Test fun `disconnected share connects selected Mac then requires explicit sending`() {
        var state by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        var sends = 0
        var connects = 0
        var cancels = 0
        compose.setContent { MyApplicationTheme {
            SharedFileReview(SharedDocumentInfo("🌉 notes.txt"), listOf(mac), mac, state, false, false, null,
                {}, { connects++; state = ConnectionState.Connected(mac, mac.lastKnownIp, mac.port) }, {}, {}, { sends++ }, { cancels++ })
        } }
        compose.onNodeWithText("Send file").assertIsNotEnabled()
        compose.onNodeWithText("Connect to My Mac").performClick()
        compose.onNodeWithText("Send file").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(0, sends) }
        compose.onNodeWithText("Send file").performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun `review blocks send on revocation identity changes busy paused and invalid file`() {
        var devices by mutableStateOf(listOf(mac))
        var state by mutableStateOf<ConnectionState>(ConnectionState.Connected(mac, mac.lastKnownIp, mac.port))
        var busy by mutableStateOf(false)
        var paused by mutableStateOf(false)
        var document by mutableStateOf<SharedDocumentInfo?>(SharedDocumentInfo("test.txt", 1))
        var sends = 0
        compose.setContent { MyApplicationTheme {
            SharedFileReview(document, devices, mac, state, busy, paused, null, {}, {}, {}, {}, { sends++ }, {})
        } }
        fun blocked(change: () -> Unit) {
            compose.runOnIdle(change)
            compose.onNodeWithText("Send file").assertIsNotEnabled()
        }
        compose.onNodeWithText("Send file").assertIsEnabled()
        blocked { devices = listOf(mac.copy(allowFileTransfer = false)) }
        blocked { devices = listOf(mac.copy(fingerprint = "new-pin")) }
        blocked { devices = emptyList() }
        blocked { devices = listOf(mac); state = (state as ConnectionState.Connected).copy(isSimulated = true) }
        blocked { state = ConnectionState.Reconnecting("Mac", 1, 1000) }
        blocked { state = ConnectionState.Connected(mac, mac.lastKnownIp, mac.port); busy = true }
        blocked { busy = false; paused = true }
        blocked { paused = false; document = SharedDocumentInfo(error = "File access is unavailable.") }
        blocked { document = null }
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun `review allows peer selection with accessible cancel and send at large font`() {
        val other = mac.copy(id = "other", name = "Office Mac", fingerprint = "other-pin")
        var selected by mutableStateOf(mac)
        var state by mutableStateOf<ConnectionState>(ConnectionState.Connected(mac, mac.lastKnownIp, mac.port))
        var cancels = 0
        compose.setContent { MyApplicationTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                SharedFileReview(SharedDocumentInfo("🌉 presentation.txt", 1), listOf(mac, other), selected, state, false, false, null,
                    { selected = it }, { state = ConnectionState.Connected(selected, selected.lastKnownIp, selected.port) }, {}, {}, {}, { cancels++ })
            }
        } }
        compose.onNodeWithText("My Mac").performClick()
        compose.onNodeWithText("Office Mac").performClick()
        compose.onNodeWithText("Send file").assertIsNotEnabled()
        compose.onNodeWithText("Connect to Office Mac").performScrollTo().performClick()
        compose.onNodeWithText("Send file").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }
}
