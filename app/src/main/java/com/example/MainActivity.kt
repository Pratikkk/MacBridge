package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.example.crypto.IdentityStartup
import com.example.ui.screens.recovery.IdentityRecoveryScreen
import com.example.manager.TextSendResult
import com.example.ui.MacBridgeApp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import com.example.sharing.ShareInbox
import com.example.sharing.SharedContent
import com.example.sharing.parseSharedContent

class MainActivity : ComponentActivity() {
    private val shareInbox = ShareInbox()
    private var queuedIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MacBridgeApplication
        if (savedInstanceState == null) queuedIntent = intent
        else shareInbox.restore(savedInstanceState)

        setContent {
            val startup by app.identityStartup.collectAsStateWithLifecycle()
            StartupIntentEffect(startup, queuedIntent, onClear = { queuedIntent = null },
                onReady = { delivered, bridge -> handleIntent(delivered, bridge) },
                onRecovery = { delivered ->
                    if (delivered?.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
                        Toast.makeText(this@MainActivity, "Check identity access, then share again from the source app.", Toast.LENGTH_LONG).show()
                    shareInbox.dismiss()
                })
            MyApplicationTheme {
                when (val value = startup) {
                    is IdentityStartup.Ready -> MacBridgeApp(bridgeManager = value.value, sharedFile = shareInbox.pending,
                        onDismissShare = shareInbox::dismiss, onShareSent = shareInbox::sent)
                    else -> IdentityRecoveryScreen((value as? IdentityStartup.Recovery)?.reason,
                        onRetry = app::retryIdentity, onResetConfirmed = app::resetIdentityConfirmed)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as MacBridgeApplication
        if (app.identityStartup.value is IdentityStartup.Recovery) {
            Toast.makeText(this, "Check identity access, then share again from the source app.", Toast.LENGTH_LONG).show()
        } else queuedIntent = intent
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

/** Deliver each intent once, including new shares received after startup is Ready. */
@androidx.compose.runtime.Composable
internal fun <T> StartupIntentEffect(startup: IdentityStartup<T>, pending: Intent?, onClear: () -> Unit,
    onReady: (Intent, T) -> Unit, onRecovery: (Intent?) -> Unit) {
    LaunchedEffect(startup, pending) {
        when (startup) {
            is IdentityStartup.Ready -> { onClear(); if (pending != null) onReady(pending, startup.value) }
            is IdentityStartup.Recovery -> { onClear(); onRecovery(pending) }
            IdentityStartup.Loading -> Unit
        }
    }
}
