package com.carmaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;

/**
 * A small picture of the HUD on the settings screen with its panels as boxes: drag a box to move
 * it, drag its corner handle to resize it. It shows one of two arrangements at a time, since they
 * never share the HUD: the directions and mini map (following a route), or the now playing card
 * and the clock (not following one). Each change is saved and sent to
 * the HUD straight away, so the real HUD follows the finger.
 */
final class HudLayoutView extends View {

    interface Listener {
        /** A box was picked up (the HUD should show its sample). */
        void onStart();

        /** A box moved or changed size (already saved). */
        void onChange();
    }

    private static final int MIN = 60;   // thousandths: the smallest a box can be made
    private static final String[] NAMES = {"Directions", "Mini map", "Now playing", "Time and date"};
    private static final int[] COLOURS = {0xFF35E0FF, 0xFFFFC450, 0xFF1ED760, 0xFFC79BFF};
    private static final int[] ROUTE = {Prefs.INFO, Prefs.MINIMAP}, IDLE = {Prefs.CARD, Prefs.CLOCK};
    /** The boxes being arranged, bottom one first. */
    private int[] shown = ROUTE;

    private final Prefs prefs;
    private final Listener listener;
    private final float density;
    private final float aspect;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hud = new RectF();
    private final RectF r = new RectF();
    private final int[][] boxes = new int[4][];

    private int dragging = -1;
    private boolean resizing;
    private float downX, downY;
    private int[] startBox;
    private long lastSent;

    HudLayoutView(Context c, Prefs prefs, Listener listener) {
        super(c);
        this.prefs = prefs;
        this.listener = listener;
        density = c.getResources().getDisplayMetrics().density;
        Display d = Hud.find(c.getSystemService(DisplayManager.class), prefs.hudDisplay());
        Point size = d == null ? new Point(0, 0) : Hud.realSize(d);
        aspect = size.x > 0 && size.y > 0 ? size.x / (float) size.y : 3f;
        reload();
    }

    /** Reads the boxes from the settings again (after a reset). */
    void reload() {
        boxes[Prefs.INFO] = prefs.hudBox(Prefs.INFO);
        boxes[Prefs.MINIMAP] = prefs.hudBox(Prefs.MINIMAP);
        boxes[Prefs.CARD] = prefs.hudBox(Prefs.CARD);
        boxes[Prefs.CLOCK] = prefs.hudBox(Prefs.CLOCK);
        invalidate();
    }

    /** @param idle true to arrange the card shown while not following a route */
    void setIdle(boolean idle) {
        shown = idle ? IDLE : ROUTE;
        dragging = -1;
        invalidate();
    }

    boolean isIdle() {
        return shown == IDLE;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w / aspect));
    }

    private void boxRect(int which, RectF out) {
        int[] b = boxes[which];
        out.set(hud.left + hud.width() * b[0] / 1000f, hud.top + hud.height() * b[1] / 1000f,
                hud.left + hud.width() * (b[0] + b[2]) / 1000f, hud.top + hud.height() * (b[1] + b[3]) / 1000f);
    }

    @Override
    protected void onDraw(Canvas c) {
        hud.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        paint.setColor(0xFF000000);
        c.drawRect(hud, paint);

        // Where the empty circle of the map around the car goes: roughly where the car's speed readout is.
        float radius = hud.height() * prefs.hudHole() / 200f;
        if (radius > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(0xFF5A6470);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * density, 5 * density}, 0));
            c.drawCircle(hud.centerX(), hud.centerY(), radius, paint);
            paint.setPathEffect(null);
        }

        boolean mapOn = prefs.hudMap();
        for (int i : shown) {
            boxRect(i, r);
            int colour = COLOURS[i];
            boolean off = (i == Prefs.MINIMAP && !mapOn) || (i == Prefs.CARD && !prefs.hudMusic())
                    || (i == Prefs.CLOCK && !prefs.hudClock());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((colour & 0x00FFFFFF) | (i == dragging ? 0x50000000 : 0x28000000));
            c.drawRect(r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(off ? 0xFF56606A : colour);
            c.drawRect(r, paint);
            // The resize handle, bottom right.
            float hs = handle();
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(r.right - hs, r.bottom - hs, r.right, r.bottom, paint);
            paint.setTextSize(13 * density);
            paint.setColor(off ? 0xFF8A949E : 0xFFFFFFFF);
            String label = !off ? NAMES[i] : i >= Prefs.CARD ? NAMES[i] + " (not chosen)" : NAMES[i] + " (off)";
            c.drawText(label, r.left + 6 * density, r.top + 17 * density, paint);
        }
    }

    private float handle() {
        return 16 * density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = -1;
                // The handles first (they can be hard to hit otherwise), then the boxes, top one first.
                float reach = handle() * 1.6f;
                for (int k = shown.length - 1; k >= 0 && dragging < 0; k--) {
                    int i = shown[k];
                    boxRect(i, r);
                    if (Math.abs(x - r.right) < reach && Math.abs(y - r.bottom) < reach) {
                        dragging = i;
                        resizing = true;
                    }
                }
                for (int k = shown.length - 1; k >= 0 && dragging < 0; k--) {
                    int i = shown[k];
                    boxRect(i, r);
                    if (r.contains(x, y)) {
                        dragging = i;
                        resizing = false;
                    }
                }
                if (dragging < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                downY = y;
                startBox = boxes[dragging].clone();
                listener.onStart();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging < 0) return false;
                int dx = Math.round((x - downX) * 1000 / hud.width());
                int dy = Math.round((y - downY) * 1000 / hud.height());
                int[] b = startBox.clone();
                if (resizing) {
                    b[2] = Math.max(MIN, Math.min(1000 - b[0], b[2] + dx));
                    b[3] = Math.max(MIN, Math.min(1000 - b[1], b[3] + dy));
                } else {
                    b[0] = Math.max(0, Math.min(1000 - b[2], b[0] + dx));
                    b[1] = Math.max(0, Math.min(1000 - b[3], b[1] + dy));
                }
                boxes[dragging] = b;
                invalidate();
                long now = System.currentTimeMillis();
                if (now - lastSent > 80) save(now);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging < 0) return false;
                save(System.currentTimeMillis());
                dragging = -1;
                invalidate();
                return true;
            default:
                return dragging >= 0;
        }
    }

    private void save(long now) {
        lastSent = now;
        prefs.setHudBox(dragging, boxes[dragging]);
        listener.onChange();
    }
}
