package com.example.manager

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Keeps the original app capability only in the bounded, session-only action registry. */
internal data class NotificationReplyTarget(val intent: PendingIntent, val input: RemoteInput) {
    fun sameAs(other: NotificationReplyTarget): Boolean = intent == other.intent && input.resultKey == other.input.resultKey
    fun send(context: Context, text: String) {
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, text) })
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        intent.send(context, 0, fill)
    }
}

internal fun notificationReplyTarget(n: Notification, pkg: String): NotificationReplyTarget? {
    if (Build.VERSION.SDK_INT < 31) return null // Older APIs cannot inspect intent mutability/type safely.
    // Choice-only, activity, foreign creator and authentication-required actions need phone UI.
    val candidates = n.actions.orEmpty().mapNotNull { action ->
        val intent = action.actionIntent ?: return@mapNotNull null
        val input = action.remoteInputs?.singleOrNull() ?: return@mapNotNull null
        if (!input.allowFreeFormInput || input.resultKey.isBlank() || input.resultKey.length > 128 ||
            intent.creatorPackage != pkg || intent.isActivity || intent.isImmutable ||
            (Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired)) null
        else NotificationReplyTarget(intent, input)
    }
    // Multiple reply destinations are ambiguous; do not choose a conversation on the user's behalf.
    return candidates.singleOrNull()
}

internal fun validNotificationReply(text: String?): Boolean {
    if (text == null || text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 4096) return false
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        if (c.isISOControl() && c != '\n' && c != '\t') return false
        if (c.isHighSurrogate()) {
            if (index == text.length || !text[index++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
