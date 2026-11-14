package com.carmaps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the floating map back after the car (head unit) boots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        if (!prefs.enabled() || (!updated && !prefs.startOnBoot())) return;
        if (!Settings.canDrawOverlays(context)) return;
        MapService.start(context);
    }
}
