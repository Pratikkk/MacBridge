package com.example.manager

import android.util.AtomicFile
import com.example.model.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Atomic metadata contains only relative private filenames and pinned peer identifiers. */
internal class TransferCheckpoint(private val file: File) {
    private val atomic = AtomicFile(file)
    @Synchronized fun load(): JSONObject = try {
        atomic.openRead().use { input ->
            val bytes = input.readNBytesCompat(128 * 1024 + 1)
            require(bytes.size <= 128 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
        }
    } catch (_: Exception) { JSONObject() }
    @Synchronized fun save(value: JSONObject) {
        value.put("version", 1)
        val data = value.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 128 * 1024)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val output = atomic.startWrite()
        try { output.write(data); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    @Synchronized fun clear() = atomic.delete()
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (n < 0) break
        if (n > 0) output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

internal fun FileTransferTarget.checkpointPeer() = JSONObject().put("id", device.id).put("pin", device.fingerprint)
internal fun JSONObject.checkpointTarget(): FileTransferTarget {
    val peer = getJSONObject("peer")
    val id = peer.getString("id")
    val pin = peer.getString("pin")
    require(id.isNotEmpty() && id.length <= 256 && pin.isNotEmpty() && pin.length <= 256)
    return FileTransferTarget(PairedDevice(id, "Saved peer", pin, "", "", allowFileTransfer = true), -1)
}
internal fun FileTransferItem.checkpointItem() = JSONObject()
    .put("id", transferId).put("name", fileName).put("size", fileSize).put("offset", transferredBytes)
    .put("checksum", sha256Checksum).put("timestamp", timestamp)
internal fun JSONObject.checkpointItem(direction: TransferDirection): FileTransferItem {
    val id = getString("id")
    val name = getString("name")
    val size = getLong("size")
    val offset = getLong("offset")
    val checksum = getString("checksum")
    require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && name.isNotEmpty() && name.length <= 256)
    require(size in 0..MAX_FILE_BYTES && offset in 0..size && checksum.matches(Regex("[0-9a-f]{64}")))
    return FileTransferItem(id, name, size, offset, direction, TransferStatus.PAUSED, checksum, timestamp = getLong("timestamp"))
}
internal fun JSONObject.checkpointExpiry(window: Long): Long {
    val expires = getLong("expires")
    val now = System.currentTimeMillis()
    require(expires > now && expires - now <= window + 1000)
    return expires
}
internal fun checkpointFile(folder: File, name: String, prefix: String): File {
    require(name.startsWith(prefix) && File(name).name == name)
    val file = File(folder, name)
    require(file.isFile && file.canonicalFile.parentFile == folder.canonicalFile && file.canonicalFile.name == name)
    return file
}
internal fun checkpointDigest(file: File, count: Long = file.length()): MessageDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { source ->
        var remaining = count
        val buffer = ByteArray(FILE_CHUNK_SIZE)
        while (remaining > 0) {
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(n > 0)
            digest.update(buffer, 0, n)
            remaining -= n
        }
    }
    return digest
}
internal fun MessageDigest.checkpointHash() = digest().joinToString("") { "%02x".format(it) }
