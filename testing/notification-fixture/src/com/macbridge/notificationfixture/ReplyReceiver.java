package com.macbridge.notificationfixture;

import android.app.RemoteInput;
import android.content.*;
import android.os.Bundle;

/** Records a local test reply, never sends to a messaging app or network. */
public final class ReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence text = results == null ? null : results.getCharSequence("reply");
        if (text == null) return;
        SharedPreferences prefs = context.getSharedPreferences("results", 0);
        prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).putString("text", text.toString()).commit();
        FixtureReceiver.post(context, "reply", "Local reply received · " + text);
    }
}
