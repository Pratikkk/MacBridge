package com.example.crypto

import android.content.Context
import androidx.room.withTransaction
import com.example.data.MacBridgeDatabase
import java.io.File
import java.util.UUID

/** No blanket data clear: verified files and completed history are retained. */
internal class IdentityResetStore(private val context: Context,
    private val database: () -> MacBridgeDatabase = { MacBridgeDatabase.getInstance(context) }) {
    private val journal = context.getSharedPreferences("macbridge_recovery", Context.MODE_PRIVATE)
    val pending get() = journal.getBoolean("reset_pending", false)
    fun begin() { check(journal.edit().putBoolean("reset_pending", true).commit()) }
    suspend fun clearTrust() {
        val db = database()
        db.withTransaction {
            db.pairedDeviceDao().clearAll()
            db.notificationDao().clearAll()
            db.fileTransferDao().failForIdentityReset()
        }
        check(context.getSharedPreferences("macbridge_app_filters", Context.MODE_PRIVATE).edit().clear().commit())
    }
    fun invalidateTransfers() {
        val folder = File(context.filesDir, "transfer_state")
        check(folder.canonicalFile == File(context.filesDir.canonicalFile, "transfer_state"))
        for (base in listOf("incoming.json", "outgoing.json")) {
            // AtomicFile may restore .bak on read; invalidate all metadata copies.
            for (suffix in listOf("", ".bak", ".new")) {
                val file = File(folder, base + suffix)
                check(!file.exists() || file.delete())
            }
        }
    }
    fun resetMetadata() {
        check(context.getSharedPreferences("macbridge_identity_prefs", Context.MODE_PRIVATE).edit()
            .putString("device_id", UUID.randomUUID().toString())
            .remove("sw_pub_key").remove("sw_priv_key").commit())
    }
    fun finish() {
        if (!journal.edit().remove("reset_pending").commit()) {
            // SharedPreferences updates memory even when its disk commit fails.
            // Keep this process gated too; do not retry into a mixed reset state.
            journal.edit().putBoolean("reset_pending", true).commit()
            error("Identity reset journal unavailable")
        }
    }
}
