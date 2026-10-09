package com.example

import android.app.Application
import com.example.manager.BridgeManager
import com.example.manager.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MacBridgeApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var bridgeManager: BridgeManager
        private set

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.i("MacBridgeApplication", "Initializing MacBridge core engine...")
        bridgeManager = BridgeManager(this, applicationScope)
        DiagnosticLogger.i("MacBridgeApplication", "MacBridge initialized with device fingerprint: ${bridgeManager.identityManager.getFingerprint().take(17)}...")
    }

    override fun onTerminate() {
        super.onTerminate()
        bridgeManager.onDestroy()
    }
}
