package com.example.network

import com.example.manager.DiagnosticLogger
import com.example.model.ProtocolMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes and decodes MacBridge Wire/Protobuf equivalent JSON frames.
 * Security Baseline 5: Strict schema, hostile input handling, max message limit.
 */
object ProtocolCodec {
    private const val TAG = "ProtocolCodec"
    private const val MAX_FRAME_SIZE = WireFrames.MAX_BYTES // Enforced on incoming bytes before JSON parsing

    fun encode(message: ProtocolMessage): String {
        val json = JSONObject()
        json.put("type", message.type)
        when (message) {
            is ProtocolMessage.Hello -> {
                json.put("deviceId", message.deviceId)
                json.put("deviceName", message.deviceName)
                json.put("fingerprint", message.fingerprint)
                json.put("protocolVersion", message.protocolVersion)
                val caps = JSONArray()
                message.capabilities.forEach { caps.put(it) }
                json.put("capabilities", caps)
            }
            is ProtocolMessage.PairRequest -> {
                json.put("deviceId", message.deviceId)
                json.put("deviceName", message.deviceName)
                json.put("fingerprint", message.fingerprint)
                json.put("oneTimeSecret", message.oneTimeSecret)
                json.put("timestamp", message.timestamp)
            }
            is ProtocolMessage.PairResponse -> {
                json.put("accepted", message.accepted)
                json.put("deviceId", message.deviceId)
                json.put("fingerprint", message.fingerprint)
                message.reason?.let { json.put("reason", it) }
            }
            is ProtocolMessage.Heartbeat -> {
                json.put("seq", message.seq)
                json.put("timestamp", message.timestamp)
                json.put("isAck", message.isAck)
            }
            is ProtocolMessage.ClipboardSync -> {
                json.put("content", message.content)
                json.put("timestamp", message.timestamp)
                json.put("sourceDevice", message.sourceDevice)
                json.put("mimeType", message.mimeType)
            }
            is ProtocolMessage.FileInit -> {
                json.put("transferId", message.transferId)
                json.put("fileName", message.fileName)
                json.put("fileSize", message.fileSize)
                json.put("sha256Checksum", message.sha256Checksum)
                json.put("mimeType", message.mimeType)
                json.put("chunkSize", message.chunkSize)
                json.put("resume", message.resume)
            }
            is ProtocolMessage.FileChunk -> {
                json.put("transferId", message.transferId)
                json.put("chunkIndex", message.chunkIndex)
                json.put("totalChunks", message.totalChunks)
                json.put("offset", message.offset)
                json.put("dataBase64", message.dataBase64)
                json.put("chunkLength", message.chunkLength)
            }
            is ProtocolMessage.FileAck -> {
                json.put("transferId", message.transferId)
                json.put("receivedBytes", message.receivedBytes)
                json.put("status", message.status)
                message.sha256Checksum?.let { json.put("sha256Checksum", it) }
            }
            is ProtocolMessage.FileCancel -> json.put("transferId", message.transferId)
            is ProtocolMessage.NotificationMirror -> {
                json.put("notificationId", message.notificationId)
                json.put("packageName", message.packageName)
                json.put("appName", message.appName)
                json.put("title", message.title)
                json.put("text", message.text)
                json.put("timestamp", message.timestamp)
                json.put("hasReplyAction", message.hasReplyAction)
                json.put("isDismissed", message.isDismissed)
                message.dismissToken?.let { json.put("dismissToken", it) }
            }
            is ProtocolMessage.NotificationAction -> {
                json.put("notificationId", message.notificationId)
                json.put("actionType", message.actionType)
                message.replyText?.let { json.put("replyText", it) }
                message.actionToken?.let { json.put("actionToken", it) }
                message.status?.let { json.put("status", it) }
            }
        }
        return json.toString()
    }

    fun decode(payload: String): ProtocolMessage? {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_FRAME_SIZE) {
            DiagnosticLogger.e(TAG, "Security rule violated: Incoming payload exceeds frame limit (${payload.length} bytes)")
            throw SecurityException("Payload size limit exceeded")
        }

