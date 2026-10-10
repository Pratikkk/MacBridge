package com.example.manager

import com.example.model.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class NotificationDestination(val id: String, val pin: String, val session: Long, val allowed: Boolean)

/** No persistent content or reconnect backlog. A bounded actor preserves posted/removed ordering. */
internal class NotificationRelay(scope: CoroutineScope,
    private val current: suspend () -> NotificationDestination?,
    private val permitted: (String) -> Boolean,
    private val send: (ProtocolMessage, NotificationDestination) -> Boolean) {
    private data class Work(val target: NotificationDestination, val message: ProtocolMessage, val epoch: Long)
    private val queue = Channel<Work>(64)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    fun invalidate() { epoch.incrementAndGet() }
    init { scope.launch {
        val sent = linkedMapOf<String, Pair<String, String>>()
        var identity: Triple<String, String, Long>? = null
        for (work in queue) {
            val now = current()
            if (now == null) { sent.clear(); identity = null; continue }
            if (now.id != work.target.id || now.pin != work.target.pin || now.session != work.target.session) continue
            val active = Triple(now.id, now.pin, now.session)
            if (identity != active) { sent.clear(); identity = active }
            val msg = work.message
            if (msg is ProtocolMessage.NotificationMirror && (work.epoch != epoch.get() || !now.allowed || !permitted(msg.packageName))) continue
            when (msg) {
                is ProtocolMessage.NotificationMirror -> {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    for (field in listOf(msg.packageName, msg.appName, msg.title, msg.text, msg.dismissToken.orEmpty())) {
                        val bytes = field.toByteArray(Charsets.UTF_8)
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
                    }
                    val snapshot = msg.packageName to java.util.Base64.getEncoder().encodeToString(digest.digest())
                    if (sent[msg.notificationId] == snapshot) continue
                    if (send(msg, now)) {
                        if (sent.size >= 100 && !sent.containsKey(msg.notificationId)) sent.remove(sent.keys.first())
                        sent[msg.notificationId] = snapshot
                    }
                }
                is ProtocolMessage.NotificationAction -> when (msg.actionType) {
                    "REMOVE" -> if (sent.remove(msg.notificationId) != null) send(msg, now)
                    "CLEAR" -> {
                        if (msg.notificationId.isEmpty()) sent.clear()
                        else sent.entries.removeAll { it.value.first == msg.notificationId }
                        send(msg, now)
                    }
                }
                else -> Unit
            }
        }
    } }
    fun offer(target: NotificationDestination, message: ProtocolMessage): Boolean = queue.trySend(Work(target, message, epoch.get())).isSuccess
}

internal fun notificationPayload(id: String, pkg: String, name: String, title: String, text: String,
    secret: Boolean, ongoing: Boolean, summary: Boolean, previews: Boolean): ProtocolMessage.NotificationMirror? {
    if (secret || ongoing || summary || id.isBlank() || id.toByteArray().size > 512 || pkg.isBlank() || pkg.length > 255) return null
    if (title.isBlank() && text.isBlank()) return null
    fun bounded(value: String, max: Int) = value.filterNot { it.isISOControl() && it != '\n' }.take(max).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    return ProtocolMessage.NotificationMirror(id, pkg, bounded(name, 100),
        if (previews) bounded(title, 256) else "New notification",
        if (previews) bounded(text, 2048) else "Preview hidden on your phone", hasReplyAction = false)
}
