package com.carmaps;

import java.util.Calendar;
import java.util.Locale;

/** Small geometry and text helpers. */
final class Geo {

    static final double R = 6371000.0;

    private Geo() {
    }

    /** Decodes an encoded polyline into [lat0, lng0, lat1, lng1, ...]. {@code factor} is 1e5 or 1e6. */
    static double[] decode(String s, double factor) {
        int n = s.length();
        double[] out = new double[n];   // never more points than characters / 2
        int count = 0, i = 0, lat = 0, lng = 0;
        while (i < n) {
            int shift = 0, result = 0, b;
            do {
                b = s.charAt(i++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && i < n);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            if (i >= n) break;
            shift = 0;
            result = 0;
            do {
                b = s.charAt(i++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && i < n);
            lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            out[count++] = lat / factor;
            out[count++] = lng / factor;
        }
        double[] trimmed = new double[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Metres between two points (fine for the short hops along a route). */
    static double dist(double lat1, double lng1, double lat2, double lng2) {
        double x = Math.toRadians(lng2 - lng1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * R;
    }

    /** Compass bearing from the first point to the second, 0..360. */
    static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double x = Math.toRadians(lng2 - lng1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        double deg = Math.toDegrees(Math.atan2(x, y));
        return deg < 0 ? deg + 360 : deg;
    }

    // ------------------------------------------------------------------ text

    static String distance(double metres) {
        if (metres < 1000) {
            int step = metres < 100 ? 10 : 50;
            int m = (int) (Math.round(metres / step) * step);
            if (m >= 1000) return "1.0 km";
            return Math.max(m, 0) + " m";
        }
        if (metres < 10000) return String.format(Locale.US, "%.1f km", metres / 1000);
        return Math.round(metres / 1000) + " km";
    }

    static String duration(double seconds) {
        long min = Math.round(seconds / 60);
        if (min < 1) return "1 min";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    static String clock(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** "350 m" → "350 metres", for speech. */
    static String spokenDistance(double metres) {
        if (metres < 1000) {
            int step = metres < 100 ? 10 : 50;
            long m = Math.round(metres / step) * step;
            return m + " metres";
        }
        double km = Math.round(metres / 100) / 10.0;
        if (km == Math.floor(km)) return (long) km + (km == 1 ? " kilometre" : " kilometres");
        return km + " kilometres";
    }

    /**
     * Whether it is dark at a place: true from shortly after sunset to shortly before sunrise. Works the
     * sun's height out from the date, time and position with the usual low-precision formulas, which are
     * good to a fraction of a degree.
     */
    static boolean isNight(double lat, double lng, long millis) {
        double days = millis / 86400000.0 - 10957.5;   // days since noon UTC on 1 January 2000
        double meanLng = Math.toRadians((280.460 + 0.9856474 * days) % 360);
        double anomaly = Math.toRadians((357.528 + 0.9856003 * days) % 360);
        double lambda = meanLng + Math.toRadians(1.915) * Math.sin(anomaly) + Math.toRadians(0.020) * Math.sin(2 * anomaly);
        double tilt = Math.toRadians(23.439 - 0.0000004 * days);
        double declination = Math.asin(Math.sin(tilt) * Math.sin(lambda));
        double rightAscension = Math.atan2(Math.cos(tilt) * Math.sin(lambda), Math.cos(lambda));
        double siderealHours = (18.697374558 + 24.06570982441908 * days) % 24;
        double hourAngle = Math.toRadians(siderealHours * 15 + lng) - rightAscension;
        double phi = Math.toRadians(lat);
        double height = Math.asin(Math.sin(phi) * Math.sin(declination)
                + Math.cos(phi) * Math.cos(declination) * Math.cos(hourAngle));
        return Math.toDegrees(height) < -1.5;
    }
}
