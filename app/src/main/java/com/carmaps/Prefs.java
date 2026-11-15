package com.carmaps;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the app remembers. */
final class Prefs {


    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("carmaps", Context.MODE_PRIVATE);
    }

    boolean enabled() { return p.getBoolean("enabled", false); }
    void setEnabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    boolean startOnBoot() { return p.getBoolean("boot", true); }
    void setStartOnBoot(boolean v) { p.edit().putBoolean("boot", v).apply(); }

    boolean avoidTolls() { return p.getBoolean("avoidTolls", false); }
    void setAvoidTolls(boolean v) { p.edit().putBoolean("avoidTolls", v).apply(); }

    boolean avoidHighways() { return p.getBoolean("avoidHighways", false); }
    void setAvoidHighways(boolean v) { p.edit().putBoolean("avoidHighways", v).apply(); }

    boolean darkMap() { return p.getBoolean("dark", false); }
    void setDarkMap(boolean v) { p.edit().putBoolean("dark", v).apply(); }

    /** The user's own TomTom API key; empty for none (then there is no traffic anywhere in the app). */
    String tomtomKey() { return p.getString("tomtomKey", ""); }
    void setTomtomKey(String v) { p.edit().putString("tomtomKey", v == null ? "" : v.trim()).apply(); }

    /** Traffic colours on the map (needs the key). */
    boolean traffic() { return p.getBoolean("traffic", true); }
    void setTraffic(boolean v) { p.edit().putBoolean("traffic", v).apply(); }

    long lastUpdateCheck() { return p.getLong("updateCheck", 0); }
    void setLastUpdateCheck(long v) { p.edit().putLong("updateCheck", v).apply(); }

    /** Places picked before, newest first. */
    java.util.List<Router.Dest> recents() {
        java.util.List<Router.Dest> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString("recents", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Router.Dest.from(a.getJSONObject(i)));
        } catch (org.json.JSONException ignored) {
            // a damaged list is just an empty one
        }
        return out;
    }

    void addRecent(Router.Dest d) {
        java.util.List<Router.Dest> list = recents();
        list.removeIf(o -> o.name.equals(d.name) && o.detail.equals(d.detail));
        list.add(0, d);
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (int i = 0; i < Math.min(8, list.size()); i++) a.put(list.get(i).json());
        } catch (org.json.JSONException ignored) {
            return;
        }
        p.edit().putString("recents", a.toString()).apply();
    }

    boolean voice() { return p.getBoolean("voice", true); }
    void setVoice(boolean v) { p.edit().putBoolean("voice", v).apply(); }

    // ---- HUD

    boolean hud() { return p.getBoolean("hud", true); }
    void setHud(boolean v) { p.edit().putBoolean("hud", v).apply(); }

    boolean hudMap() { return p.getBoolean("hudMiniMap", true); }
    void setHudMap(boolean v) { p.edit().putBoolean("hudMiniMap", v).apply(); }

    /** Blank the car's own HUD readouts while the directions are showing. */
    boolean hudCover() { return p.getBoolean("hudCover", false); }
    void setHudCover(boolean v) { p.edit().putBoolean("hudCover", v).apply(); }

    /** -1: find it automatically. */
    int hudDisplay() { return p.getInt("hudDisplay", -1); }
    void setHudDisplay(int v) { p.edit().putInt("hudDisplay", v).apply(); }

    /** 0 = far left, 100 = far right. */
    int hudX() { return p.getInt("hudX", 50); }
    void setHudX(int v) { p.edit().putInt("hudX", v).apply(); }

    /** 0 = top, 100 = bottom. */
    int hudY() { return p.getInt("hudY", 50); }
    void setHudY(int v) { p.edit().putInt("hudY", v).apply(); }

    /** Panel height as a percentage of the HUD's height. */
    int hudSize() { return p.getInt("hudSize", 70); }
    void setHudSize(int v) { p.edit().putInt("hudSize", v).apply(); }

    int hudOpacity() { return p.getInt("hudOpacity", 100); }
    void setHudOpacity(int v) { p.edit().putInt("hudOpacity", v).apply(); }

    // ---- floating window

    boolean collapsed() { return p.getBoolean("collapsed", false); }
    void setCollapsed(boolean v) { p.edit().putBoolean("collapsed", v).apply(); }

    /** Top-left corner and size of the map when shown; null if never placed. */
    int[] window() {
        if (!p.contains("winW")) return null;
        return new int[]{p.getInt("winX", 0), p.getInt("winY", 0), p.getInt("winW", 0), p.getInt("winH", 0)};
    }

    void setWindow(int x, int y, int w, int h) {
        p.edit().putInt("winX", x).putInt("winY", y).putInt("winW", w).putInt("winH", h).apply();
    }

    void clearWindow() {
        p.edit().remove("winX").remove("winY").remove("winW").remove("winH").apply();
    }
}
