package com.example.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.MacBridgeApplication
import com.example.manager.DiagnosticLogger

@RequiresApi(Build.VERSION_CODES.N)
class MacBridgeTileService : TileService() {

    private val TAG = "MacBridgeTileService"

    override fun onStartListening() {
        super.onStartListening()
        val app = application as? MacBridgeApplication
        val isConnected = app?.bridgeManager?.isConnected == true
        qsTile?.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.label = if (isConnected) "Sync to Mac" else "MacBridge"
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? MacBridgeApplication ?: return
        DiagnosticLogger.i(TAG, "Quick Settings Tile tapped: Sync Clipboard")
        val success = app.bridgeManager.pushClipboard()
        if (success) {
            Toast.makeText(this, "MacBridge: Clipboard synced to Mac", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "MacBridge: No active Mac connection or empty clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
