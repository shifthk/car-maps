package com.carmaps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An OpenStreetMap map drawn natively: the standard map tiles from tile.openstreetmap.org, with the
 * route, the destination and the car on top. Drag to move, pinch or double-tap to zoom, hold a
 * finger down to pick a point. North is always up.
 *
 * <p>Following OpenStreetMap's tile usage policy: tiles are kept on disk and reused for two weeks,
 * at most two are downloaded at a time, only tiles on screen are fetched, the app identifies itself,
 * and the attribution is always shown.</p>
 */
final class TileMap extends View {

    interface Listener {
        /** The user dragged or zoomed the map themselves. */
        void onUserMoved();

        void onLongPress(double lat, double lng);

        void onTap();
    }

    private static final String TAG = "TileMap";
    private static final String TILES = "https://tile.openstreetmap.org/";
    private static final String AGENT = "CarMaps/2.16 (Android; +https://github.com/shifthk/car-maps)";
    private static final int TILE = 256;
    private static final int MIN_ZOOM = 2, MAX_ZOOM = 19;
    private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;
    private static final long DISK_LIMIT = 150L * 1024 * 1024, DISK_TARGET = 100L * 1024 * 1024;
    private static final long GLIDE_MS = 1000;
    /** TomTom's traffic flow tiles: see-through, coloured by how slow each road is against its free flow. */
    private static final String TRAFFIC = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/";
    private static final long TRAFFIC_BLOCK_MS = 5 * 60 * 1000;
    /** How long a traffic tile is used before it is fetched again (Long.MAX_VALUE: never). */
    private volatile long trafficFreshMs = 5 * 60 * 1000;
    private static final int TRAFFIC_MIN_ZOOM = 7;

    /** How the traffic layer is doing, for the settings screen. */
    static volatile String trafficStatus = "";

    /** TomTom's own map tiles, used instead of OpenStreetMap's while the user has a TomTom key. */
    private static final String TOMTOM_MAP = "https://api.tomtom.com/map/1/tile/basic/";
    private static final long TOMTOM_KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    /** Which map is being drawn, for the settings screen. */
    static volatile String mapStatus = "";
    private volatile String mapKey;
    /** After TomTom turns the key away, OpenStreetMap's tiles are drawn until this time. */
    private volatile long mapBlockedUntil;
    /** In front of every tile's name: "" for OpenStreetMap, "m/" for TomTom's day map, "n/" for its night map. */
    private String prefix = "";
    private ColorMatrixColorFilter darkFilter;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ExecutorService trafficPool = Executors.newFixedThreadPool(3);
    /** Traffic tiles live in memory only, and are fetched again once they are a couple of minutes old. */
    private final LruCache<String, Bitmap> trafficMemory = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final java.util.HashMap<String, Long> trafficAt = new java.util.HashMap<>();
    private final Paint trafficPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The TomTom key while the traffic layer is on, otherwise null. */
    private volatile String trafficKey;
    private long trafficBlockedUntil;
    private final Runnable trafficTick = new Runnable() {
        @Override
        public void run() {
            if (trafficKey == null || released) return;
            // Drawing notices tiles that have gone stale and asks for them again. A hidden map
            // (the window shrunk to its button) isn't drawn, so it asks for nothing.
            if (isShown()) invalidate();
            main.postDelayed(this, 60000);
        }
    };
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    /** The tiles the last drawing needed; a queued download checks it is still among them. */
    private volatile Set<String> visible = ConcurrentHashMap.newKeySet();
    private volatile boolean released;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float density;
    /** How much bigger than their natural size tiles are drawn, so labels are readable on a dense screen. */
    private final float tileScale;
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private Listener listener;
    private boolean dark;
    /**
     * Drawn for the head-up display: map tiles reduced to their roads (bright on a see-through
     * background, since black is unlit on a HUD), with no traffic layer and no car marker. TomTom's
     * night map is used while a key is set, OpenStreetMap's otherwise.
     */
    private boolean hudStyle;
    /** Where the car sits top to bottom while the map is turned, as a fraction of the height. */
    private float turnedAnchor = 0.68f;

