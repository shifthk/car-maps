package com.carmaps;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turn-by-turn routes from OpenStreetMap data, with no key or account: the Valhalla routing server
 * run by FOSSGIS for the OpenStreetMap project, and Nominatim to find a place by name when the Google
 * Maps page didn't give its coordinates. Both are free community services meant for light personal
 * use. Blocking; call off the main thread.
 */
final class Router {

    private static final String ROUTE = "https://valhalla1.openstreetmap.de/route";
    private static final String TRACE = "https://valhalla1.openstreetmap.de/trace_route";
    private static final String SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final String AGENT = "CarMaps/1.0 (Android; personal in-car navigation)";

    private Router() {
    }

    /** Where to go. */
    static final class Dest {
        final String name;
        final double lat, lng;

        Dest(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }

        /** The part before the first comma: "Hong Kong City Hall, 5 Edinburgh Pl, ..." → "Hong Kong City Hall". */
        String shortName() {
            int comma = name.indexOf(',');
            String s = (comma > 0 ? name.substring(0, comma) : name).trim();
            return s.isEmpty() ? "your destination" : s;
        }
    }

    /** @return Valhalla's "trip" for the best driving route */
    static JSONObject route(Location from, Dest to, boolean avoidTolls, boolean avoidHighways)
            throws IOException, JSONException {
        JSONObject origin = new JSONObject().put("lat", from.getLatitude()).put("lon", from.getLongitude());
        // While moving, say which way the car is facing so the route doesn't start with a U-turn.
        if (from.hasBearing() && from.hasSpeed() && from.getSpeed() > 3f) {
            origin.put("heading", Math.round(from.getBearing()) % 360).put("heading_tolerance", 60);
        }
        JSONObject body = new JSONObject()
                .put("locations", new JSONArray()
                        .put(origin)
                        .put(new JSONObject().put("lat", to.lat).put("lon", to.lng)))
                .put("costing", "auto")
                .put("units", "kilometers")
                .put("language", language())
                .put("costing_options", new JSONObject().put("auto", new JSONObject()
                        .put("use_tolls", avoidTolls ? 0 : 0.5)
                        .put("use_highways", avoidHighways ? 0 : 1)));
        JSONObject res = new JSONObject(http("POST", ROUTE, body.toString()));
        JSONObject trip = res.optJSONObject("trip");
        if (trip == null) throw new IOException("No route found");
        return trip;
    }

    /**
     * Turn-by-turn directions along a line someone else drew (Google's route): the line is matched
     * onto OpenStreetMap's roads and described as a route.
     *
     * @param line    encoded polyline, six decimals
     * @param metres  how long the line is supposed to be
     * @return Valhalla's "trip" along the line, or null if the roads it found don't add up to the line
     */
    static JSONObject trace(String line, double metres) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("encoded_polyline", line)
                .put("costing", "auto")
                .put("shape_match", "map_snap")
                .put("units", "kilometers")
                .put("language", language());
        JSONObject res = new JSONObject(http("POST", TRACE, body.toString()));
        // Where the line can't be followed in one go (it starts in a car park, say), the pieces come
        // back separately: the first as "trip", the rest as "alternates". Take the longest.
        JSONObject best = res.optJSONObject("trip");
        JSONArray others = res.optJSONArray("alternates");
        if (others != null) {
            for (int i = 0; i < others.length(); i++) {
                JSONObject t = others.getJSONObject(i).optJSONObject("trip");
                if (t != null && (best == null || length(t) > length(best))) best = t;
            }
        }
        if (best == null) return null;
        double found = length(best) * 1000;
        // A piece much shorter than the line means part of the route is missing; much longer means a detour.
        if (metres > 0 && (found < metres * 0.9 || found > metres * 1.1)) return null;
        return best;
    }

    private static double length(JSONObject trip) {
        JSONObject summary = trip.optJSONObject("summary");
        return summary == null ? 0 : summary.optDouble("length", 0);
    }

    /** @return {lat, lng} of the best match for a place name, or null */
    static double[] geocode(String name) throws IOException, JSONException {
        String url = SEARCH + "?format=jsonv2&limit=1&q=" + URLEncoder.encode(name, "UTF-8");
        JSONArray list = new JSONArray(http("GET", url, null));
        if (list.length() == 0) return null;
        JSONObject first = list.getJSONObject(0);
        return new double[]{first.getDouble("lat"), first.getDouble("lon")};
    }

    /** Valhalla writes instructions in a handful of languages; anything else gets English. */
    private static String language() {
        String tag = Locale.getDefault().toLanguageTag();
        return tag.startsWith("en") ? tag.equals("en-GB") ? "en-GB" : "en-US" : "en-US";
    }

    private static String http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", AGENT);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String message = code == 429 ? "The routing service is busy. Try again in a moment."
                        : "The routing service returned error " + code;
                try {
                    String error = new JSONObject(text).optString("error");
                    if (!error.isEmpty()) message = error;
                } catch (JSONException ignored) {
                    // not JSON: keep the generic message
                }
                throw new IOException(message);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
