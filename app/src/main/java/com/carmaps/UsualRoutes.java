package com.carmaps;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The ways the driver actually goes. Each time a route is followed to the end, the line the car drove
 * is kept, filed under where it started and where it went. Asked for a route to the same place again
 * from somewhere along that line, the line from there on is given back, for the router to follow.
 *
 * <p>A journey that mostly matches one already kept counts towards it, so the way taken most often
 * wins over a one-off detour. Kept in a file on this device only.</p>
 */
final class UsualRoutes {

    private static final String TAG = "UsualRoutes";
    /** The same destination: within this far. */
    private static final double SAME_PLACE_M = 300;
    /** The same start, when filing a journey. */
    private static final double SAME_START_M = 1000;
    /** Starting from somewhere along a kept line counts: within this far of it. */
    private static final double ON_LINE_M = 400;
    /** A journey is the same way as a kept one when this much of it runs within {@link #NEAR_M} of it. */
    private static final double SAME_WAY = 0.8;
    private static final double NEAR_M = 60;
    /** Points of a kept line are about this far apart. */
    private static final double SPACING_M = 40;
    /** Lines shorter than this aren't worth keeping or following. */
    private static final double MIN_LENGTH_M = 1000;
    private static final int MAX_KEPT = 60;

    /** A way of getting somewhere, as the car drove it. */
    static final class Way {
        double[] lat, lng;
        double toLat, toLng;
        String toName = "";
        /** How many journeys went this way. */
        int count;
        long lastUsed;
    }

    private final File file;
    private final List<Way> ways = new ArrayList<>();
    private static UsualRoutes instance;

    /** The one copy, shared by the map and the settings screen. */
    static synchronized UsualRoutes get(Context c) {
        if (instance == null) instance = new UsualRoutes(c.getApplicationContext());
        return instance;
    }

    private UsualRoutes(Context c) {
        file = new File(c.getFilesDir(), "usual_routes.json");
        load();
    }

    synchronized int size() {
        return ways.size();
    }

    synchronized void forgetAll() {
        ways.clear();
        save();
    }

    /**
     * The usual way from {@code (fromLat, fromLng)} to {@code (toLat, toLng)}: the rest of the kept line
     * from the point on it nearest the start, or null if no kept line starts near here or passes close by.
     */
    synchronized Way find(double fromLat, double fromLng, double toLat, double toLng) {
        Way best = null;
        for (Way w : ways) {
            if (Geo.dist(w.toLat, w.toLng, toLat, toLng) > SAME_PLACE_M) continue;
            int at = nearest(w, fromLat, fromLng);
            if (at < 0 || Geo.dist(w.lat[at], w.lng[at], fromLat, fromLng) > ON_LINE_M) continue;
            if (best == null || w.count > best.count || (w.count == best.count && w.lastUsed > best.lastUsed)) {
                best = w;
            }
        }
        return best == null ? null : rest(best, fromLat, fromLng);
    }

    /** The rest of {@code w} from the point on it nearest here; null if here is off it or little is left. */
    static Way rest(Way w, double lat, double lng) {
        int at = nearest(w, lat, lng);
        if (at < 0 || Geo.dist(w.lat[at], w.lng[at], lat, lng) > ON_LINE_M) return null;
        Way r = new Way();
        int n = w.lat.length - at;
        r.lat = new double[n];
        r.lng = new double[n];
        System.arraycopy(w.lat, at, r.lat, 0, n);
        System.arraycopy(w.lng, at, r.lng, 0, n);
        if (length(r.lat, r.lng) < MIN_LENGTH_M) return null;
        r.toLat = w.toLat;
        r.toLng = w.toLng;
        r.toName = w.toName;
        r.count = w.count;
        r.lastUsed = w.lastUsed;
        return r;
    }

    /**
     * Files a journey that ended at the destination.
     *
     * @param driven the car's positions ({lat, lng}) from the start of guidance to the end, in order
     */
    synchronized void remember(List<double[]> driven, double toLat, double toLng, String toName) {
        Way w = thin(driven);
        if (w == null) return;
        w.toLat = toLat;
        w.toLng = toLng;
        w.toName = toName == null ? "" : toName;
        w.count = 1;
        w.lastUsed = System.currentTimeMillis();
        for (Way k : ways) {
            if (Geo.dist(k.toLat, k.toLng, toLat, toLng) > SAME_PLACE_M) continue;
            if (Geo.dist(k.lat[0], k.lng[0], w.lat[0], w.lng[0]) > SAME_START_M) continue;
            if (overlap(w, k) >= SAME_WAY && overlap(k, w) >= SAME_WAY) {
                // The same way again: keep the newest line (roads change) and count the journey.
                k.lat = w.lat;
                k.lng = w.lng;
                k.toLat = toLat;
                k.toLng = toLng;
                if (!w.toName.isEmpty()) k.toName = w.toName;
                k.count++;
                k.lastUsed = w.lastUsed;
                save();
                return;
            }
        }
        ways.add(w);
        while (ways.size() > MAX_KEPT) {
            Way oldest = ways.get(0);
            for (Way k : ways) if (k.lastUsed < oldest.lastUsed) oldest = k;
            ways.remove(oldest);
        }
        save();
    }

