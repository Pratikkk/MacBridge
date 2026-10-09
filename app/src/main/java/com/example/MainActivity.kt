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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MacBridgeApplication
        val bridgeManager = app.bridgeManager

        // Handle Share Sheet intents
        handleIntent(intent, bridgeManager)

        setContent {
            MyApplicationTheme {
                MacBridgeApp(bridgeManager = bridgeManager)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as MacBridgeApplication
        handleIntent(intent, app.bridgeManager)
    }

    private fun handleIntent(intent: Intent?, bridgeManager: com.example.manager.BridgeManager) {
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            lifecycleScope.launch {
                val result = bridgeManager.sendSharedText(sharedText)
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
