package com.carmaps;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * Directions and a mini map (the route ahead as a line) on the head-up display.
 *
 * <p>The Zeekr 7X exposes its HUD to Android as a secondary display (1440 x 480; id 3 on the cars
 * checked). The picture goes in an overlay window on that display, which sits above everything else
 * shown there. The HUD is a projection, so black means "no light": everything here is bright shapes
 * on black, and everything else is left see-through.</p>
 *
 * <p>How to find the display and put a window on it was learned from zeekr-shortcut-car (dts88) and
 * CAMCORDER (FerixzTanz), who worked it out on their own cars. No code is copied from either.</p>
 */
final class Hud {

    private static final String TAG = "Hud";
    private static final int HUD_W = 1440, HUD_H = 480;
    private static final int CLUSTER_W = 2560, CLUSTER_H = 536;
    private static final int FALLBACK_ID = 3;
    private static final long RETRY_MS = 5000;
    private static final long TEST_MS = 20000;

    private final Context context;
    private final Prefs prefs;
    private final DisplayManager dm;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The window the picture is in: an overlay window (these two), or else a presentation. */
    private WindowManager overlayWm;
    private WindowManager.LayoutParams overlayLp;
    private Presentation presentation;
    private Panel panel;
    private int shownOn = -1;

    private boolean navigating;
    private boolean testing;
    private double bearing;
    /** The car's last position, for the map around it shown while no route is being followed. */
    private boolean haveCar;
    private double carLat, carLng;
    private float carBearing;
    private Nav.Route route;
    private Nav.Progress progress;
    private String dist = "", text = "", footer = "";
    /** Null while rerouting. */
    private Maneuvers.Icon icon = Maneuvers.of("");
    /** Why the HUD isn't showing, for the settings screen. */
    static volatile String status = "Not started";

    private final Runnable evaluate = this::evaluate;
    private final Runnable endTest = () -> {
        testing = false;
        evaluate();
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
            schedule();
        }

        @Override
        public void onDisplayRemoved(int id) {
            schedule();
        }