    /** How much of {@code a}'s line (by points) runs close to {@code b}'s. */
    static double overlap(Way a, Way b) {
        return overlap(a.lat, a.lng, b.lat, b.lng);
    }

    static double overlap(double[] aLat, double[] aLng, double[] bLat, double[] bLng) {
        if (aLat.length == 0 || bLat.length < 2) return 0;
        int near = 0;
        for (int i = 0; i < aLat.length; i++) {
            if (distToLine(aLat[i], aLng[i], bLat, bLng) <= NEAR_M) near++;
        }
        return near / (double) aLat.length;
    }

    /** Index of the point of {@code w}'s line nearest the given place. */
    private static int nearest(Way w, double lat, double lng) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < w.lat.length; i++) {
            double d = Geo.dist(w.lat[i], w.lng[i], lat, lng);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Distance from a place to the nearest point of a line, measured flat (fine over a few hundred metres). */
    private static double distToLine(double lat, double lng, double[] ls, double[] gs) {
        double k = Math.cos(Math.toRadians(lat)) * 111320, m = 110540;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < ls.length; i++) {
            double ax = (gs[i] - lng) * k, ay = (ls[i] - lat) * m;
            double bx = (gs[i + 1] - lng) * k, by = (ls[i + 1] - lat) * m;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double px = ax + dx * t, py = ay + dy * t;
            best = Math.min(best, Math.sqrt(px * px + py * py));
        }
        return best;
    }

    static double length(double[] lat, double[] lng) {
        double d = 0;
        for (int i = 1; i < lat.length; i++) d += Geo.dist(lat[i - 1], lng[i - 1], lat[i], lng[i]);
        return d;
    }

    /** The positions, a point every {@link #SPACING_M} or so; null if the journey was too short. */
    private static Way thin(List<double[]> driven) {
        if (driven == null || driven.size() < 2) return null;
        List<double[]> kept = new ArrayList<>();
        double[] lastKept = null;
        for (double[] p : driven) {
            if (lastKept == null || Geo.dist(lastKept[0], lastKept[1], p[0], p[1]) >= SPACING_M) {
                kept.add(p);
                lastKept = p;
            }
        }
        double[] end = driven.get(driven.size() - 1);
        if (kept.get(kept.size() - 1) != end) kept.add(end);
        Way w = new Way();
        w.lat = new double[kept.size()];
        w.lng = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            w.lat[i] = kept.get(i)[0];
            w.lng[i] = kept.get(i)[1];
        }
        return length(w.lat, w.lng) < MIN_LENGTH_M ? null : w;
    }

    // ------------------------------------------------------------------ the file

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONArray a = new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray pts = o.getJSONArray("pts");
                Way w = new Way();
                int n = pts.length() / 2;
                w.lat = new double[n];
                w.lng = new double[n];
                for (int k = 0; k < n; k++) {
                    w.lat[k] = pts.getInt(2 * k) / 1e5;
                    w.lng[k] = pts.getInt(2 * k + 1) / 1e5;
                }
                w.toLat = o.getDouble("toLat");
                w.toLng = o.getDouble("toLng");
                w.toName = o.optString("toName");
                w.count = o.optInt("count", 1);
                w.lastUsed = o.optLong("last");
                if (n >= 2) ways.add(w);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Couldn't read the usual routes", e);
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Way w : ways) {
                JSONArray pts = new JSONArray();
                for (int k = 0; k < w.lat.length; k++) {
                    pts.put(Math.round(w.lat[k] * 1e5)).put(Math.round(w.lng[k] * 1e5));
                }
                a.put(new JSONObject().put("toLat", w.toLat).put("toLng", w.toLng).put("toName", w.toName)
                        .put("count", w.count).put("last", w.lastUsed).put("pts", pts));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Couldn't write the usual routes", e);
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Couldn't write the usual routes", e);
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "Couldn't replace the usual routes file");
    }
}