    /** Centre of the view, in map units: 0..1 across the whole world, x east and y south. */
    private double cx = 0.5, cy = 0.5;
    private double zoom = 3;
    private boolean follow = true;
    private boolean fitPending;

    private boolean haveCar;
    private double carX, carY, carFromX, carFromY, carToX, carToY;
    private float carBearing, carBearingFrom, carBearingTo;
    private boolean haveBearing;
    /** Turn the map so the car's heading is up while following it. */
    private boolean headingUp;
    /** How the last drawing was turned (degrees) and where on the view the map's centre was put. */
    private float rotation, ax, ay;
    private double[][] altX, altY;
    private double[] incX, incY;
    private int[] incCat;
    private long carMovedAt;

    private double[] routeX, routeY;
    private boolean havePin;
    private double pinX, pinY;

    TileMap(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        tileScale = Math.max(1f, density * 0.8f);
        dir = new File(c.getCacheDir(), "tiles");
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                double world = worldSize();
                // If the map is turned, a drag across the screen is a drag at an angle across the map.
                double r = Math.toRadians(rotation);
                cx += (dx * Math.cos(r) - dy * Math.sin(r)) / world;
                cy += (dx * Math.sin(r) + dy * Math.cos(r)) / world;
                moved();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAbout(1, e.getX(), e.getY());
                moved();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (listener == null || pinch.isInProgress()) return;
                double world = worldSize();
                double r = Math.toRadians(rotation);
                double sx = e.getX() - ax, sy = e.getY() - ay;
                double x = cx + (sx * Math.cos(r) - sy * Math.sin(r)) / world;
                double y = cy + (sx * Math.sin(r) + sy * Math.cos(r)) / world;
                listener.onLongPress(latOf(y), x * 360 - 180);
            }
        });
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAbout(Math.log(d.getScaleFactor()) / Math.log(2), d.getFocusX(), d.getFocusY());
                moved();
                return true;
            }
        });
        pool.execute(this::pruneDisk);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void release() {
        released = true;
        pool.shutdownNow();
        trafficPool.shutdownNow();
        main.removeCallbacksAndMessages(null);
        memory.evictAll();
        trafficMemory.evictAll();
    }

    // ------------------------------------------------------------------ what to show

    /** Dark colours for night driving: the same tiles with their brightness flipped and hues kept. */
    void setDark(boolean on) {
        if (dark == on) return;
        dark = on;
        if (on) {
            ColorMatrix m = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});
            // Turning the hues half way round after flipping puts greens and blues back where they were.
            m.postConcat(new ColorMatrix(new float[]{
                    -0.574f, 1.430f, 0.144f, 0, 0,
                    0.426f, 0.430f, 0.144f, 0, 0,
                    0.426f, 1.430f, -0.856f, 0, 0,
                    0, 0, 0, 1, 0}));
            // Pure black roads on a car screen are hard to tell apart: lift the darkest shades a little.
            m.postConcat(new ColorMatrix(new float[]{
                    0.82f, 0, 0, 0, 26,
                    0, 0.82f, 0, 0, 28,
                    0, 0, 0.82f, 0, 32,
                    0, 0, 0, 1, 0}));
            darkFilter = new ColorMatrixColorFilter(m);
        }
        invalidate();
    }

    /** Draws TomTom's map instead of OpenStreetMap's. @param key the user's TomTom key, or null for OpenStreetMap */
    void setMap(String key) {
        if (key == null ? mapKey == null : key.equals(mapKey)) return;
        mapKey = key;
        mapBlockedUntil = 0;
        mapStatus = key == null ? "" : "Map: TomTom";
        invalidate();
    }

    /** @param ms how long a traffic tile is used before being fetched again; Long.MAX_VALUE for never */
    void setTrafficFresh(long ms) {
        trafficFreshMs = ms;
    }

    /** Lays live traffic over the map. @param key the user's TomTom key, or null to turn the layer off */
    void setTraffic(String key) {
        if (key == null ? trafficKey == null : key.equals(trafficKey)) return;
        trafficKey = key;
        trafficMemory.evictAll();
        trafficAt.clear();
        trafficBlockedUntil = 0;
        trafficStatus = key == null ? "" : "Map traffic: waiting for the first tiles";
        main.removeCallbacks(trafficTick);
        if (key != null) main.postDelayed(trafficTick, 60000);
        invalidate();
    }

    void setCar(double lat, double lng, float bearing) {
        double x = xOf(lng), y = yOf(lat);
        if (!haveCar) {
            haveCar = true;
            carX = carFromX = carToX = x;
            carY = carFromY = carToY = y;
            if (follow) {
                cx = x;
                cy = y;
                if (zoom < 10) zoom = 16;
            }
        } else {
            carFromX = carX;
            carFromY = carY;
            carToX = x;
            carToY = y;
        }
        carBearingFrom = haveBearing ? carBearing : bearing;
        carBearingTo = bearing;
        if (!haveBearing) carBearing = bearing;
        haveBearing = true;
        carMovedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean hasCar() {
        return haveCar;
    }

    /** Keep the car in the middle of the view as it moves. */
    void setFollow(boolean on) {
        follow = on;
        if (on && haveCar) {
            cx = carX;
            cy = carY;
            if (zoom < 14) zoom = 16;
        }
        invalidate();
    }

    boolean isFollowing() {
        return follow;
    }

    void setZoom(double z) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        invalidate();
    }

    void zoomBy(double steps) {
        zoomAbout(steps, getWidth() / 2f, getHeight() / 2f);
    }

    void setRoute(double[] lat, double[] lng) {
        int n = lat.length;
        routeX = new double[n];
        routeY = new double[n];
        for (int i = 0; i < n; i++) {
            routeX[i] = xOf(lng[i]);
            routeY[i] = yOf(lat[i]);
        }
        invalidate();
    }

    void clearRoute() {
        routeX = routeY = null;
        altX = altY = null;
        invalidate();
    }

    /** Other routes on offer, drawn in grey under the chosen one; null for none. */
    void setAlternates(java.util.List<double[]> lats, java.util.List<double[]> lngs) {
        if (lats == null || lats.isEmpty()) {
            altX = altY = null;
        } else {
            altX = new double[lats.size()][];
            altY = new double[lats.size()][];
            for (int k = 0; k < lats.size(); k++) {
                double[] lat = lats.get(k), lng = lngs.get(k);
                altX[k] = new double[lat.length];
                altY[k] = new double[lat.length];
                for (int i = 0; i < lat.length; i++) {
                    altX[k][i] = xOf(lng[i]);
                    altY[k][i] = yOf(lat[i]);
                }
            }
        }
        invalidate();
    }

    /** Markers for things on the road; category is TomTom's icon category. */
    void setIncidents(double[] lat, double[] lng, int[] category) {
        int n = lat == null ? 0 : lat.length;
        incX = new double[n];
        incY = new double[n];
        incCat = category;
        for (int i = 0; i < n; i++) {
            incX[i] = xOf(lng[i]);
            incY[i] = yOf(lat[i]);
        }
        invalidate();
    }

    /** See {@link #hudStyle}. */
    void setHudStyle(boolean on) {
        hudStyle = on;
        invalidate();
    }

    void setTurnedAnchor(float fraction) {
        turnedAnchor = fraction;
        invalidate();
    }

    void setHeadingUp(boolean on) {
        if (headingUp == on) return;
        headingUp = on;
        invalidate();
    }

    void setPin(double lat, double lng) {
        havePin = true;
        pinX = xOf(lng);
        pinY = yOf(lat);
        invalidate();
    }

    void clearPin() {
        havePin = false;
        invalidate();
    }

    /** Moves and zooms so the whole route is in view. */
    void fitRoute() {
        if (routeX == null || routeX.length == 0) return;
        if (getWidth() == 0 || getHeight() == 0) {
            fitPending = true;
            return;
        }
        fitPending = false;
        double minX = 1, maxX = 0, minY = 1, maxY = 0;
        for (int i = 0; i < routeX.length; i++) {
            minX = Math.min(minX, routeX[i]);
            maxX = Math.max(maxX, routeX[i]);
            minY = Math.min(minY, routeY[i]);
            maxY = Math.max(maxY, routeY[i]);
        }
        double spanX = Math.max(maxX - minX, 1e-7), spanY = Math.max(maxY - minY, 1e-7);
        double perUnit = Math.min(getWidth() * 0.78 / spanX, getHeight() * 0.74 / spanY);
        follow = false;
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        setZoom(Math.min(16.5, Math.log(perUnit / (TILE * tileScale)) / Math.log(2)));
    }

    void centerOn(double lat, double lng, double atLeastZoom) {
        follow = false;
        cx = xOf(lng);
        cy = yOf(lat);
        if (zoom < atLeastZoom) zoom = atLeastZoom;
        invalidate();
    }

    // ------------------------------------------------------------------ geometry

    private static double xOf(double lng) {
        return (lng + 180) / 360;
    }

    private static double yOf(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-85.05, Math.min(85.05, lat))));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    private static double latOf(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
    }

    /** Width of the whole world in screen pixels at the current zoom. */
    private double worldSize() {
        return TILE * tileScale * Math.pow(2, zoom);
    }

    private void zoomAbout(double steps, float fx, float fy) {
        double before = worldSize();
        double px = cx + (fx - getWidth() / 2f) / before, py = cy + (fy - getHeight() / 2f) / before;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + steps));
        double after = worldSize();
        cx = px - (fx - getWidth() / 2f) / after;
        cy = py - (fy - getHeight() / 2f) / after;
        invalidate();
    }

    private void moved() {
        cy = Math.max(0, Math.min(1, cy));
        follow = false;
        if (listener != null) listener.onUserMoved();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (!pinch.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (fitPending && w > 0 && h > 0) fitRoute();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean tomtom = mapKey != null && SystemClock.elapsedRealtime() >= mapBlockedUntil;
        if (!hudStyle) c.drawColor(dark ? 0xFF1B1F24 : 0xFFE8E6E1);
        // TomTom has a night map of its own; OpenStreetMap's tiles are recoloured instead.
        prefix = hudStyle ? (tomtom ? "hn/" : "h/") : tomtom ? (dark ? "n/" : "m/") : "";
        tilePaint.setColorFilter(dark && !tomtom && !hudStyle ? darkFilter : null);

        // Glide the car from its last position and heading to the new ones over the second between GPS fixes.
        boolean gliding = false;
        if (haveCar) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - carMovedAt) / (float) GLIDE_MS);
            carX = carFromX + (carToX - carFromX) * t;
            carY = carFromY + (carToY - carFromY) * t;
            float turn = ((carBearingTo - carBearingFrom) % 360 + 540) % 360 - 180;   // the short way round
            carBearing = carBearingFrom + turn * t;
            gliding = t < 1f;
            if (follow) {
                cx = carX;
                cy = carY;
            }
        }

        // While following the car the map can be turned so the way ahead is up, with the car low in
        // the view so more of the road ahead shows. Otherwise north is up and the centre is the centre.
        boolean turned = headingUp && follow && haveCar;
        rotation = turned ? carBearing : 0f;
        ax = w / 2f;
        ay = turned ? h * turnedAnchor : h / 2f;

        final Set<String> needed = ConcurrentHashMap.newKeySet();
        visible = needed;
        int z = Math.max(0, Math.min(MAX_ZOOM, (int) Math.floor(zoom + 0.5)));
        int n = 1 << z;
        double world = worldSize();
        double size = world / n;
        // A turned map needs tiles out to the furthest corner in every direction.
        double reachX = w / 2.0, reachY = h / 2.0;
        if (turned) reachX = reachY = Math.hypot(Math.max(ax, w - ax), Math.max(ay, h - ay));
        int x0 = (int) Math.floor((cx - reachX / world) * n), x1 = (int) Math.floor((cx + reachX / world) * n);
        int y0 = Math.max(0, (int) Math.floor((cy - reachY / world) * n));
        int y1 = Math.min(n - 1, (int) Math.floor((cy + reachY / world) * n));

        int turnedBack = c.save();
        c.rotate(-rotation, ax, ay);
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int wrapped = ((tx % n) + n) % n;
                float left = (float) ((tx / (double) n - cx) * world + ax);
                float top = (float) ((ty / (double) n - cy) * world + ay);
                // A hair of overlap hides the seams between tiles.
                dst.set(left, top, (float) (left + size) + 0.5f, (float) (top + size) + 0.5f);
                drawTile(c, z, wrapped, ty);
                // Traffic only for tiles that can actually be seen: a turned map loads map tiles
                // out to its corners (cached for days), but traffic there would be wasted requests.
                if (trafficKey != null && !hudStyle && z >= TRAFFIC_MIN_ZOOM && onScreen(w, h)) {
                    drawTraffic(c, z, wrapped, ty);
                }
            }
        }

        double[][] ox = altX, oy = altY;
        if (ox != null) {
            for (int k = 0; k < ox.length; k++) drawLine(c, ox[k], oy[k], world, 0xFF5E6873, 0xFFAEB7C0);
        }
        drawLine(c, routeX, routeY, world, 0xFF0B3D91, 0xFF4C9BFF);
        drawIncidents(c, world);
        if (havePin) {
            float px = (float) ((pinX - cx) * world + ax), py = (float) ((pinY - cy) * world + ay);
            int upright = c.save();
            c.rotate(rotation, px, py);
            drawPin(c, px, py);
            c.restoreToCount(upright);
        }
        if (haveCar && !hudStyle) drawCar(c, (float) ((carX - cx) * world + ax), (float) ((carY - cy) * world + ay));
        c.restoreToCount(turnedBack);

        paint.setStyle(Paint.Style.FILL);
        if (hudStyle) {
            // On the HUD a box behind the credit would glow: just small, dim text in the corner.
            paint.setTextSize(10 * density);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(0x80FFFFFF);
            c.drawText(tomtom ? "© TomTom" : "© OpenStreetMap", w - 4 * density, h - 4 * density, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            if (gliding) postInvalidateOnAnimation();
            return;
        }
        paint.setTextSize(11 * density);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(dark ? 0xB0000000 : 0xB0FFFFFF);
        String credit = !prefix.isEmpty() ? "© TomTom" : trafficKey == null ? "© OpenStreetMap contributors"
                : "© OpenStreetMap contributors · traffic © TomTom";
        float tw = paint.measureText(credit);
        c.drawRect(w - tw - 10 * density, h - 18 * density, w, h, paint);
        paint.setColor(dark ? 0xFFD0D6DC : 0xFF333333);
        c.drawText(credit, w - 5 * density, h - 5 * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (gliding) postInvalidateOnAnimation();
    }

    /** Small round markers: red for crashes and closures, orange for roadworks, yellow for the rest. */
    private void drawIncidents(Canvas c, double world) {
        double[] xs = incX, ys = incY;
        int[] cat = incCat;
        if (xs == null || cat == null || zoom < 10) return;
        float r = 11 * density;
        float reach = (float) Math.hypot(getWidth(), getHeight());
        for (int i = 0; i < xs.length && i < cat.length; i++) {
            float x = (float) ((xs[i] - cx) * world + ax), y = (float) ((ys[i] - cy) * world + ay);
            if (Math.abs(x - ax) > reach || Math.abs(y - ay) > reach) continue;
            int upright = c.save();
            c.rotate(rotation, x, y);
            boolean closed = cat[i] == 8;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(closed || cat[i] == 1 || cat[i] == 14 ? 0xFFD93025
                    : cat[i] == 9 || cat[i] == 7 ? 0xFFF57C00 : 0xFFF9AB00);
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.FILL);
            if (closed) {
                c.drawRect(x - r * 0.6f, y - r * 0.18f, x + r * 0.6f, y + r * 0.18f, paint);
            } else {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setFakeBoldText(true);
                paint.setTextSize(r * 1.5f);
                c.drawText("!", x, y + r * 0.52f, paint);
                paint.setFakeBoldText(false);
                paint.setTextAlign(Paint.Align.LEFT);
            }
            c.restoreToCount(upright);
        }
    }

    private void drawTile(Canvas c, int z, int x, int y) {
        String key = prefix + z + "/" + x + "/" + y;
        visible.add(key);
        Bitmap b = memory.get(key);
        if (b != null) {
            c.drawBitmap(b, null, dst, tilePaint);
            return;
        }
        request(key);
        // Until it arrives, stretch the matching part of a lower-zoom tile we already have.
        for (int up = 1; up <= 5 && z - up >= 0; up++) {
            Bitmap parent = memory.get(prefix + (z - up) + "/" + (x >> up) + "/" + (y >> up));
            if (parent == null) continue;
            int cells = 1 << up, cell = parent.getWidth() / cells;
            if (cell < 1) break;
            int px = (x & (cells - 1)) * cell, py = (y & (cells - 1)) * cell;
            src.set(px, py, px + cell, py + cell);
            c.drawBitmap(parent, src, dst, tilePaint);
            return;
        }
    }

    /**
     * Whether the tile in {@link #dst} (map coordinates, before the map is turned about ax, ay)
     * overlaps the w x h view once turned: a separating-axis test of the two rectangles.
     */
    private boolean onScreen(int w, int h) {
        if (rotation == 0f) return dst.right > 0 && dst.left < w && dst.bottom > 0 && dst.top < h;
        double a = Math.toRadians(-rotation), cos = Math.cos(a), sin = Math.sin(a);
        float[] tx = {dst.left, dst.right, dst.right, dst.left}, ty = {dst.top, dst.top, dst.bottom, dst.bottom};
        // The tile's corners on screen.
        double[] sx = new double[4], sy = new double[4];
        for (int i = 0; i < 4; i++) {
            double dx = tx[i] - ax, dy = ty[i] - ay;
            sx[i] = ax + dx * cos - dy * sin;
            sy[i] = ay + dx * sin + dy * cos;
        }
        double[] vx = {0, w, w, 0}, vy = {0, 0, h, h};
        // Axes: the screen's two, then the turned tile's two.
        double[][] axes = {{1, 0}, {0, 1}, {cos, sin}, {-sin, cos}};
        for (double[] ax2 : axes) {
            double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE, vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double p = sx[i] * ax2[0] + sy[i] * ax2[1];
                tMin = Math.min(tMin, p);
                tMax = Math.max(tMax, p);
                double q = vx[i] * ax2[0] + vy[i] * ax2[1];
                vMin = Math.min(vMin, q);
                vMax = Math.max(vMax, q);
            }
            if (tMax <= vMin || vMax <= tMin) return false;
        }
        return true;
    }

    private void drawTraffic(Canvas c, int z, int x, int y) {
        String key = "t/" + z + "/" + x + "/" + y;
        visible.add(key);
        Bitmap b = trafficMemory.get(key);
        if (b != null) c.drawBitmap(b, null, dst, trafficPaint);
        Long at = trafficAt.get(key);
        long now = SystemClock.elapsedRealtime();
        // A stale tile stays on screen until its replacement arrives.
        if (b == null || at == null || now - at > trafficFreshMs) requestTraffic(key, z + "/" + x + "/" + y, now);
    }

    private void requestTraffic(String key, String tile, long now) {
        final String apiKey = trafficKey;
        if (released || apiKey == null || now < trafficBlockedUntil || loading.contains(key) || failed.contains(key)) {
            return;
        }
        loading.add(key);
        trafficPool.execute(() -> {
            final boolean skipped = !visible.contains(key);
            final int[] code = new int[1];
            final Bitmap got = skipped ? null : loadTraffic(tile, apiKey, code);
            main.post(() -> {
                loading.remove(key);
                if (released || !apiKey.equals(trafficKey)) return;
                if (got != null) {
                    if (trafficAt.size() > 3000) trafficAt.clear();
                    trafficMemory.put(key, got);
                    trafficAt.put(key, SystemClock.elapsedRealtime());
                    trafficStatus = "Map traffic: showing";
                    invalidate();
                } else if (code[0] == 401 || code[0] == 403 || code[0] == 429) {
                    // The key is wrong or out of allowance: asking for every other tile would only fail too.
                    trafficBlockedUntil = SystemClock.elapsedRealtime() + TRAFFIC_BLOCK_MS;
                    trafficStatus = code[0] != 429 ? "Map traffic: TomTom didn't accept the key"
                            : "Map traffic: the key's free allowance is used up for now";
                } else if (!skipped) {
                    trafficStatus = "Map traffic: couldn't reach TomTom";
                    failed.add(key);
                    main.postDelayed(() -> {
                        failed.remove(key);
                        invalidate();
                    }, 15000);
                }
            });
        });
    }

    private Bitmap loadTraffic(String tile, String apiKey, int[] code) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(TRAFFIC + tile + ".png?tileSize=256&key="
                    + java.net.URLEncoder.encode(apiKey, "UTF-8")).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            code[0] = c.getResponseCode();
            if (code[0] != 200) return null;
            Usage.add(Usage.TRAFFIC);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            Log.d(TAG, "Traffic tile " + tile + ": " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** A route as a line with a darker edge. */
    private void drawLine(Canvas c, double[] xs, double[] ys, double world, int edge, int fill) {
        if (xs == null || xs.length < 2) return;
        path.rewind();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < xs.length; i++) {
            float x = (float) ((xs[i] - cx) * world + ax), y = (float) ((ys[i] - cy) * world + ay);
            if (i == 0) path.moveTo(x, y);
            else if (Math.abs(x - lastX) + Math.abs(y - lastY) >= 1.5f || i == xs.length - 1) path.lineTo(x, y);
            else continue;
            lastX = x;
            lastY = y;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(9 * density);
        paint.setColor(edge);
        c.drawPath(path, paint);
        paint.setStrokeWidth(5.5f * density);
        paint.setColor(fill);
        c.drawPath(path, paint);
    }

    private void drawPin(Canvas c, float x, float y) {
        float r = 11 * density;
        path.rewind();
        path.moveTo(x, y);
        path.cubicTo(x - r * 0.35f, y - r * 1.2f, x - r, y - r * 1.5f, x - r, y - r * 2.3f);
        path.cubicTo(x - r, y - r * 3.6f, x + r, y - r * 3.6f, x + r, y - r * 2.3f);
        path.cubicTo(x + r, y - r * 1.5f, x + r * 0.35f, y - r * 1.2f, x, y);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE5392D);
        c.drawPath(path, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(x, y - r * 2.3f, r * 0.38f, paint);
    }

    private void drawCar(Canvas c, float x, float y) {
        float s = 13 * density;
        int save = c.save();
        c.rotate(carBearing, x, y);
        path.rewind();
        path.moveTo(x, y - s * 1.25f);
        path.lineTo(x + s * 0.95f, y + s);
        path.lineTo(x, y + s * 0.5f);
        path.lineTo(x - s * 0.95f, y + s);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1A73E8);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2.5f * density);
        paint.setColor(0xFFFFFFFF);
        c.drawPath(path, paint);
        c.restoreToCount(save);
    }

    // ------------------------------------------------------------------ tiles

    private void request(String key) {
        if (released || loading.contains(key) || failed.contains(key)) return;
        loading.add(key);
        // OpenStreetMap asks for no more than two downloads at a time; TomTom's tiles use the larger pool.
        (isTomTom(key) ? trafficPool : pool).execute(() -> {
            // Scrolled away before its turn came: don't fetch a tile nobody is looking at.
            final boolean skipped = !visible.contains(key);
            final Bitmap got = skipped ? null : load(key);
            main.post(() -> {
                loading.remove(key);
                if (released) return;
                if (got != null) {
                    memory.put(key, got);
                    invalidate();
                } else if (!skipped) {
                    // Don't hammer the server over a tile that won't load; try again in a while.
                    failed.add(key);
                    main.postDelayed(() -> {
                        failed.remove(key);
                        invalidate();
                    }, 15000);
                }
            });
        });
    }

    /** From disk if we have it and it's fresh, otherwise from the server (keeping a stale copy as a fallback). */
    private static boolean isTomTom(String key) {
        return key.startsWith("m/") || key.startsWith("n/") || key.startsWith("hn/");
    }

    private Bitmap load(String key) {
        if (key.startsWith("hn/")) {
            // The HUD's version of a TomTom night tile.
            Bitmap b = load(key.substring(1));
            return b == null ? null : nightRoadsOnly(b);
        }
        if (key.startsWith("h/")) {
            // The HUD's version of an OpenStreetMap tile: the same file on disk, recoloured once here.
            Bitmap b = load(key.substring(2));
            return b == null ? null : roadsOnly(b);
        }
        final boolean tomtom = isTomTom(key);
        final String apiKey = mapKey;
        if (tomtom && apiKey == null) return null;
        File file = new File(dir, key + ".png");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        boolean have = file.isFile() && file.length() > 0;
        if (have && System.currentTimeMillis() - file.lastModified() < (tomtom ? TOMTOM_KEEP_MS : KEEP_MS)) {
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
            if (b != null) return b;
        }
        HttpURLConnection c = null;
        try {
            String url = !tomtom ? TILES + key + ".png"
                    : TOMTOM_MAP + (key.startsWith("n/") ? "night/" : "main/") + key.substring(2)
                    + ".png?tileSize=256&key=" + java.net.URLEncoder.encode(apiKey, "UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (!tomtom) c.setRequestProperty("User-Agent", AGENT);
            int code = c.getResponseCode();
            if (tomtom && (code == 401 || code == 403 || code == 429)) {
                // The key is wrong or out of allowance: draw OpenStreetMap's map for a while instead.
                mapBlockedUntil = SystemClock.elapsedRealtime() + TRAFFIC_BLOCK_MS;
                mapStatus = (code == 429 ? "Map: the TomTom key's free allowance is used up for now"
                        : "Map: TomTom didn't accept the key") + "; showing OpenStreetMap";
                postInvalidate();
            } else if (tomtom && code == 200) {
                mapStatus = "Map: TomTom";
                Usage.add(Usage.MAP);
            }
            if (code == 200) {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                File part = new File(file.getPath() + ".part");
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!part.renameTo(file)) {
                    file.delete();
                    part.renameTo(file);
                }
                Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
                if (b != null) return b;
            }
        } catch (Exception e) {
            Log.d(TAG, "Tile " + key + ": " + e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
        return have ? BitmapFactory.decodeFile(file.getPath(), opts) : null;
    }

    /**
     * Keeps only the roads of an OpenStreetMap tile, for the HUD: main roads (drawn yellow, orange
     * or pink by OpenStreetMap) in amber, other roads (drawn white) in pale blue, and everything else
     * see-through. Checked against tiles of inner Sydney; white label halos come through too, which
     * leaves street names readable as outlines along their roads.
     */
    private static Bitmap roadsOnly(Bitmap tile) {
        int w = tile.getWidth(), h = tile.getHeight();
        int[] px = new int[w * h];
        tile.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (r >= 248 && g >= 248 && b >= 248) px[i] = 0xFF96CDEB;
            else if (r >= 225 && r - b >= 40 && b < 200 && g >= 120) px[i] = 0xFFFFC450;
            else px[i] = 0;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Keeps only the roads (and labels) of a TomTom night tile, for the HUD. The night map draws
     * them lighter than its dark blue-grey ground: main roads tan, which become amber, and the rest
     * grey, which become pale blue. Water, parks, buildings and the ground become see-through, and
     * the edges of lines fade so they stay smooth. Checked against TomTom's sample night tiles.
     */
    private static Bitmap nightRoadsOnly(Bitmap tile) {
        int w = tile.getWidth(), h = tile.getHeight();
        int[] px = new int[w * h];
        tile.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int lum = (r * 3 + g * 6 + b) / 10;
            if (lum < 66 || b - r >= 25) {
                px[i] = 0;
                continue;
            }
            int alpha = Math.min(255, (lum - 62) * 6);
            px[i] = (alpha << 24) | (r - b >= 30 ? 0xFFC450 : 0x96CDEB);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Keeps the tiles on disk under a limit by deleting the ones not touched for longest. */
    private void pruneDisk() {
        try {
            java.util.List<File> files = new java.util.ArrayList<>();
            collect(dir, files);
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= DISK_LIMIT) return;
            File[] sorted = files.toArray(new File[0]);
            Arrays.sort(sorted, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : sorted) {
                if (total <= DISK_TARGET) break;
                total -= f.length();
                f.delete();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't tidy the tile store", e);
        }
    }

    private static void collect(File d, java.util.List<File> out) {
        File[] list = d.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }
}
