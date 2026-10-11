package com.example.ui.screens.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.crypto.IdentityFailure
import com.example.ui.components.Panel
import com.example.ui.components.ScreenTitle

/** No peer names, clipboard, notification previews or pending share content here. */
@Composable
fun IdentityRecoveryScreen(reason: IdentityFailure?, onRetry: () -> Unit, onResetConfirmed: () -> Unit) {
    // Recreating the activity dismisses confirmation; it never confirms a reset.
    var confirming by remember(reason) { mutableStateOf(false) }
    val pending = reason == IdentityFailure.RESET_INCOMPLETE
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenTitle("MacBridge", if (reason == null) "Checking identity access…" else "Identity needs attention")
            Panel {
                if (reason == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Sharing will be available when your secure identity is ready.")
                } else {
                    Text(when (reason) {
                        IdentityFailure.MISSING -> "Original identity unavailable"
                        IdentityFailure.UNAVAILABLE -> "Secure storage unavailable"
                        IdentityFailure.RESET_INCOMPLETE -> "Identity reset interrupted"
                    }, style = MaterialTheme.typography.titleLarge)
                    Text(when (reason) {
                        IdentityFailure.MISSING -> "The original signing key is missing or cannot be used. Your Macs will reject a replacement identity until you pair again."
                        IdentityFailure.UNAVAILABLE -> "Android could not access secure storage. Unlock your phone and try again. A restart may help. Your identity and pairings have not been replaced."
                        IdentityFailure.RESET_INCOMPLETE -> "A reset you confirmed did not finish. Sharing is paused until you choose to finish it."
                    })
                    Text("Your verified received files, completed transfer records and clipboard history are kept. Files saved outside MacBridge are unaffected.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!pending) Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Try again") }
                    if (reason != IdentityFailure.UNAVAILABLE) OutlinedButton(onClick = { confirming = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (pending) "Finish identity reset…" else "Reset identity…")
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(if (pending) "Finish identity reset?" else "Reset this phone’s identity?") },
        text = { Text("This replaces the unavailable identity and removes this phone’s saved Macs and notification-sharing choices. Unfinished transfers cannot resume. Pair again on both devices; forget the old phone entry on your Mac. Verified received files, completed transfers, clipboard history and exported files stay saved.") },
        confirmButton = { TextButton(onClick = { confirming = false; onResetConfirmed() }) { Text(if (pending) "Finish reset" else "Reset and unpair") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}
