package com.macbridge.notificationfixture;

import android.app.*;
import android.content.*;

/** ADB-only test control. All notifications and replies stay inside this fixture app. */
public final class FixtureReceiver extends BroadcastReceiver {
    public static final int ID = 2401;
    public static void post(Context context, String mode, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("test", "Bridge test alerts", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder builder = new Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("MacBridge test · 世界 🌉")
            .setContentText(text).setOnlyAlertOnce(true);
        if (mode.equals("secret")) builder.setVisibility(Notification.VISIBILITY_SECRET);
        if (mode.equals("ongoing")) builder.setOngoing(true);
        if (mode.equals("summary")) builder.setGroup("test").setGroupSummary(true);
        if (!mode.equals("unsupported")) {
            Intent reply = new Intent(context, ReplyReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(context, 1, reply,
                mode.equals("immutable") ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_MUTABLE);
            RemoteInput input = new RemoteInput.Builder("reply").setLabel("Test reply").build();
            Notification.Action action = new Notification.Action.Builder(null, "Reply", pending)
                .addRemoteInput(input).setAuthenticationRequired(mode.equals("auth")).build();
            builder.addAction(action);
            if (mode.equals("ambiguous")) builder.addAction(action);
        }
        try {
            manager.notify(ID, builder.build());
            context.getSharedPreferences("post_results", 0).edit()
                .putString("mode", mode).putBoolean("posted", true).remove("reason").commit();
        } catch (IllegalArgumentException rejected) {
            // Recent Android versions forbid immutable RemoteInput actions at posting time.
            // Keep this negative test observable without crashing or retaining an old alert.
            if (!mode.equals("immutable")) throw rejected;
            manager.cancel(ID);
            context.getSharedPreferences("post_results", 0).edit()
                .putString("mode", mode).putBoolean("posted", false)
                .putString("reason", "Android rejected immutable RemoteInput action").commit();
            android.widget.Toast.makeText(context,
                "Android rejected this immutable reply test; use automated coverage.",
                android.widget.Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra("mode");
        if ("remove".equals(mode)) { context.getSystemService(NotificationManager.class).cancel(ID); return; }
        if ("reset".equals(mode)) {
            context.getSharedPreferences("results", 0).edit().clear().commit();
            context.getSystemService(NotificationManager.class).cancel(ID); return;
        }
        post(context, mode == null ? "reply" : mode, "Local test notification · no external message");
    }
}