        return try {
            val json = JSONObject(payload)
            when (json.getString("type")) {
                "HELLO" -> {
                    val caps = mutableListOf<String>()
                    val arr = json.optJSONArray("capabilities")
                    if (arr != null) {
                        for (i in 0 until arr.length()) caps.add(arr.getString(i))
                    }
                    ProtocolMessage.Hello(
                        deviceId = json.getString("deviceId"),
                        deviceName = json.getString("deviceName"),
                        fingerprint = json.getString("fingerprint"),
                        protocolVersion = json.optInt("protocolVersion", 1),
                        capabilities = caps
                    )
                }
                "PAIR_REQUEST" -> ProtocolMessage.PairRequest(
                    deviceId = json.getString("deviceId"),
                    deviceName = json.getString("deviceName"),
                    fingerprint = json.getString("fingerprint"),
                    oneTimeSecret = json.getString("oneTimeSecret"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                "PAIR_RESPONSE" -> ProtocolMessage.PairResponse(
                    accepted = json.getBoolean("accepted"),
                    deviceId = json.getString("deviceId"),
                    fingerprint = json.getString("fingerprint"),
                    reason = json.optString("reason", null)
                )
                "HEARTBEAT" -> ProtocolMessage.Heartbeat(
                    seq = json.getLong("seq"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    isAck = json.optBoolean("isAck", false)
                )
                "CLIPBOARD" -> ProtocolMessage.ClipboardSync(
                    content = json.getString("content"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    sourceDevice = json.getString("sourceDevice"),
                    mimeType = json.optString("mimeType", "text/plain")
                )
                "FILE_INIT" -> ProtocolMessage.FileInit(
                    transferId = json.getString("transferId"),
                    fileName = json.getString("fileName"),
                    fileSize = json.getLong("fileSize"),
                    sha256Checksum = json.getString("sha256Checksum"),
                    mimeType = json.optString("mimeType", "application/octet-stream"),
                    chunkSize = json.optInt("chunkSize", 64 * 1024),
                    resume = json.optBoolean("resume", false)
                )
                "FILE_CHUNK" -> ProtocolMessage.FileChunk(
                    transferId = json.getString("transferId"),
                    chunkIndex = json.getInt("chunkIndex"),
                    totalChunks = json.getInt("totalChunks"),
                    offset = json.getLong("offset"),
                    dataBase64 = json.getString("dataBase64"),
                    chunkLength = json.getInt("chunkLength")
                )
                "FILE_ACK" -> ProtocolMessage.FileAck(
                    transferId = json.getString("transferId"),
                    receivedBytes = json.getLong("receivedBytes"),
                    status = json.getString("status"),
                    sha256Checksum = if (json.has("sha256Checksum")) json.getString("sha256Checksum") else null
                )
                "FILE_CANCEL" -> ProtocolMessage.FileCancel(json.getString("transferId"))
                "NOTIFICATION" -> ProtocolMessage.NotificationMirror(
                    notificationId = json.getString("notificationId"),
                    packageName = json.getString("packageName"),
                    appName = json.getString("appName"),
                    title = json.getString("title"),
                    text = json.getString("text"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    hasReplyAction = json.optBoolean("hasReplyAction", false),
                    isDismissed = json.optBoolean("isDismissed", false),
                    dismissToken = if (json.has("dismissToken")) json.getString("dismissToken") else null
                )
                "NOTIFICATION_ACTION" -> ProtocolMessage.NotificationAction(
                    notificationId = json.getString("notificationId"),
                    actionType = json.getString("actionType"),
                    replyText = if (json.has("replyText")) json.getString("replyText") else null,
                    actionToken = if (json.has("actionToken")) json.getString("actionToken") else null,
                    status = if (json.has("status")) json.getString("status") else null
                )
                else -> null
            }
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "Failed to decode protocol frame: ${e.message}")
            null
        }
    }
}
