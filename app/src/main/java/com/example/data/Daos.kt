package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.ClipboardItem
import com.example.model.FileTransferItem
import com.example.model.MirroredNotification
import com.example.model.PairedDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices WHERE isBlocked = 0 ORDER BY pairedTimestamp DESC")
    fun getAllPairedDevices(): Flow<List<PairedDevice>>

    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: String): PairedDevice?

    @Query("SELECT * FROM paired_devices WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getDeviceByFingerprint(fingerprint: String): PairedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(device: PairedDevice)

    @Update
    suspend fun update(device: PairedDevice)

    @Delete
    suspend fun delete(device: PairedDevice)

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllClips(): Flow<List<ClipboardItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardItem): Long

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun clearAll()
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<FileTransferItem>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransfer(transferId: String): FileTransferItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(transfer: FileTransferItem)

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteById(transferId: String)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM mirrored_notifications ORDER BY timestamp DESC LIMIT 50")
    fun getAllNotifications(): Flow<List<MirroredNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MirroredNotification)

    @Query("UPDATE mirrored_notifications SET isDismissed = 1 WHERE notificationId = :id")
    suspend fun markDismissed(id: String)

    @Query("DELETE FROM mirrored_notifications")
    suspend fun clearAll()
}
