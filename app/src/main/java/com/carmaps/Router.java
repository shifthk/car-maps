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
    private static final String AGENT = "CarMaps/2.11 (Android; +https://github.com/shifthk/car-maps)";
    private static final String TOMTOM = "https://api.tomtom.com/";

    /** How the last TomTom route request went, for the settings screen. */
    static volatile String trafficStatus = "No route asked for yet";

    private Router() {
    }

    /** An error answer from a server, with its HTTP status. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Driving routes, best first: from TomTom, with live traffic, when the user has given a key;
     * otherwise, or if TomTom fails, from the OpenStreetMap router (no traffic).
     *
     * @param alternatives how many other routes to ask for besides the best one
     */
    static List<Nav.Route> find(Location from, Dest to, boolean avoidTolls, boolean avoidHighways, String tomtomKey,
                                int alternatives) throws IOException, JSONException {
        if (!tomtomKey.isEmpty()) {
            try {
                JSONArray found;
                try {
                    found = tomtom(from, to, avoidTolls, avoidHighways, tomtomKey, alternatives, true);
                } catch (HttpError e) {
                    if (e.code != 400) throw e;
                    // TomTom turned down one of the extras (tolls, limits, lanes): ask for the plain route.
                    found = tomtom(from, to, avoidTolls, avoidHighways, tomtomKey, alternatives, false);
                }
                List<Nav.Route> out = new ArrayList<>();
                for (int i = 0; i < found.length(); i++) {
                    try {
                        out.add(Nav.Route.parseTomTom(found.getJSONObject(i)));
                    } catch (JSONException e) {
                        if (i == 0) throw e;   // an alternative that can't be read is just left out
                    }
                }
                Nav.Route r = out.get(0);
                trafficStatus = "Last route came from TomTom with live traffic"
                        + (r.trafficDelaySec >= 60 ? " (" + Geo.duration(r.trafficDelaySec) + " delay)" : "");
                return out;
            } catch (IOException | JSONException e) {
                String why = e instanceof JSONException ? "unexpected answer (" + e.getMessage() + ")" : e.getMessage();
                trafficStatus = "TomTom route failed: " + why + " Used the OpenStreetMap router instead.";
            }
        }
        List<Nav.Route> out = new ArrayList<>();
        List<JSONObject> trips = route(from, to, avoidTolls, avoidHighways, alternatives);
        for (int i = 0; i < trips.size(); i++) {
            try {
                out.add(Nav.Route.parse(trips.get(i)));
            } catch (JSONException e) {
                if (i == 0) throw e;
            }
        }
        return out;
    }

    /** @return "routes" from TomTom's Calculate Route, with text instructions */
    private static JSONArray tomtom(Location from, Dest to, boolean avoidTolls, boolean avoidHighways, String key,
                                    int alternatives, boolean extras) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(TOMTOM).append(String.format(Locale.US,
                "routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json", from.getLatitude(), from.getLongitude(),
                to.lat, to.lng));
        url.append("?key=").append(URLEncoder.encode(key, "UTF-8"))
                .append("&traffic=true&travelMode=car&routeType=fastest&instructionsType=text&language=")
                .append(language());
        if (avoidTolls) url.append("&avoid=tollRoads");
        if (avoidHighways) url.append("&avoid=motorways");
        if (alternatives > 0) url.append("&maxAlternatives=").append(alternatives);
        // Which stretches are tolled, what the speed limits are, and which lanes to be in.
        if (extras) url.append("&sectionType=toll&sectionType=speedLimit&sectionType=lanes");
        if (from.hasBearing() && from.hasSpeed() && from.getSpeed() > 3f) {
            url.append("&vehicleHeading=").append(Math.round(from.getBearing()) % 360);
        }
        String answer = http("GET", url.toString(), null);
        Usage.add(Usage.ROUTE);
        JSONArray routes = new JSONObject(answer).optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IOException("No route found.");
        return routes;
    }

    /** Something on the road that TomTom is reporting: a crash, roadworks, a closure. */
    static final class Incident {
        String id = "";
        /** TomTom's icon category: 1 accident, 7 lane closed, 8 road closed, 9 roadworks and so on. */
        int category;
        /** Where its marker goes. */
        double lat, lng;
        /** Its first and last points, as lat, lng, lat, lng, in the direction of the traffic it affects. */
        double[] line = new double[0];
        /** Metres along the route being followed, or -1 if it isn't on it. */
        double routeDist = -1;
    }

    static String incidentName(int category) {
        switch (category) {
            case 1: return "Accident";
            case 2: return "Fog";
            case 3: return "Dangerous conditions";
            case 4: return "Rain";
            case 5: return "Ice";
            case 7: return "Lane closed";
            case 8: return "Road closed";
            case 9: return "Roadworks";
            case 10: return "Strong wind";
            case 11: return "Flooding";
            case 14: return "Broken down vehicle";
            default: return "Incident";
        }
    }

    /** Incidents within about 20 km of a place, from TomTom. Jams are left out: the traffic colours show those. */
    static List<Incident> incidents(double lat, double lng, String key) throws IOException, JSONException {
        String fields = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory}}}";
        String url = TOMTOM + "traffic/services/5/incidentDetails?key=" + URLEncoder.encode(key, "UTF-8")
                + String.format(Locale.US, "&bbox=%.4f,%.4f,%.4f,%.4f", lng - 0.24, lat - 0.2, lng + 0.24, lat + 0.2)
                + "&fields=" + URLEncoder.encode(fields, "UTF-8")
                + "&language=en-GB&categoryFilter=1,3,5,7,8,9,11,14&timeValidityFilter=present";
        String answer = http("GET", url, null);
        Usage.add(Usage.INCIDENT);
        JSONArray list = new JSONObject(answer).optJSONArray("incidents");
        List<Incident> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            JSONObject g = o.optJSONObject("geometry");
            JSONObject p = o.optJSONObject("properties");
            JSONArray at = g == null ? null : g.optJSONArray("coordinates");
            if (p == null || at == null || at.length() == 0) continue;
            Incident inc = new Incident();
            inc.id = p.optString("id", "i" + i);
            inc.category = p.optInt("iconCategory");
            if ("Point".equals(g.optString("type"))) {
                if (at.length() < 2) continue;
                inc.line = new double[]{at.getDouble(1), at.getDouble(0)};
            } else {
                JSONArray first = at.optJSONArray(0), last = at.optJSONArray(at.length() - 1);
                if (first == null || last == null || first.length() < 2 || last.length() < 2) continue;
                inc.line = new double[]{first.getDouble(1), first.getDouble(0), last.getDouble(1), last.getDouble(0)};
            }
            inc.lat = inc.line[0];
            inc.lng = inc.line[1];
            out.add(inc);
        }
        return out;
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
            if (code == 200) {
                Usage.add(Usage.TRAFFIC);
                return null;
            }
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

    /** @return Valhalla's "trip" for the best driving route, followed by those of any alternatives */
    static List<JSONObject> route(Location from, Dest to, boolean avoidTolls, boolean avoidHighways, int alternatives)
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
        if (alternatives > 0) body.put("alternates", alternatives);
        JSONObject res = new JSONObject(http("POST", ROUTE, body.toString()));
        JSONObject trip = res.optJSONObject("trip");
        if (trip == null) throw new IOException("No route found");
        List<JSONObject> out = new ArrayList<>();
        out.add(trip);
        JSONArray others = res.optJSONArray("alternates");
        for (int i = 0; others != null && i < others.length(); i++) {
            JSONObject other = others.getJSONObject(i).optJSONObject("trip");
            if (other != null) out.add(other);
        }
        return out;
    }

    /** How the last TomTom search went, for the settings screen. */
    static volatile String searchStatus = "";

    /** Places matching what was typed: from TomTom when the user has given a key, otherwise (or if it fails) Photon. */
    static List<Dest> search(String text, Location near, String tomtomKey) throws IOException, JSONException {
        if (!tomtomKey.isEmpty()) {
            try {
                List<Dest> found = tomtomSearch(text, near, tomtomKey);
                searchStatus = "Search: TomTom";
                return found;
            } catch (IOException | JSONException e) {
                String why = e instanceof JSONException ? "unexpected answer (" + e.getMessage() + ")" : e.getMessage();
                searchStatus = "TomTom search failed: " + why + " Used the OpenStreetMap search instead.";
            }
        }
        return search(text, near);
    }

    private static List<Dest> tomtomSearch(String text, Location near, String key) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(TOMTOM).append("search/2/search/")
                .append(URLEncoder.encode(text, "UTF-8").replace("+", "%20")).append(".json?key=")
                .append(URLEncoder.encode(key, "UTF-8")).append("&typeahead=true&limit=8&language=").append(language());
        if (near != null) {
            url.append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", near.getLatitude(), near.getLongitude()));
        }
        String answer = http("GET", url.toString(), null);
        Usage.add(Usage.SEARCH);
        JSONArray results = new JSONObject(answer).optJSONArray("results");
        List<Dest> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            JSONObject at = r.optJSONObject("position");
            if (at == null || !at.has("lat") || !at.has("lon")) continue;
            JSONObject poi = r.optJSONObject("poi");
            JSONObject address = r.optJSONObject("address");
            String full = address == null ? "" : address.optString("freeformAddress");
            String name = poi == null ? "" : poi.optString("name");
            String detail = full;
            if (name.isEmpty()) {
                // An address, street or town: its first part is the name and the rest says where it is.
                int comma = full.indexOf(", ");
                name = comma < 0 ? full : full.substring(0, comma);
                detail = comma < 0 ? "" : full.substring(comma + 2);
            }
            if (name.isEmpty()) continue;
            Dest d = new Dest(name, detail, at.getDouble("lat"), at.getDouble("lon"));
            if (near != null) {
                d.distance = Geo.distance(Geo.dist(near.getLatitude(), near.getLongitude(), d.lat, d.lng));
            }
            out.add(d);
        }
        return out;
    }

    /** @return places from Photon matching what was typed, nearest-first where the names match equally well */
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
            c.setRequestProperty("User-Agent", url.startsWith(TOMTOM) ? "CarMaps/2.11" : AGENT);
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
                throw new HttpError(code, message);
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