        @Override
        public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY) schedule();
        }
    };

    Hud(Context context, Prefs prefs) {
        this.context = context;
        this.prefs = prefs;
        this.dm = context.getSystemService(DisplayManager.class);
        dm.registerDisplayListener(displayListener, main);
    }

    // ------------------------------------------------------------------ which display

    /** The HUD's display right now, or null. */
    static Display find(DisplayManager dm, int chosenId) {
        Display[] all;
        try {
            all = dm.getDisplays();
        } catch (RuntimeException e) {
            return null;
        }
        if (chosenId >= 0) {
            for (Display d : all) {
                if (d.getDisplayId() == chosenId && d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
            }
            return null;
        }
        Display fallback = null;
        for (Display d : all) {
            // A HUD that is switched off stays in the list: drawing on it would show nowhere.
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || d.getState() != Display.STATE_ON) continue;
            Point size = realSize(d);
            if (size.x == HUD_W && size.y == HUD_H) return d;
            // Never fall back onto the instrument cluster.
            if (d.getDisplayId() == FALLBACK_ID && !(size.x == CLUSTER_W && size.y == CLUSTER_H)) fallback = d;
        }
        return fallback;
    }

    static Point realSize(Display d) {
        Point size = new Point();
        try {
            d.getRealSize(size);
        } catch (RuntimeException ignored) {
            // left at 0 x 0, which matches nothing
        }
        return size;
    }

    /** Every display Android reports, for the settings screen. */
    static String describeDisplays(Context c) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        StringBuilder sb = new StringBuilder();
        Display hud = find(dm, -1);
        for (Display d : dm.getDisplays()) {
            Point size = realSize(d);
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.format(Locale.US, "Display %d: %d x %d%s%s", d.getDisplayId(), size.x, size.y,
                    d.getState() == Display.STATE_ON ? "" : " (off)",
                    d.getDisplayId() == Display.DEFAULT_DISPLAY ? " — main screen"
                            : hud != null && hud.getDisplayId() == d.getDisplayId() ? " — looks like the HUD" : ""));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ what to show

    /** Which way the car is facing, for when it is off the route. */
    void setBearing(double bearing) {
        this.bearing = bearing;
    }

    /** Where the car is, for the map around it shown while no route is being followed. */
    void setCar(double lat, double lng, float bearing) {
        boolean first = !haveCar;
        haveCar = true;
        carLat = lat;
        carLng = lng;
        carBearing = bearing;
        if (panel != null) panel.onCar();
        if (first) evaluate();
    }

    /** The route being followed (or previewed); null when there is none. */
    void setRoute(Nav.Route route) {
        this.route = route;
        if (route == null) progress = null;
        if (panel != null) panel.onNav();
    }

    /** @param p where the car is on the route, or null when not navigating */
    void setNav(Nav.Progress p) {
        boolean was = navigating;
        navigating = p != null;
        progress = p;
        if (p != null && p.offRoute) {
            // The old route's turn no longer applies: show nothing to follow until the new route arrives.
            icon = null;
            dist = "Rerouting";
            text = "Finding a new route";
        } else if (p != null) {
            icon = Maneuvers.of(p.maneuver);
            dist = Geo.distance(p.distToManeuver);
            text = p.text;
            footer = Geo.clock(p.eta) + "  ·  " + Geo.duration(p.remainSec) + "  ·  " + Geo.distance(p.remainDist);
        }
        if (was != navigating) evaluate();
        else if (panel != null) panel.onNav();
    }

    /** Shows a sample for a few seconds so the panel can be positioned from the settings screen. */
    void test() {
        testing = true;
        main.removeCallbacks(endTest);
        main.postDelayed(endTest, TEST_MS);
        evaluate();
    }

    /** Settings changed. */
    void refresh() {
        evaluate();
        if (panel != null) panel.applyPrefs();
    }

    void release() {
        main.removeCallbacksAndMessages(null);
        dm.unregisterDisplayListener(displayListener);
        dismiss();
    }

    private void schedule() {
        main.removeCallbacks(evaluate);
        main.postDelayed(evaluate, 300);
    }

    private boolean wanted() {
        return testing || (prefs.hud() && (navigating || surround()));
    }

    /** The map around the car, which takes the whole HUD while no route is being followed. */
    private boolean surround() {
        return !navigating && !testing && haveCar && prefs.hudSurround();
    }

    private boolean showing() {
        if (panel == null) return false;
        return presentation != null ? presentation.isShowing() : panel.isAttachedToWindow();
    }

    /** Which kind of window is up: true for the overlay kind. */
    private boolean shownAsOverlay() {
        return overlayWm != null;
    }

    private void evaluate() {
        main.removeCallbacks(evaluate);
        if (!wanted()) {
            dismiss();
            status = !prefs.hud() ? "Off" : prefs.hudSurround() ? "Waiting for the car's position"
                    : "Ready (shows while guiding)";
            return;
        }
        Display display = find(dm, prefs.hudDisplay());
        if (display == null) {
            dismiss();
            status = "No HUD display found (is the HUD switched on?)";
            main.postDelayed(evaluate, RETRY_MS);
            return;
        }
        if (showing() && shownOn == display.getDisplayId() && shownAsOverlay() == prefs.hudOverlay()) {
            panel.applyPrefs();
            return;
        }
        dismiss();
        show(display);
        if (panel == null) main.postDelayed(evaluate, RETRY_MS);
    }

    // ------------------------------------------------------------------ the window

    /**
     * Two kinds of window can be put on the HUD's display. A Presentation (an ordinary second-screen
     * window) is what the apps known to work on this car use, so it is tried first; an overlay window
     * (the "display over other apps" kind) is the other, and the setting swaps the order. Either way
     * the window is see-through apart from the arrow, text and route line; the gap in the middle
     * keeps them clear of the car's own readouts.
     */
    private void show(Display display) {
        boolean overlayFirst = prefs.hudOverlay();
        Throwable failure = overlayFirst ? showOverlay(display) : showPresentation(display);
        if (failure != null) {
            Log.w(TAG, "First kind of window failed on display " + display.getDisplayId() + "; trying the other", failure);
            Throwable second = overlayFirst ? showPresentation(display) : showOverlay(display);
            if (second != null) {
                Log.e(TAG, "Couldn't open a window on display " + display.getDisplayId(), second);
                status = "Couldn't draw on display " + display.getDisplayId() + ": " + second.getMessage();
                return;
            }
        }
        shownOn = display.getDisplayId();
        status = "Showing on display " + shownOn + (overlayWm != null ? " (display-over-apps window)" : " (second-screen window)");
        panel.applyPrefs();
    }

    /** @return null if it worked */
    private Throwable showOverlay(Display display) {
        if (Build.VERSION.SDK_INT < 30) return new UnsupportedOperationException("needs Android 11");
        if (!Settings.canDrawOverlays(context)) return new SecurityException("display over other apps isn't allowed");
        try {
            Context dc = context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager wm = dc.getSystemService(WindowManager.class);
            Panel view = new Panel(dc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.alpha = prefs.hudOpacity() / 100f;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            wm.addView(view, lp);
            overlayWm = wm;
            overlayLp = lp;
            panel = view;
            return null;
        } catch (Throwable t) {
            overlayWm = null;
            overlayLp = null;
            return t;
        }
    }

    /** @return null if it worked */
    private Throwable showPresentation(Display display) {
        Presentation p = null;
        try {
            p = new Presentation(context, display);
            Panel view = new Panel(p.getContext());
            FrameLayout root = new FrameLayout(p.getContext());
            root.setBackgroundColor(0);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            p.setContentView(root);
            Window window = p.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(0));
            }
            p.show();
            presentation = p;
            panel = view;
            return null;
        } catch (Throwable t) {
            if (p != null) {
                try {
                    p.dismiss();
                } catch (RuntimeException ignored) {
                    // never shown
                }
            }
            return t;
        }
    }

    private void dismiss() {
        Presentation p = presentation;
        Panel v = panel;
        WindowManager wm = overlayWm;
        presentation = null;
        panel = null;
        if (v != null) v.release();
        overlayWm = null;
        overlayLp = null;
        shownOn = -1;
        if (wm != null && v != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
                // the display went away and took the window with it
            }
        }
        if (p != null) {
            try {
                p.dismiss();
            } catch (RuntimeException ignored) {
                // the display went away and took the window with it
            }
        }
    }

    /** How bright the whole picture is: the window's opacity. */
    private void applyAlpha() {
        float alpha = prefs.hudOpacity() / 100f;
        if (overlayWm != null && overlayLp != null && panel != null) {
            if (overlayLp.alpha != alpha) {
                overlayLp.alpha = alpha;
                try {
                    overlayWm.updateViewLayout(panel, overlayLp);
                } catch (RuntimeException ignored) {
                    // the window is gone; the next evaluate() rebuilds it
                }
            }
            return;
        }
        Window window = presentation == null ? null : presentation.getWindow();
        if (window != null) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            if (attrs.alpha != alpha) {
                attrs.alpha = alpha;
                window.setAttributes(attrs);
            }
        }
    }

    // ------------------------------------------------------------------ the picture

    /**
     * Fills the HUD (transparent). While following a route: the directions on the left, the mini
     * map on the right, and an empty gap between them. Otherwise: a map of the roads around the car
     * across the whole HUD, with an empty circle in the middle. The gap and the circle are left clear
     * for what the car itself shows in the middle of the HUD (its speed readout).
     */
    private final class Panel extends ViewGroup {
        private final InfoView info;
        private final FrameLayout mapBox;
        private final LineView line;
        private final TileMap around;
        private final Path hole = new Path();

        Panel(Context c) {
            super(c);
            around = new TileMap(c);
            around.setHudStyle(true);
            around.setHeadingUp(true);
            around.setTurnedAnchor(0.5f);
            around.setFollow(true);
            around.setZoom(16);
            addView(around);
            info = new InfoView(c);
            addView(info);
            mapBox = new FrameLayout(c);
            line = new LineView(c);
            mapBox.addView(line, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mapBox.addView(new Edges(c), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(mapBox);
            onCar();
            applyPrefs();
        }

        void release() {
            around.release();
        }

        private boolean showMap() {
            return prefs.hudMap() && (testing || (route != null && progress != null));
        }

        void applyPrefs() {
            applyAlpha();
            applyMapSource();
            boolean whole = surround();
            around.setVisibility(whole ? VISIBLE : GONE);
            info.setVisibility(whole ? GONE : VISIBLE);
            mapBox.setVisibility(!whole && showMap() ? VISIBLE : INVISIBLE);
            requestLayout();
            invalidate();
            info.invalidate();
            line.invalidate();
        }

        void onNav() {
            if ((mapBox.getVisibility() == VISIBLE) != (!surround() && showMap())
                    || (around.getVisibility() == VISIBLE) != surround()) {
                applyPrefs();
            }
            info.invalidate();
            line.invalidate();
        }

        void onCar() {
            if (haveCar) around.setCar(carLat, carLng, carBearing);
        }

        /** TomTom's map with a key (its traffic layer is never asked for here), OpenStreetMap's without. */
        private void applyMapSource() {
            String key = prefs.tomtomKey();
            around.setMap(key.isEmpty() ? null : key);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            int[] r = rects(w, h);
            around.measure(MeasureSpec.makeMeasureSpec(Math.max(1, w), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, h), MeasureSpec.EXACTLY));
            info.measure(MeasureSpec.makeMeasureSpec(Math.max(1, r[2]), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, r[6]), MeasureSpec.EXACTLY));
            mapBox.measure(MeasureSpec.makeMeasureSpec(Math.max(1, r[5]), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, r[7]), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int[] p = rects(r - l, b - t);
            around.layout(0, 0, r - l, b - t);
            info.layout(p[0], p[1], p[0] + Math.max(1, p[2]), p[1] + p[6]);
            mapBox.layout(p[3], p[4], p[3] + Math.max(1, p[5]), p[4] + Math.max(1, p[7]));
        }

        /** Leaves the circle in the middle of the map around the car undrawn, so nothing there lights up. */
        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child != around) return super.drawChild(canvas, child, drawingTime);
            float radius = getHeight() * prefs.hudHole() / 200f;
            if (radius <= 0) return super.drawChild(canvas, child, drawingTime);
            hole.rewind();
            hole.addCircle(getWidth() / 2f, getHeight() / 2f, radius, Path.Direction.CW);
            int save = canvas.save();
            if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(hole);
            else canvas.clipPath(hole, android.graphics.Region.Op.DIFFERENCE);
            boolean more = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(save);
            return more;
        }

        /**
         * Each panel sits in the box the settings give it, in thousandths of the HUD's width and
         * height; the settings screen lets the boxes be dragged and resized. The space between them is
         * whatever the boxes leave, which is how the car's own speed readout is kept clear.
         *
         * @return {info left, info top, info width, map left, map top, map width, info height, map height}
         */
        private int[] rects(int w, int h) {
            int[] i = prefs.hudBox(Prefs.INFO), m = prefs.hudBox(Prefs.MINIMAP);
            return new int[]{w * i[0] / 1000, h * i[1] / 1000, Math.max(1, w * i[2] / 1000),
                    w * m[0] / 1000, h * m[1] / 1000, showMap() ? Math.max(1, w * m[2] / 1000) : 0,
                    Math.max(1, h * i[3] / 1000), Math.max(1, h * m[3] / 1000)};
        }
    }

    /**
     * The next turn: arrow, distance, what to do, and the arrival line (or the lanes to be in). In a
     * wide space the arrow sits beside the text; in a narrower one, the arrow and distance share the
     * top and the instruction wraps onto two lines below them.
     */
    private final class InfoView extends View {
        private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint foot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        /** Lanes not to take. */
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);

        InfoView(Context c) {
            super(c);
            shape.setColor(0xFF35E0FF);
            big.setColor(0xFFFFFFFF);
            big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            small.setColor(0xFFFFFFFF);
            foot.setColor(0xFF5CFF9D);
            foot.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            dim.setColor(0xFF56606A);
        }

        /** Sets the paint's size to the largest up to {@code size} at which the text fits in {@code width}. */
        private void fit(TextPaint p, String text, float size, float width) {
            p.setTextSize(size);
            float tw = p.measureText(text);
            if (tw > width && tw > 0) p.setTextSize(Math.max(8, size * width / tw));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            if (w < h * 0.3f) return;
            Maneuvers.Icon ic = icon;
            String d = dist, t = text, f = footer;
            if (!navigating && !testing) return;
            if (testing && !navigating) {
                ic = Maneuvers.of("TURN_LEFT");
                d = "350 m";
                t = "Turn left onto Example Road";
                f = "14:32  ·  25 min  ·  12 km";
            }
            Nav.Progress p = progress;
            boolean live = navigating && p != null && !p.offRoute;
            Nav.LaneSpot spot = live ? p.lanes : null;
            float pad = h * 0.04f;

            if (w >= h * 2f) {
                // Wide: arrow on the left, distance and instruction beside it, arrival line along the bottom.
                float iconSize = Math.min(h * 0.62f, w * 0.3f);
                float textLeft = pad;
                if (ic != null) {
                    Maneuvers.draw(c, ic, 0, 0, iconSize, shape);
                    textLeft += iconSize;
                }
                fit(big, d, h * 0.32f, w - textLeft - pad);
                float y = pad * 0.5f - big.ascent();
                c.drawText(d, textLeft, y, big);
                fit(small, t, h * 0.14f, Math.max(10, w - textLeft - pad) * 1.6f);
                y += big.descent() - small.ascent();
                c.drawText(TextUtils.ellipsize(t, small, Math.max(10, w - textLeft - pad), TextUtils.TruncateAt.END)
                        .toString(), textLeft, y, small);
            } else {
                // Narrower: arrow and distance across the top, the instruction on up to two lines under them.
                float iconSize = Math.min(h * 0.46f, w * 0.48f);
                float textLeft = pad;
                if (ic != null) {
                    Maneuvers.draw(c, ic, 0, 0, iconSize, shape);
                    textLeft += iconSize;
                }
                fit(big, d, iconSize * 0.6f, w - textLeft - pad);
                c.drawText(d, textLeft, iconSize * 0.5f - (big.ascent() + big.descent()) / 2f, big);
                small.setTextSize(Math.min(h * 0.1f, w * 0.09f));
                android.text.StaticLayout lines = android.text.StaticLayout.Builder
                        .obtain(t, 0, t.length(), small, (int) Math.max(10, w - pad * 2))
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build();
                c.save();
                c.translate(pad, iconSize + pad * 0.5f);
                lines.draw(c);
                c.restore();
            }

            if (spot != null) {
                // Coming up to a junction with marked lanes: the lanes to be in take the bottom line.
                float cell = Math.min(h * 0.24f, (w - pad) / spot.arrows.length);
                Lanes.draw(c, spot, pad, h - cell, cell, shape, dim);
            } else {
                fit(foot, f, w >= h * 2f ? h * 0.16f : h * 0.12f, w - pad * 2);
                c.drawText(f, pad, h - pad - foot.descent(), foot);
            }
        }
    }

    /** The simple mini map: the route ahead as a line, turned so the car always points up. */
    private final class LineView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private static final double BEHIND_M = 150, AHEAD_M = 700, VIEW_M = 420;

        LineView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            Nav.Route r = route;
            Nav.Progress p = progress;
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h * 0.66f;
            if (r == null || p == null) {
                if (testing) drawSample(c, w, h, cx, cy);
                return;
            }
            if (p.offRoute) return;   // the old route would be drawn from the wrong place: leave it blank
            double k = cy / VIEW_M;   // pixels per metre: VIEW_M metres of road between the car and the top edge
            double b = Math.toRadians(p.offRoute ? bearing : p.snapBearing);
            double cos = Math.cos(b), sin = Math.sin(b);
            double cosLat = Math.cos(Math.toRadians(p.snapLat));
            double m = Math.toRadians(1) * Geo.R;

            path.rewind();
            boolean started = false;
            int n = r.lat.length;
            int i = Math.max(0, p.segment);
            while (i > 0 && r.cum[i] > p.along - BEHIND_M) i--;
            for (; i < n && r.cum[i] < p.along + AHEAD_M; i++) {
                double dx = (r.lng[i] - p.snapLng) * cosLat * m, dy = (r.lat[i] - p.snapLat) * m;
                float x = (float) (cx + (dx * cos - dy * sin) * k);
                float y = (float) (cy - (dx * sin + dy * cos) * k);
                if (started) path.lineTo(x, y);
                else {
                    path.moveTo(x, y);
                    started = true;
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.min(w, h) * 0.045f);
            paint.setColor(0xFF35E0FF);
            c.drawPath(path, paint);

            drawCar(c, Math.min(w, h), cx, cy);
        }

        /** What the mini map looks like, for placing the panel when no route is being followed. */
        private void drawSample(Canvas c, float w, float h, float cx, float cy) {
            path.rewind();
            path.moveTo(cx, h);
            path.lineTo(cx, h * 0.38f);
            path.lineTo(w * 0.12f, h * 0.38f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.min(w, h) * 0.045f);
            paint.setColor(0xFF35E0FF);
            c.drawPath(path, paint);
            drawCar(c, Math.min(w, h), cx, cy);
        }

        private void drawCar(Canvas c, float h, float cx, float cy) {
            float s = h * 0.07f;
            path.rewind();
            path.moveTo(cx, cy - s * 1.3f);
            path.lineTo(cx + s, cy + s);
            path.lineTo(cx, cy + s * 0.5f);
            path.lineTo(cx - s, cy + s);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            c.drawPath(path, paint);
        }
    }

    /** Fades the mini map to black at its edges, so it has no hard rectangle floating over the road. */
    private static final class Edges extends View {
        private final Paint paint = new Paint();
        private Shader[] shaders;
        private int builtW, builtH;

        Edges(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float f = Math.min(w, h) * 0.14f;
            if (shaders == null || builtW != w || builtH != h) {
                int black = 0xFF000000, clear = 0x00000000;
                shaders = new Shader[]{
                        new LinearGradient(0, 0, f, 0, black, clear, Shader.TileMode.CLAMP),
                        new LinearGradient(w, 0, w - f, 0, black, clear, Shader.TileMode.CLAMP),
                        new LinearGradient(0, 0, 0, f, black, clear, Shader.TileMode.CLAMP),
                        new LinearGradient(0, h, 0, h - f, black, clear, Shader.TileMode.CLAMP)};
                builtW = w;
                builtH = h;
            }
            paint.setShader(shaders[0]);
            c.drawRect(0, 0, f, h, paint);
            paint.setShader(shaders[1]);
            c.drawRect(w - f, 0, w, h, paint);
            paint.setShader(shaders[2]);
            c.drawRect(0, 0, w, f, paint);
            paint.setShader(shaders[3]);
            c.drawRect(0, h - f, w, h, paint);
        }
    }
}
