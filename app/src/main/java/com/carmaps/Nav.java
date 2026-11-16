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
        /** True when the route and its times come from TomTom with live traffic. */
        boolean traffic;
        /** How much of {@link #durationSec} is delay from traffic. */
        double trafficDelaySec;
        /** True if the router says some part of the route is tolled. */
        boolean hasTolls;
        /** Speed limits where known: each is {from metres, to metres, km/h} along the route. */
        double[][] limits = new double[0][];
        /** The stretches before junctions where the router says which lanes to be in. */
        final List<LaneSpot> lanes = new ArrayList<>();

        /** @param r one entry of "routes" in a TomTom Calculate Route response asked for with text instructions */
        static Route parseTomTom(JSONObject r) throws JSONException {
            Route route = new Route();
            List<double[]> pts = new ArrayList<>();
            JSONArray legs = r.getJSONArray("legs");
            for (int l = 0; l < legs.length(); l++) {
                JSONArray points = legs.getJSONObject(l).getJSONArray("points");
                for (int i = 0; i < points.length(); i++) {
                    JSONObject p = points.getJSONObject(i);
                    pts.add(new double[]{p.getDouble("latitude"), p.getDouble("longitude")});
                }
            }
            int n = pts.size();
            if (n < 2) throw new JSONException("Route has no line");
            route.lat = new double[n];
            route.lng = new double[n];
            route.cum = new double[n];
            for (int i = 0; i < n; i++) {
                route.lat[i] = pts.get(i)[0];
                route.lng[i] = pts.get(i)[1];
                route.cum[i] = i == 0 ? 0
                        : route.cum[i - 1] + Geo.dist(route.lat[i - 1], route.lng[i - 1], route.lat[i], route.lng[i]);
            }
            route.totalDist = route.cum[n - 1];
            JSONObject summary = r.getJSONObject("summary");
            double total = summary.getDouble("travelTimeInSeconds");
            route.trafficDelaySec = summary.optDouble("trafficDelayInSeconds", 0);
            route.traffic = true;

            JSONArray sections = r.optJSONArray("sections");
            List<double[]> limits = new ArrayList<>();
            for (int i = 0; sections != null && i < sections.length(); i++) {
                JSONObject sec = sections.getJSONObject(i);
                String type = sec.optString("sectionType");
                int a = Math.max(0, Math.min(n - 1, sec.optInt("startPointIndex")));
                int b = Math.max(a, Math.min(n - 1, sec.optInt("endPointIndex")));
                if ("TOLL".equals(type) || "TOLL_ROAD".equals(type)) {
                    route.hasTolls = true;
                } else if ("SPEED_LIMIT".equals(type) && sec.optInt("maxSpeedLimitInKmh") > 0) {
                    limits.add(new double[]{route.cum[a], route.cum[b], sec.optInt("maxSpeedLimitInKmh")});
                } else if ("LANES".equals(type)) {
                    JSONArray list = sec.optJSONArray("lanes");
                    if (list == null || list.length() < 2 || list.length() > 10) continue;
                    LaneSpot spot = new LaneSpot();
                    spot.startDist = route.cum[a];
                    spot.endDist = route.cum[b];
                    spot.arrows = new String[list.length()];
                    spot.follow = new boolean[list.length()];
                    boolean any = false;
                    for (int k = 0; k < list.length(); k++) {
                        JSONObject lane = list.getJSONObject(k);
                        JSONArray dirs = lane.optJSONArray("directions");
                        String follow = lane.optString("follow");
                        spot.follow[k] = !follow.isEmpty();
                        any |= spot.follow[k];
                        spot.arrows[k] = !follow.isEmpty() ? follow
                                : dirs != null && dirs.length() > 0 ? dirs.optString(0) : "STRAIGHT";
                    }
                    // Only worth showing where it says which lanes to take.
                    if (any) route.lanes.add(spot);
                }
            }
            route.limits = limits.toArray(new double[0][]);

            JSONObject guidance = r.optJSONObject("guidance");
            JSONArray list = guidance == null ? null : guidance.optJSONArray("instructions");
            if (list == null || list.length() == 0) throw new JSONException("Route has no steps");
            // Each instruction says where its manoeuvre is and the driving time to reach it from the start.
            int count = list.length();
            double[] at = new double[count];
            for (int i = 0; i < count; i++) {
                JSONObject m = list.getJSONObject(i);
                Step step = new Step();
                step.maneuver = tomTomManeuver(m.optString("maneuver"), m.optString("drivingSide"));
                step.text = m.optString("message");
                if (step.text.isEmpty()) step.text = m.optString("street");
                step.spoken = step.text;
                int index = Math.max(0, Math.min(n - 1, m.optInt("pointIndex")));
                step.startDist = route.cum[index];
                at[i] = m.optDouble("travelTimeInSeconds", 0);
                route.steps.add(step);
            }
            for (int i = 0; i < count; i++) {
                Step step = route.steps.get(i);
                boolean lastOne = i == count - 1;
                step.endDist = Math.max(step.startDist, lastOne ? route.totalDist : route.steps.get(i + 1).startDist);
                step.seconds = Math.max(0, (lastOne ? total : at[i + 1]) - at[i]);
            }
            // The list should open with setting off; if it doesn't, the first stretch still needs a step.
            if (route.steps.get(0).startDist > 1) {
                Step first = new Step();
                first.maneuver = "DEPART";
                first.text = first.spoken = "Set off";
                first.endDist = route.steps.get(0).startDist;
                first.seconds = at[0];
                route.steps.add(0, first);
            }
            // And it should close with arriving, as a step of its own at the very end.
            if (!"DESTINATION".equals(route.steps.get(route.steps.size() - 1).maneuver)) {
                Step arrive = new Step();
                arrive.maneuver = "DESTINATION";
                arrive.text = arrive.spoken = "Arrive at your destination";
                arrive.startDist = arrive.endDist = route.totalDist;
                route.steps.add(arrive);
            }
            route.finish();
            route.durationSec = total;
            return route;
        }

        private void finish() {
            int count = steps.size();
            secondsAfter = new double[count];
            double after = 0;
            for (int i = count - 1; i >= 0; i--) {
                secondsAfter[i] = after;
                after += steps.get(i).seconds;
            }
            durationSec = after;
        }

        /** TomTom's manoeuvre codes, as the names {@link Maneuvers} draws. */
        private static String tomTomManeuver(String code, String drivingSide) {
            // Where traffic keeps left, U-turns go round to the right and exits leave on the left.
            boolean keepsLeft = "LEFT".equals(drivingSide);
            switch (code) {
                case "ARRIVE": case "ARRIVE_LEFT": case "ARRIVE_RIGHT": return "DESTINATION";
                case "DEPART": return "DEPART";
                case "KEEP_RIGHT": return "FORK_RIGHT";
                case "KEEP_LEFT": return "FORK_LEFT";
                case "BEAR_RIGHT": return "TURN_SLIGHT_RIGHT";
                case "BEAR_LEFT": return "TURN_SLIGHT_LEFT";
                case "TURN_RIGHT": return "TURN_RIGHT";
                case "TURN_LEFT": return "TURN_LEFT";
                case "SHARP_RIGHT": return "TURN_SHARP_RIGHT";
                case "SHARP_LEFT": return "TURN_SHARP_LEFT";
                case "MAKE_UTURN": case "TRY_MAKE_UTURN": return keepsLeft ? "UTURN_RIGHT" : "UTURN_LEFT";
                case "ENTER_MOTORWAY": case "ENTER_FREEWAY": case "ENTER_HIGHWAY": case "ENTRANCE_RAMP":
                    return "MERGE";
                case "TAKE_EXIT": return keepsLeft ? "RAMP_LEFT" : "RAMP_RIGHT";
                case "MOTORWAY_EXIT_LEFT": return "RAMP_LEFT";
                case "MOTORWAY_EXIT_RIGHT": return "RAMP_RIGHT";
                case "ROUNDABOUT_CROSS": case "ROUNDABOUT_RIGHT": case "ROUNDABOUT_LEFT": case "ROUNDABOUT_BACK":
                    return "ROUNDABOUT";
                default: return "STRAIGHT";
            }
        }

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
                if (m.optBoolean("toll")) route.hasTolls = true;
                int begin = Math.max(0, Math.min(n - 1, m.optInt("begin_shape_index")));
                int end = Math.max(begin, Math.min(n - 1, m.optInt("end_shape_index")));
                step.startDist = route.cum[begin];
                step.endDist = route.cum[end];
                route.steps.add(step);
            }
            if (route.steps.isEmpty()) throw new JSONException("Route has no steps");

            route.finish();
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
        /** The limit on this stretch in km/h, or 0 if not known; {@link #over} is filled in by the service. */
        int speedLimit;
        boolean over;
        /** Which lanes to be in for the junction coming up, or null. */
        LaneSpot lanes;
    }

    /** One stretch of road with marked lanes: an arrow per lane, left to right, and which ones to take. */
    static final class LaneSpot {
        double startDist, endDist;
        String[] arrows;
        boolean[] follow;
    }

    /** How far before a lane stretch its arrows start to show. */
    private static final double LANES_AHEAD_M = 400;

    /** @return {metres along the route, metres away from it} for the point of the route nearest to a place */
    static double[] offsetOf(Route r, double lat, double lng) {
        double[] best = nearest(r, lat, lng, Math.cos(Math.toRadians(lat)), 0, r.lat.length - 2);
        int seg = (int) best[0];
        return new double[]{r.cum[seg] + best[2] * (r.cum[seg + 1] - r.cum[seg]), best[1]};
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
        for (double[] limit : r.limits) {
            if (p.along >= limit[0] && p.along < limit[1]) {
                p.speedLimit = (int) limit[2];
                break;
            }
        }
        for (LaneSpot spot : r.lanes) {
            if (spot.endDist > p.along && spot.startDist - p.along < LANES_AHEAD_M) {
                p.lanes = spot;
                break;
            }
        }

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
