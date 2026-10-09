package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.ClipboardItem
import com.example.model.FileTransferItem
import com.example.model.MirroredNotification
import com.example.model.PairedDevice

@Database(
    entities = [
        PairedDevice::class,
        ClipboardItem::class,
        FileTransferItem::class,
        MirroredNotification::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MacBridgeDatabase : RoomDatabase() {
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var INSTANCE: MacBridgeDatabase? = null

        fun getInstance(context: Context): MacBridgeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MacBridgeDatabase::class.java,
                    "macbridge.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
