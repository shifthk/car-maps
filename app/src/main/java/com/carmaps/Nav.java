package com.carmaps;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A route and where the car is along it. Plain Java, no Android. */
final class Nav {

    /** Further than this from the route line (plus GPS error) counts as off the route. */
    private static final double OFF_ROUTE_M = 50;

    /** One instruction: the manoeuvre is made at {@link #startDist}, then the road is followed to {@link #endDist}. */
    static final class Step {
        String maneuver = "";
        String text = "";
        /** The same instruction worded for speech. */
        String spoken = "";
        double startDist, endDist;
        double seconds;
    }

    static final class Route {
        /** The line to follow: points and the distance travelled to reach each one. */
        double[] lat, lng, cum;
        final List<Step> steps = new ArrayList<>();
        double totalDist;
        double durationSec;
        /** secondsAfter[i]: driving time left once step i is finished. */
        double[] secondsAfter;

        /** @param trip the "trip" object of a Valhalla route response */
        static Route parse(JSONObject trip) throws JSONException {
            Route route = new Route();
            JSONArray legs = trip.getJSONArray("legs");
            JSONObject leg = legs.getJSONObject(0);
            double[] pts = Geo.decode(leg.getString("shape"), 1e6);
            int n = pts.length / 2;
            if (n < 2) throw new JSONException("Route has no line");
            route.lat = new double[n];
            route.lng = new double[n];
            route.cum = new double[n];
            for (int i = 0; i < n; i++) {
                route.lat[i] = pts[2 * i];
                route.lng[i] = pts[2 * i + 1];
                route.cum[i] = i == 0 ? 0
                        : route.cum[i - 1] + Geo.dist(route.lat[i - 1], route.lng[i - 1], route.lat[i], route.lng[i]);
            }
            route.totalDist = route.cum[n - 1];

            JSONArray maneuvers = leg.getJSONArray("maneuvers");
            for (int i = 0; i < maneuvers.length(); i++) {
                JSONObject m = maneuvers.getJSONObject(i);
                Step step = new Step();
                step.maneuver = maneuverName(m.optInt("type"));
                step.text = m.optString("instruction");
                step.spoken = m.optString("verbal_pre_transition_instruction", step.text);
                step.seconds = m.optDouble("time", 0);
                int begin = Math.max(0, Math.min(n - 1, m.optInt("begin_shape_index")));
                int end = Math.max(begin, Math.min(n - 1, m.optInt("end_shape_index")));
                step.startDist = route.cum[begin];
                step.endDist = route.cum[end];
                route.steps.add(step);
            }
            if (route.steps.isEmpty()) throw new JSONException("Route has no steps");

            int count = route.steps.size();
            route.secondsAfter = new double[count];
            double after = 0;
            for (int i = count - 1; i >= 0; i--) {
                route.secondsAfter[i] = after;
                after += route.steps.get(i).seconds;
            }
            route.durationSec = after;
            return route;
        }

        /** Valhalla's numbered manoeuvre types, as the names {@link Maneuvers} draws. */
        private static String maneuverName(int type) {
            switch (type) {
                case 1: case 2: case 3: return "DEPART";
                case 4: case 5: case 6: return "DESTINATION";
                case 9: return "TURN_SLIGHT_RIGHT";
                case 10: return "TURN_RIGHT";
                case 11: return "TURN_SHARP_RIGHT";
                case 12: return "UTURN_RIGHT";
                case 13: return "UTURN_LEFT";
                case 14: return "TURN_SHARP_LEFT";
                case 15: return "TURN_LEFT";
                case 16: return "TURN_SLIGHT_LEFT";
                case 18: case 20: return "RAMP_RIGHT";
                case 19: case 21: return "RAMP_LEFT";
                case 23: return "FORK_RIGHT";
                case 24: return "FORK_LEFT";
                case 25: case 37: case 38: return "MERGE";
                case 26: case 27: return "ROUNDABOUT";
                default: return "STRAIGHT";
            }
        }
    }

