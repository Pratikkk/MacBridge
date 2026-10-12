package com.example.manager

import android.content.Context
import java.io.File

const val MIB = 1024L * 1024
val RECEIVE_QUOTAS = listOf(250 * MIB, 500 * MIB, 1000 * MIB)
const val MAX_RECEIVED_FILES = 100

data class StorageUsage(val bytes: Long, val files: Int, val limit: Long)
class StorageFull : Exception()

/** Only private received copies are managed here; exports never enter this folder. */
class StoragePolicy(context: Context) {
    private val preferences = context.getSharedPreferences("macbridge_storage", Context.MODE_PRIVATE)
    val folder = File(context.filesDir, "received_files")
    val limit: Long get() = preferences.getLong("limit", 500 * MIB).takeIf { it in RECEIVE_QUOTAS } ?: 500 * MIB
    fun setLimit(value: Long) { require(value in RECEIVE_QUOTAS); check(preferences.edit().putLong("limit", value).commit()) }
    fun usage(): StorageUsage {
        require(folder.canonicalFile == File(folder.parentFile.canonicalFile, folder.name) && (!folder.exists() || folder.isDirectory))
        val files = if (folder.exists()) requireNotNull(folder.listFiles()) else emptyArray()
        require(files.size <= 10000 && files.all { it.canonicalFile == File(folder.canonicalFile, it.name) && it.isFile })
        return StorageUsage(files.sumOf { it.length() }, files.size, limit)
    }
    fun requireSpace(size: Long, free: Long = folder.usableSpace) {
        val used = usage()
        if (size > used.limit - used.bytes || used.files >= MAX_RECEIVED_FILES || free < size + 8 * MIB) throw StorageFull()
    }
}
