package com.example.manager

import java.util.UUID

internal data class NotificationGeneration(val key: String, val pkg: String, val posted: Long, val token: String,
    val target: NotificationDestination? = null, val reply: NotificationReplyTarget? = null)

/** One-use handles for actually forwarded alerts, never persisted across process/session changes. */
internal class NotificationActions(private val token: () -> String = { UUID.randomUUID().toString() }) {
    private val records = linkedMapOf<String, NotificationGeneration>()
    @Synchronized fun observe(key: String, pkg: String, posted: Long, clearable: Boolean, reply: NotificationReplyTarget? = null): String? {
        records.remove(key)
        if (!clearable) return null
        if (records.size >= 100) records.remove(records.keys.first())
        val value = token()
        records[key] = NotificationGeneration(key, pkg, posted, value, reply = reply)
        return value
    }
    @Synchronized fun bind(key: String, token: String, target: NotificationDestination) {
        val record = records[key]?.takeIf { it.token == token } ?: return
        records[key] = record.copy(target = target)
    }
    @Synchronized fun take(key: String, token: String, target: NotificationDestination): NotificationGeneration? {
        val record = records[key] ?: return null
        val old = record.target ?: return null
        if (record.token != token || !target.allowed || old.id != target.id || old.pin != target.pin || old.session != target.session) return null
        records.remove(key)
        return record
    }
    @Synchronized fun clearTarget(id: String) { records.entries.removeAll { it.value.target?.id == id } }
    @Synchronized fun remove(key: String) { records.remove(key) }
    @Synchronized fun clear(pkg: String = "") {
        if (pkg.isEmpty()) records.clear() else records.entries.removeAll { it.value.pkg == pkg }
    }
}

internal fun notificationStillDismissible(record: NotificationGeneration, pkg: String?, posted: Long?,
    clearable: Boolean, secret: Boolean, ongoing: Boolean, summary: Boolean): Boolean =
    record.pkg == pkg && record.posted == posted && clearable && !secret && !ongoing && !summary
