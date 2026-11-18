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

    /** The settings screen's last open tab. */
    int settingsTab() { return p.getInt("settingsTab", 0); }
    void setSettingsTab(int v) { p.edit().putInt("settingsTab", v).apply(); }

    /** Minutes before a traffic tile is fetched again; 0 = never (only new areas are fetched). */
    static final int[] TRAFFIC_MINUTES = {2, 5, 10, 15, 0};
    int trafficRefresh() { return Math.max(0, Math.min(TRAFFIC_MINUTES.length - 1, p.getInt("trafficRefresh", 1))); }
    void setTrafficRefresh(int v) { p.edit().putInt("trafficRefresh", v).apply(); }
    /** @return how long a traffic tile stays fresh, or Long.MAX_VALUE for never re-fetching */
    long trafficFreshMs() {
        int m = TRAFFIC_MINUTES[trafficRefresh()];
        return m == 0 ? Long.MAX_VALUE : m * 60_000L;
    }

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








    // ---- where the two HUD panels sit: {left, top, width, height} in thousandths of the HUD's width and height

    /** CARD: the now playing card, and CLOCK: the time and date, shown while no route is being followed. */
    static final int INFO = 0, MINIMAP = 1, CARD = 2, CLOCK = 3;
    private static final String[] BOX_KEYS = {"hudBoxInfo", "hudBoxMap", "hudBoxCard", "hudBoxClock"};

    int[] hudBox(int which) {
        String v = p.getString(BOX_KEYS[which], null);
        if (v != null) {
            try {
                String[] t = v.split(",");
                return new int[]{Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3])};
            } catch (RuntimeException ignored) {
                // fall through to the layout the older sliders gave
            }
        }
        // The card started out in the directions' box, so until it is moved it stays there.
        if (which == CARD) return hudBox(INFO);
        if (which == CLOCK) {
            // Until 2.19 the clock shared the card's box; otherwise it starts opposite the card.
            int[] c = hudBox(CARD);
            if (hudIdle() == IDLE_CLOCK) return c;
            return opposite(c);
        }
        return legacyBoxes()[which];
    }

    /** The same box mirrored to the other side of the HUD. */
    private static int[] opposite(int[] c) {
        return new int[]{Math.max(0, 1000 - c[0] - c[2]), c[1], c[2], c[3]};
    }

    void setHudBox(int which, int[] b) {
        p.edit().putString(BOX_KEYS[which], b[0] + "," + b[1] + "," + b[2] + "," + b[3]).apply();
    }

    void resetHudBoxes() {
        p.edit().remove(BOX_KEYS[0]).remove(BOX_KEYS[1]).remove(BOX_KEYS[2]).remove(BOX_KEYS[3])
                .remove("hudGap").remove("hudX").remove("hudInfoSize").remove("hudInfoY")
                .remove("hudMapW").remove("hudMapH").apply();
    }

    /**
     * The two panels where versions up to 2.11 put them from their sliders (gap, left/right, size,
     * up/down, mini map width and height), worked out for a 1440 x 480 HUD, so updating moves nothing.
     */
    private int[][] legacyBoxes() {
        int w = 1440, h = 480;
        int infoH = Math.max(48, Math.min(h, Math.round(h * p.getInt("hudInfoSize", 70) / 100f)));
        int infoTop = Math.round((h - infoH) * p.getInt("hudInfoY", 50) / 100f);
        int infoW = Math.round(infoH * 2.6f);
        int mapW = Math.max(24, Math.min(w / 2, Math.round(h * p.getInt("hudMapW", 100) / 100f)));
        int mapH = Math.max(24, Math.min(h, Math.round(h * p.getInt("hudMapH", 100) / 100f)));
        int smallest = Math.round(h * 0.08f);
        int gap = Math.max(smallest, Math.round(w * p.getInt("hudGap", 30) / 100f));
        if (infoW + gap + mapW > w) {
            gap = Math.max(smallest, Math.min(gap, w - mapW - Math.round(infoH * 0.8f)));
            infoW = Math.max(0, w - mapW - gap);
        }
        int left = Math.round(Math.max(0, w - infoW - gap - mapW) * p.getInt("hudX", 50) / 100f);
        int mapTop = Math.max(0, (h - mapH) / 2);
        return new int[][]{
                {left * 1000 / w, infoTop * 1000 / h, Math.max(50, infoW * 1000 / w), infoH * 1000 / h},
                {(left + infoW + gap) * 1000 / w, mapTop * 1000 / h, mapW * 1000 / w, mapH * 1000 / h}};
    }

    /** What the HUD shows while no route is being followed. */
    static final int IDLE_NOTHING = 0, IDLE_MAP = 1, IDLE_MUSIC = 2, IDLE_CLOCK = 3, IDLE_BOTH = 4;

    /** Until 2.12 there was only an on/off switch for the map. */
    int hudIdle() { return p.getInt("hudIdle", p.getBoolean("hudSurround", true) ? IDLE_MAP : IDLE_NOTHING); }
    void setHudIdle(int v) {
        // The clock used to share the card's box: it keeps that spot, and the card moves to the other side.
        if (hudIdle() == IDLE_CLOCK && v != IDLE_CLOCK && !p.contains(BOX_KEYS[CLOCK])) {
            int[] c = hudBox(CARD);
            setHudBox(CLOCK, c);
            setHudBox(CARD, opposite(c));
        }
        p.edit().putInt("hudIdle", v).apply();
    }

    /** Now playing is on the HUD while no route is being followed. */
    boolean hudMusic() { int i = hudIdle(); return i == IDLE_MUSIC || i == IDLE_BOTH; }

    /** The time and date are on the HUD while no route is being followed. */
    boolean hudClock() { int i = hudIdle(); return i == IDLE_CLOCK || i == IDLE_BOTH; }

    // ---- Spotify (the user's own app registration and the tokens from signing in; never a password)

    String spotifyClientId() { return p.getString("spotifyClientId", ""); }
    void setSpotifyClientId(String v) { p.edit().putString("spotifyClientId", v == null ? "" : v.trim()).apply(); }

    String spotifyVerifier() { return p.getString("spotifyVerifier", ""); }
    void setSpotifyVerifier(String v) { p.edit().putString("spotifyVerifier", v).apply(); }

    String spotifyAccess() { return p.getString("spotifyAccess", ""); }
    String spotifyRefresh() { return p.getString("spotifyRefresh", ""); }
    long spotifyExpiry() { return p.getLong("spotifyExpiry", 0); }

    void setSpotifyTokens(String access, String refresh, long expiry) {
        p.edit().putString("spotifyAccess", access).putString("spotifyRefresh", refresh)
                .putLong("spotifyExpiry", expiry).apply();
    }

    void signOutSpotify() {
        p.edit().remove("spotifyAccess").remove("spotifyRefresh").remove("spotifyExpiry")
                .remove("spotifyVerifier").apply();
    }

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
