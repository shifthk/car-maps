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
    private static final String AGENT = "CarMaps/2.0 (Android; +https://github.com/shifthk/car-maps)";
    private static final int TILE = 256;
    private static final int MIN_ZOOM = 2, MAX_ZOOM = 19;
    private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;
    private static final long DISK_LIMIT = 150L * 1024 * 1024, DISK_TARGET = 100L * 1024 * 1024;
    private static final long GLIDE_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
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

    /** Centre of the view, in map units: 0..1 across the whole world, x east and y south. */
    private double cx = 0.5, cy = 0.5;
    private double zoom = 3;
    private boolean follow = true;
    private boolean fitPending;

    private boolean haveCar;
    private double carX, carY, carFromX, carFromY, carToX, carToY;
    private float carBearing;
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
                cx += dx / world;
                cy += dy / world;
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
                double x = cx + (e.getX() - getWidth() / 2f) / world, y = cy + (e.getY() - getHeight() / 2f) / world;
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
        main.removeCallbacksAndMessages(null);
        memory.evictAll();
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
            tilePaint.setColorFilter(new ColorMatrixColorFilter(m));
        } else {
            tilePaint.setColorFilter(null);
        }
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
        carBearing = bearing;
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
        c.drawColor(dark ? 0xFF1B1F24 : 0xFFE8E6E1);

        // Glide the car from its last position to the new one over the second between GPS fixes.
        boolean gliding = false;
        if (haveCar) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - carMovedAt) / (float) GLIDE_MS);
            carX = carFromX + (carToX - carFromX) * t;
            carY = carFromY + (carToY - carFromY) * t;
            gliding = t < 1f;
            if (follow) {
                cx = carX;
                cy = carY;
            }
        }

        final Set<String> needed = ConcurrentHashMap.newKeySet();
        visible = needed;
        int z = Math.max(0, Math.min(MAX_ZOOM, (int) Math.floor(zoom + 0.5)));
        int n = 1 << z;
        double world = worldSize();
        double size = world / n;
        int x0 = (int) Math.floor((cx - w / 2.0 / world) * n), x1 = (int) Math.floor((cx + w / 2.0 / world) * n);
        int y0 = Math.max(0, (int) Math.floor((cy - h / 2.0 / world) * n));
        int y1 = Math.min(n - 1, (int) Math.floor((cy + h / 2.0 / world) * n));
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int wrapped = ((tx % n) + n) % n;
                float left = (float) ((tx / (double) n - cx) * world + w / 2.0);
                float top = (float) ((ty / (double) n - cy) * world + h / 2.0);
                // A hair of overlap hides the seams between tiles.
                dst.set(left, top, (float) (left + size) + 0.5f, (float) (top + size) + 0.5f);
                drawTile(c, z, wrapped, ty);
            }
        }

        drawRoute(c, w, h, world);
        if (havePin) drawPin(c, (float) ((pinX - cx) * world + w / 2.0), (float) ((pinY - cy) * world + h / 2.0));
        if (haveCar) drawCar(c, (float) ((carX - cx) * world + w / 2.0), (float) ((carY - cy) * world + h / 2.0));

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(11 * density);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(dark ? 0xB0000000 : 0xB0FFFFFF);
        String credit = "© OpenStreetMap contributors";
        float tw = paint.measureText(credit);
        c.drawRect(w - tw - 10 * density, h - 18 * density, w, h, paint);
        paint.setColor(dark ? 0xFFD0D6DC : 0xFF333333);
        c.drawText(credit, w - 5 * density, h - 5 * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (gliding) postInvalidateOnAnimation();
    }

    private void drawTile(Canvas c, int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        visible.add(key);
        Bitmap b = memory.get(key);
        if (b != null) {
            c.drawBitmap(b, null, dst, tilePaint);
            return;
        }
        request(key);
        // Until it arrives, stretch the matching part of a lower-zoom tile we already have.
        for (int up = 1; up <= 5 && z - up >= 0; up++) {
            Bitmap parent = memory.get((z - up) + "/" + (x >> up) + "/" + (y >> up));
            if (parent == null) continue;
            int cells = 1 << up, cell = parent.getWidth() / cells;
            if (cell < 1) break;
            int px = (x & (cells - 1)) * cell, py = (y & (cells - 1)) * cell;
            src.set(px, py, px + cell, py + cell);
            c.drawBitmap(parent, src, dst, tilePaint);
            return;
        }
    }

    private void drawRoute(Canvas c, int w, int h, double world) {
        double[] xs = routeX, ys = routeY;
        if (xs == null || xs.length < 2) return;
        path.rewind();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < xs.length; i++) {
            float x = (float) ((xs[i] - cx) * world + w / 2.0), y = (float) ((ys[i] - cy) * world + h / 2.0);
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
        paint.setColor(0xFF0B3D91);
        c.drawPath(path, paint);
        paint.setStrokeWidth(5.5f * density);
        paint.setColor(0xFF4C9BFF);
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
        pool.execute(() -> {
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
    private Bitmap load(String key) {
        File file = new File(dir, key + ".png");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        boolean have = file.isFile() && file.length() > 0;
        if (have && System.currentTimeMillis() - file.lastModified() < KEEP_MS) {
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
            if (b != null) return b;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(TILES + key + ".png").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", AGENT);
            if (c.getResponseCode() == 200) {
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
            Log.d(TAG, "Tile " + key + ": " + e);
        } finally {
            if (c != null) c.disconnect();
        }
        return have ? BitmapFactory.decodeFile(file.getPath(), opts) : null;
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
