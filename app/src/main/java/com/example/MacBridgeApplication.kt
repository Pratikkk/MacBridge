package com.example

import android.app.Application
import android.content.Context
import com.example.crypto.*
import com.example.manager.BridgeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MacBridgeApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var startup: IdentityRecoveryController<BridgeManager>
    val identityStartup get() = startup.state
    val bridgeManager: BridgeManager? get() = (startup.state.value as? IdentityStartup.Ready)?.value

    override fun onCreate() {
        super.onCreate()
        startup = IdentityRecoveryController(applicationScope,
            pendingReset = { recoveryPrefs().getBoolean("reset_pending", false) },
            initialize = { BridgeManager(this, applicationScope) },
            reset = { resetLostIdentity() })
        startup.retry()
    }

    fun retryIdentity() = startup.retry()
    fun resetIdentityConfirmed() = startup.resetConfirmed()
    private fun recoveryPrefs() = getSharedPreferences("macbridge_recovery", Context.MODE_PRIVATE)

    private suspend fun resetLostIdentity() {
        val journal = recoveryPrefs()
        if (!journal.getBoolean("reset_pending", false)) {
            // The old identity may become available between display and confirmation.
            // If it recovered, keep every existing pairing and initialize normally.
            try { IdentityManager(this); return }
            catch (error: IdentityAccessException) {
                if (error.reason != IdentityFailure.MISSING) throw error
            }
        }
        val store = IdentityResetStore(this)
        completeIdentityReset(store::begin, { IdentityManager.prepareReplacement(this) },
            store::clearTrust, store::invalidateTransfers, store::resetMetadata, store::finish)
    }

    override fun onTerminate() {
        bridgeManager?.onDestroy()
        applicationScope.cancel()
        super.onTerminate()
    }
}
