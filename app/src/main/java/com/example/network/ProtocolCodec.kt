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
                message.replyToken?.let { json.put("replyToken", it) }
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
            val json = StrictJson.objectFrom(payload)
            when (json.stringField("type")) {
                "HELLO" -> {
                    val caps = mutableListOf<String>()
                    val arr = if (json.has("capabilities")) json.getJSONArray("capabilities") else null
                    if (arr != null) {
                        require(arr.length() <= 32) { "Too many capabilities" }
                        for (i in 0 until arr.length()) {
                            val value = arr.get(i)
                            require(value is String) { "Invalid capability" }
                            caps.add(value)
                        }
                    }
                    ProtocolMessage.Hello(
                        deviceId = json.stringField("deviceId"),
                        deviceName = json.stringField("deviceName"),
                        fingerprint = json.stringField("fingerprint"),
                        protocolVersion = json.optionalInt("protocolVersion", 1),
                        capabilities = caps
                    )
                }
                "PAIR_REQUEST" -> ProtocolMessage.PairRequest(
                    deviceId = json.stringField("deviceId"),
                    deviceName = json.stringField("deviceName"),
                    fingerprint = json.stringField("fingerprint"),
                    oneTimeSecret = json.stringField("oneTimeSecret"),
                    timestamp = json.optionalLong("timestamp", System.currentTimeMillis())
                )
                "PAIR_RESPONSE" -> ProtocolMessage.PairResponse(
                    accepted = json.booleanField("accepted"),
                    deviceId = json.stringField("deviceId"),
                    fingerprint = json.stringField("fingerprint"),
                    reason = json.optionalString("reason")
                )
                "HEARTBEAT" -> ProtocolMessage.Heartbeat(
                    seq = json.longField("seq"),
                    timestamp = json.optionalLong("timestamp", System.currentTimeMillis()),
                    isAck = json.optionalBoolean("isAck", false)
                )
                "CLIPBOARD" -> ProtocolMessage.ClipboardSync(
                    content = json.stringField("content"),
                    timestamp = json.optionalLong("timestamp", System.currentTimeMillis()),
                    sourceDevice = json.stringField("sourceDevice"),
                    mimeType = json.optionalString("mimeType", "text/plain")!!
                )
                "FILE_INIT" -> ProtocolMessage.FileInit(
                    transferId = json.stringField("transferId"),
                    fileName = json.stringField("fileName"),
                    fileSize = json.longField("fileSize"),
                    sha256Checksum = json.stringField("sha256Checksum"),
                    mimeType = json.optionalString("mimeType", "application/octet-stream")!!,
                    chunkSize = json.optionalInt("chunkSize", 64 * 1024),
                    resume = json.optionalBoolean("resume", false)
                )
                "FILE_CHUNK" -> ProtocolMessage.FileChunk(
                    transferId = json.stringField("transferId"),
                    chunkIndex = json.intField("chunkIndex"),
                    totalChunks = json.intField("totalChunks"),
                    offset = json.longField("offset"),
                    dataBase64 = json.stringField("dataBase64"),
                    chunkLength = json.intField("chunkLength")
                )
                "FILE_ACK" -> ProtocolMessage.FileAck(
                    transferId = json.stringField("transferId"),
                    receivedBytes = json.longField("receivedBytes"),
                    status = json.stringField("status"),
                    sha256Checksum = if (json.has("sha256Checksum")) json.stringField("sha256Checksum") else null
                )
                "FILE_CANCEL" -> ProtocolMessage.FileCancel(json.stringField("transferId"))
                "NOTIFICATION" -> ProtocolMessage.NotificationMirror(
                    notificationId = json.stringField("notificationId"),
                    packageName = json.stringField("packageName"),
                    appName = json.stringField("appName"),
                    title = json.stringField("title"),
                    text = json.stringField("text"),
                    timestamp = json.optionalLong("timestamp", System.currentTimeMillis()),
                    hasReplyAction = json.optionalBoolean("hasReplyAction", false),
                    isDismissed = json.optionalBoolean("isDismissed", false),
                    dismissToken = if (json.has("dismissToken")) json.stringField("dismissToken") else null,
                    replyToken = if (json.has("replyToken")) json.stringField("replyToken") else null
                )
                "NOTIFICATION_ACTION" -> ProtocolMessage.NotificationAction(
                    notificationId = json.stringField("notificationId"),
                    actionType = json.stringField("actionType"),
                    replyText = if (json.has("replyText")) json.stringField("replyText") else null,
                    actionToken = if (json.has("actionToken")) json.stringField("actionToken") else null,
                    status = if (json.has("status")) json.stringField("status") else null
                )
                else -> null
            }
        } catch (e: Exception) {
            // Parser exception messages can include the entire peer payload.
            DiagnosticLogger.w(TAG, "Invalid protocol frame (${e.javaClass.simpleName})")
            null
        }
    }
}
