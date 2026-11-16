package com.carmaps;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Counts the requests this app has made to TomTom in the current calendar month, against the free
 * monthly allowance of each kind. TomTom offers no way to ask a key how much it has used, so this is
 * the app's own tally: it only knows about requests made from this device, and it starts each month
 * on the 1st (UTC), which may not be the day TomTom resets its count.
 */
final class Usage {

    static final int MAP = 0, TRAFFIC = 1, ROUTE = 2, SEARCH = 3, INCIDENT = 4;
    private static final String[] KEYS = {"map", "traffic", "route", "search", "incident"};
    private static final String[] NAMES = {"Map tiles", "Traffic tiles", "Routes", "Searches", "Incident checks"};
    /** TomTom's free requests per month for each kind, from its pricing page in November 2026. */
    private static final int[] FREE = {200000, 200000, 20000, 2500, 2500};
    private static final long SAVE_EVERY_MS = 20000;

    private static SharedPreferences store;
    private static final int[] counts = new int[KEYS.length];
    private static String month = "";
    private static boolean dirty;
    private static long savedAt;

    private Usage() {
    }

    static synchronized void init(Context c) {
        if (store != null) return;
        store = c.getApplicationContext().getSharedPreferences("carmaps_usage", Context.MODE_PRIVATE);
        month = store.getString("month", "");
        for (int i = 0; i < KEYS.length; i++) counts[i] = store.getInt(KEYS[i], 0);
        roll();
    }

    /** One more request of this kind was answered by TomTom. Safe to call from any thread. */
    static synchronized void add(int kind) {
        if (store == null) return;
        roll();
        counts[kind]++;
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - savedAt > SAVE_EVERY_MS) save();
    }

    static synchronized void flush() {
        if (store != null && dirty) save();
    }

    /** A few lines for the settings screen. */
    static synchronized String describe() {
        if (store == null) return "";
        roll();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            double percent = counts[i] * 100.0 / FREE[i];
            if (i > 0) sb.append('\n');
            sb.append(String.format(Locale.US, "%s: %,d of %,d free (%s%%)", NAMES[i], counts[i], FREE[i],
                    percent > 0 && percent < 1 ? "under 1" : String.valueOf(Math.round(percent))));
        }
        return sb.toString();
    }

    /** A new month starts the tally again. */
    private static void roll() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        String now = c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1);
        if (now.equals(month)) return;
        month = now;
        for (int i = 0; i < counts.length; i++) counts[i] = 0;
        save();
    }

    private static void save() {
        SharedPreferences.Editor e = store.edit().putString("month", month);
        for (int i = 0; i < KEYS.length; i++) e.putInt(KEYS[i], counts[i]);
        e.apply();
        dirty = false;
        savedAt = System.currentTimeMillis();
    }
}