    /** Where the car is on the route right now. */
    static final class Progress {
        /** Index of the route segment the car is on. */
        int segment;
        double along;
        /** Metres from the route line. */
        double offBy;
        boolean offRoute;
        double snapLat, snapLng, snapBearing;
        int stepIndex;
        /** The next thing to do, and how far away it is. */
        String maneuver = "";
        String text = "";
        String spoken = "";
        double distToManeuver;
        double remainDist;
        double remainSec;
        long eta;
    }

    private final Route route;
    private int lastSegment;

    Nav(Route route) {
        this.route = route;
    }

    Route route() {
        return route;
    }

    Progress update(double lat, double lng, float accuracy) {
        Route r = route;
        int n = r.lat.length;
        double cosLat = Math.cos(Math.toRadians(lat));

        // Look near where the car last was first, so a road that doubles back doesn't confuse things.
        int from = Math.max(0, lastSegment - 10);
        int to = Math.min(n - 2, lastSegment + 200);
        double[] best = nearest(r, lat, lng, cosLat, from, to);
        double allowed = OFF_ROUTE_M + Math.min(Math.max(accuracy, 0f), 50f);
        if (best[1] > allowed) {
            double[] anywhere = nearest(r, lat, lng, cosLat, 0, n - 2);
            if (anywhere[1] <= allowed) best = anywhere;
        }

        Progress p = new Progress();
        int seg = (int) best[0];
        double t = best[2];
        p.offBy = best[1];
        p.offRoute = best[1] > allowed;
        if (!p.offRoute) lastSegment = seg;
        p.segment = seg;
        double segLen = r.cum[seg + 1] - r.cum[seg];
        p.along = r.cum[seg] + t * segLen;
        p.snapLat = r.lat[seg] + t * (r.lat[seg + 1] - r.lat[seg]);
        p.snapLng = r.lng[seg] + t * (r.lng[seg + 1] - r.lng[seg]);
        p.snapBearing = Geo.bearing(r.lat[seg], r.lng[seg], r.lat[seg + 1], r.lng[seg + 1]);

        int count = r.steps.size();
        int si = count - 1;
        for (int i = 0; i < count; i++) {
            if (p.along < r.steps.get(i).endDist - 0.5) {
                si = i;
                break;
            }
        }
        p.stepIndex = si;
        Step current = r.steps.get(si);
        p.remainDist = Math.max(0, r.totalDist - p.along);
        double stepLen = current.endDist - current.startDist;
        double stepLeft = stepLen > 0 ? Math.max(0, Math.min(1, (current.endDist - p.along) / stepLen)) : 0;
        p.remainSec = current.seconds * stepLeft + r.secondsAfter[si];
        p.eta = System.currentTimeMillis() + (long) (p.remainSec * 1000);

        if (si + 1 < count) {
            Step next = r.steps.get(si + 1);
            p.maneuver = next.maneuver;
            p.text = next.text;
            p.spoken = next.spoken;
            p.distToManeuver = Math.max(0, current.endDist - p.along);
        } else {
            p.maneuver = "DESTINATION";
            p.text = "Arrive at your destination";
            p.spoken = p.text;
            p.distToManeuver = p.remainDist;
        }
        return p;
    }

    /** @return {segment index, metres from it, how far along it (0..1)} for the closest segment in the range */
    private static double[] nearest(Route r, double lat, double lng, double cosLat, int from, int to) {
        double bestD = Double.MAX_VALUE, bestT = 0;
        int bestI = from;
        final double k = Math.toRadians(1) * Geo.R;
        for (int i = from; i <= to; i++) {
            double ax = (r.lng[i] - lng) * cosLat * k, ay = (r.lat[i] - lat) * k;
            double bx = (r.lng[i + 1] - lng) * cosLat * k, by = (r.lat[i + 1] - lat) * k;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? -(ax * dx + ay * dy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (d < bestD) {
                bestD = d;
                bestT = t;
                bestI = i;
            }
        }
        return new double[]{bestI, bestD, bestT};
    }
}
