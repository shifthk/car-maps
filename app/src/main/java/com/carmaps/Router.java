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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Places and routes from OpenStreetMap data, with no key or account: Photon (run by Komoot) to find
 * places as you type, and the Valhalla routing server run by FOSSGIS for turn-by-turn driving routes.
 * Both are free community services meant for light personal use. Blocking; call off the main thread.
 */
final class Router {

    private static final String ROUTE = "https://valhalla1.openstreetmap.de/route";
    private static final String SEARCH = "https://photon.komoot.io/api/";
    private static final String AGENT = "CarMaps/2.1 (Android; +https://github.com/shifthk/car-maps)";
    private static final String TOMTOM = "https://api.tomtom.com/";

    /** How the last TomTom route request went, for the settings screen. */
    static volatile String trafficStatus = "No route asked for yet";

    private Router() {
    }

    /**
     * The best driving route: from TomTom, with live traffic, when the user has given a key; otherwise,
     * or if TomTom fails, from the OpenStreetMap router (no traffic).
     */
    static Nav.Route find(Location from, Dest to, boolean avoidTolls, boolean avoidHighways, String tomtomKey)
            throws IOException, JSONException {
        if (!tomtomKey.isEmpty()) {
            try {
                Nav.Route r = Nav.Route.parseTomTom(tomtom(from, to, avoidTolls, avoidHighways, tomtomKey));
                trafficStatus = "Last route came from TomTom with live traffic"
                        + (r.trafficDelaySec >= 60 ? " (" + Geo.duration(r.trafficDelaySec) + " delay)" : "");
                return r;
            } catch (IOException | JSONException e) {
                String why = e instanceof JSONException ? "unexpected answer (" + e.getMessage() + ")" : e.getMessage();
                trafficStatus = "TomTom route failed: " + why + " Used the OpenStreetMap router instead.";
            }
        }
        return Nav.Route.parse(route(from, to, avoidTolls, avoidHighways));
    }

    /** @return the first entry of "routes" from TomTom's Calculate Route, with text instructions */
    private static JSONObject tomtom(Location from, Dest to, boolean avoidTolls, boolean avoidHighways, String key)
            throws IOException, JSONException {
        StringBuilder url = new StringBuilder(TOMTOM).append(String.format(Locale.US,
                "routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json", from.getLatitude(), from.getLongitude(),
                to.lat, to.lng));
        url.append("?key=").append(URLEncoder.encode(key, "UTF-8"))
                .append("&traffic=true&travelMode=car&routeType=fastest&instructionsType=text&language=")
                .append(language());
        if (avoidTolls) url.append("&avoid=tollRoads");
        if (avoidHighways) url.append("&avoid=motorways");
        if (from.hasBearing() && from.hasSpeed() && from.getSpeed() > 3f) {
            url.append("&vehicleHeading=").append(Math.round(from.getBearing()) % 360);
        }
        JSONArray routes = new JSONObject(http("GET", url.toString(), null)).optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IOException("No route found.");
        return routes.getJSONObject(0);
    }

    /** Tries a TomTom key by fetching one traffic tile. @return null if it works, otherwise what went wrong */
    static String testKey(String key) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(TOMTOM + "traffic/map/4/tile/flow/relative0/10/942/614.png?key="
                    + URLEncoder.encode(key, "UTF-8")).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code == 200) return null;
            if (code == 401 || code == 403) return "TomTom didn't accept the key. Check it was typed exactly.";
            if (code == 429) return "The key works, but its free allowance is used up for now.";
            return "TomTom returned error " + code + ".";
        } catch (IOException e) {
            return "Couldn't reach TomTom. Check the internet connection.";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** A place: somewhere to go, or a search result. */
    static final class Dest {
        final String name;
        /** Street, town and so on; may be empty. */
        final String detail;
        final double lat, lng;
        /** How far away it is, for search results; may be empty. */
        String distance = "";

        Dest(String name, String detail, double lat, double lng) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lng = lng;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("n", name).put("d", detail).put("lat", lat).put("lng", lng);
        }

        static Dest from(JSONObject o) {
            return new Dest(o.optString("n"), o.optString("d"), o.optDouble("lat"), o.optDouble("lng"));
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

    /** @return places matching what was typed, nearest-first where the names match equally well */
    static List<Dest> search(String text, Location near) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(SEARCH).append("?limit=8&lang=en&q=")
                .append(URLEncoder.encode(text, "UTF-8"));
        if (near != null) {
            url.append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", near.getLatitude(), near.getLongitude()));
        }
        JSONArray features = new JSONObject(http("GET", url.toString(), null)).optJSONArray("features");
        List<Dest> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject p = f.optJSONObject("properties");
            JSONObject g = f.optJSONObject("geometry");
            JSONArray at = g == null ? null : g.optJSONArray("coordinates");
            if (p == null || at == null || at.length() < 2) continue;

            String street = p.optString("street");
            String number = p.optString("housenumber");
            String address = street.isEmpty() ? "" : number.isEmpty() ? street : number + " " + street;
            String name = p.optString("name");
            List<String> rest = new ArrayList<>();
            if (name.isEmpty()) name = address;
            else if (!address.isEmpty()) rest.add(address);
            for (String key : new String[]{"district", "city", "state", "country"}) {
                String v = p.optString(key);
                if (!v.isEmpty() && !v.equals(name) && !rest.contains(v)) rest.add(v);
            }
            if (name.isEmpty()) {
                if (rest.isEmpty()) continue;
                name = rest.remove(0);
            }
            Dest d = new Dest(name, String.join(", ", rest), at.getDouble(1), at.getDouble(0));
            if (near != null) {
                d.distance = Geo.distance(Geo.dist(near.getLatitude(), near.getLongitude(), d.lat, d.lng));
            }
            out.add(d);
        }
        return out;
    }

    /** Valhalla writes instructions in a handful of languages; anything else gets English. */
    private static String language() {
        return "en-GB".equals(Locale.getDefault().toLanguageTag()) ? "en-GB" : "en-US";
    }

    private static String http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            // The community services ask apps to say who they are; TomTom knows by the key.
            c.setRequestProperty("User-Agent", url.startsWith(TOMTOM) ? "CarMaps/2.1" : AGENT);
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
                boolean tomtom = url.startsWith(TOMTOM);
                String message = code == 429 ? (tomtom ? "the key's free allowance is used up for now."
                        : "The service is busy. Try again in a moment.")
                        : tomtom && (code == 401 || code == 403) ? "the key wasn't accepted."
                        : "The service returned error " + code + ".";
                try {
                    JSONObject o = new JSONObject(text);
                    String error = o.optString("error");
                    JSONObject detailed = o.optJSONObject("detailedError");
                    if (detailed != null && !detailed.optString("message").isEmpty() && code != 401 && code != 403 && code != 429) {
                        message = detailed.optString("message");
                    } else if (!tomtom && !error.isEmpty()) message = error;
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
