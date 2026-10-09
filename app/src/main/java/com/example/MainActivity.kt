package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.MacBridgeApp
import com.example.ui.theme.MyApplicationTheme

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
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val ok = bridgeManager.clipboardManager.pushCurrentClipboardToMac("Mac")
                if (ok) {
                    Toast.makeText(this, "Shared text pushed to Mac", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
