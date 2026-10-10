package com.macbridge.notificationfixture;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.*;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        TextView intro = new TextView(this);
        intro.setText("Local notification test. Replies stay in this app; no messages are sent to anyone.");
        layout.addView(intro);
        for (String mode : new String[]{"reply", "unsupported", "secret", "ongoing", "summary", "immutable", "auth", "ambiguous"}) {
            Button button = new Button(this);
            button.setText("Post " + mode + " test");
            button.setOnClickListener(view -> FixtureReceiver.post(this, mode, "Local test notification · no external message"));
            layout.addView(button);
        }
        Button remove = new Button(this);
        remove.setText("Remove test alert");
        remove.setOnClickListener(view -> getSystemService(android.app.NotificationManager.class).cancel(FixtureReceiver.ID));
        layout.addView(remove);
        setContentView(layout);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
}
