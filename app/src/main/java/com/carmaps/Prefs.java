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

    /** Map colours: 0 light, 1 dark, 2 dark between sunset and sunrise, 3 follow the car's day/night theme. */
    int theme() { return p.getInt("theme", p.getBoolean("dark", false) ? 1 : 2); }
    void setTheme(int v) { p.edit().putInt("theme", v).apply(); }

    /** While following the car, turn the map so the way ahead is up. */
    boolean headingUp() { return p.getBoolean("headingUp", true); }
    void setHeadingUp(boolean v) { p.edit().putBoolean("headingUp", v).apply(); }

    /** Show TomTom's incidents on the map and warn about those on the route. */
    boolean incidents() { return p.getBoolean("incidents", true); }
    void setIncidents(boolean v) { p.edit().putBoolean("incidents", v).apply(); }

    /** A saved place: slot is "home" or "work". Null if not set. */
    Router.Dest place(String slot) {
        try {
            String s = p.getString("place_" + slot, "");
            return s.isEmpty() ? null : Router.Dest.from(new org.json.JSONObject(s));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    void setPlace(String slot, Router.Dest d) {
        try {
            if (d == null) p.edit().remove("place_" + slot).apply();
            else p.edit().putString("place_" + slot, d.json().toString()).apply();
        } catch (org.json.JSONException ignored) {
            // nothing saved
        }
    }

    /** Other saved places, oldest first. */
    java.util.List<Router.Dest> favourites() {
        java.util.List<Router.Dest> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString("favourites", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Router.Dest.from(a.getJSONObject(i)));
        } catch (org.json.JSONException ignored) {
            // a damaged list is just an empty one
        }
        return out;
    }

    static boolean same(Router.Dest a, Router.Dest b) {
        return a != null && b != null && a.name.equals(b.name)
                && Math.abs(a.lat - b.lat) < 1e-5 && Math.abs(a.lng - b.lng) < 1e-5;
    }

    boolean isFavourite(Router.Dest d) {
        for (Router.Dest o : favourites()) if (same(o, d)) return true;
        return false;
    }

    void setFavourite(Router.Dest d, boolean on) {
        java.util.List<Router.Dest> list = favourites();
        list.removeIf(o -> same(o, d));
        if (on) list.add(d);
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (int i = Math.max(0, list.size() - 8); i < list.size(); i++) a.put(list.get(i).json());
        } catch (org.json.JSONException ignored) {
            return;
        }
        p.edit().putString("favourites", a.toString()).apply();
    }

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

    /** Try the "display over other apps" kind of window on the HUD before the second-screen kind. */
    boolean hudOverlay() { return p.getBoolean("hudOverlay", false); }
    void setHudOverlay(boolean v) { p.edit().putBoolean("hudOverlay", v).apply(); }

    /** -1: find it automatically. */
    int hudDisplay() { return p.getInt("hudDisplay", -1); }
    void setHudDisplay(int v) { p.edit().putInt("hudDisplay", v).apply(); }

    /** 0 = far left, 100 = far right. */
    int hudX() { return p.getInt("hudX", 50); }
    void setHudX(int v) { p.edit().putInt("hudX", v).apply(); }

    /** Width of the empty gap between the directions and the mini map, as a percentage of the HUD's width. */
    int hudGap() { return p.getInt("hudGap", 30); }
    void setHudGap(int v) { p.edit().putInt("hudGap", v).apply(); }

    /** The mini map's width and height, each as a percentage of the HUD's height. */
    int hudMapW() { return p.getInt("hudMapW", 100); }
    void setHudMapW(int v) { p.edit().putInt("hudMapW", v).apply(); }

    int hudMapH() { return p.getInt("hudMapH", 100); }
    void setHudMapH(int v) { p.edit().putInt("hudMapH", v).apply(); }

    /** Height of the directions as a percentage of the HUD's height, and where they sit (0 top, 100 bottom). */
    int hudInfoSize() { return p.getInt("hudInfoSize", 70); }
    void setHudInfoSize(int v) { p.edit().putInt("hudInfoSize", v).apply(); }

    int hudInfoY() { return p.getInt("hudInfoY", 50); }
    void setHudInfoY(int v) { p.edit().putInt("hudInfoY", v).apply(); }

    /** A map of the roads around the car on the HUD while no route is being followed. */
    boolean hudSurround() { return p.getBoolean("hudSurround", true); }
    void setHudSurround(boolean v) { p.edit().putBoolean("hudSurround", v).apply(); }

    /** Diameter of the empty circle in the middle of that map, as a percentage of the HUD's height. */
    int hudHole() { return p.getInt("hudHole", 70); }
    void setHudHole(int v) { p.edit().putInt("hudHole", v).apply(); }

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
