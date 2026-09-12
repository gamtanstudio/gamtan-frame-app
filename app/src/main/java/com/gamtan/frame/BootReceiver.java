package com.gamtan.frame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Backup auto-start: launches the frame after the device finishes booting.
 * (When this app is also set as the device's Home/launcher, it starts on boot
 * anyway — this receiver covers devices where it is not set as Home.)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (Exception ignore) {}
        }
    }
}
