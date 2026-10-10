package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.manager.TextSendResult
import com.example.ui.MacBridgeApp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import com.example.sharing.ShareInbox
import com.example.sharing.SharedContent
import com.example.sharing.parseSharedContent

class MainActivity : ComponentActivity() {
    private val shareInbox = ShareInbox()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MacBridgeApplication
        val bridgeManager = app.bridgeManager

        // Handle Share Sheet intents
        if (savedInstanceState == null) handleIntent(intent, bridgeManager)
        else shareInbox.restore(savedInstanceState)

        setContent {
            MyApplicationTheme {
                MacBridgeApp(bridgeManager = bridgeManager, sharedFile = shareInbox.pending,
                    onDismissShare = shareInbox::dismiss, onShareSent = shareInbox::sent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as MacBridgeApplication
        handleIntent(intent, app.bridgeManager)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        shareInbox.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent?, bridgeManager: com.example.manager.BridgeManager) {
        when (val shared = parseSharedContent(intent)) {
            is SharedContent.File -> {
                if (!shareInbox.accept(shared.uri) && shareInbox.pending != shared.uri && shareInbox.pending != null)
                    Toast.makeText(this, "Finish or cancel the file you’re reviewing first.", Toast.LENGTH_LONG).show()
            }
            is SharedContent.Rejected -> Toast.makeText(this, shared.message, Toast.LENGTH_LONG).show()
            SharedContent.Ignored -> Unit
            is SharedContent.Text -> {
                lifecycleScope.launch {
                    val result = bridgeManager.sendSharedText(shared.text)
                    val message = when (result) {
                        TextSendResult.SENT -> "Shared text sent to Mac"
                        TextSendResult.EMPTY_TEXT -> "No text to share. To send a file, open Share → Files and choose it."
                        TextSendResult.NOT_CONNECTED -> "Connect to your Mac before sharing text"
                        TextSendResult.PERMISSION_DENIED -> "Enable clipboard sharing for this Mac in Devices"
                        TextSendResult.SEND_FAILED -> "Could not send text. Check your Mac connection and try again."
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
