package com.carmaps;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turn arrows, drawn in a 100 x 100 box: a stroked line plus a filled arrowhead. Kept as SVG-style
 * path text so the HUD (Canvas) and the floating map (SVG) draw the same shapes.
 */
final class Maneuvers {

    static final class Icon {
        final String stroke, fill;
        /** Drawn flipped left-to-right (the right-hand versions of the left-hand shapes). */
        final boolean mirror;

        Icon(String stroke, String fill, boolean mirror) {
            this.stroke = stroke;
            this.fill = fill;
            this.mirror = mirror;
        }

        JSONObject json() {
            try {
                return new JSONObject().put("s", stroke).put("f", fill).put("m", mirror);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    private static final String[] STRAIGHT = {"M50 88 L50 30", "M50 10 L32 34 L68 34 Z"};
    private static final String[] LEFT = {"M64 88 L64 46 L34 46", "M12 46 L36 28 L36 64 Z"};
    private static final String[] SLIGHT_LEFT = {"M58 88 L58 58 L38 34", "M24 19 L52 23 L28 47 Z"};
    private static final String[] SHARP_LEFT = {"M62 88 L62 38 L40 60", "M26 74 L54 70 L30 46 Z"};
    private static final String[] UTURN_LEFT = {"M68 88 L68 40 C68 16 34 16 34 40 L34 58", "M34 80 L16 56 L52 56 Z"};
    private static final String[] MERGE = {"M50 88 L50 30 M76 88 L76 76 L50 54", "M50 10 L32 34 L68 34 Z"};
    private static final String[] ROUNDABOUT = {
            "M50 92 L50 68 M50 32 C59.9 32 68 40.1 68 50 C68 59.9 59.9 68 50 68 C40.1 68 32 59.9 32 50 "
                    + "C32 40.1 40.1 32 50 32 Z M50 32 L50 24",
            "M50 6 L36 26 L64 26 Z"};
    private static final String[] DESTINATION = {"",
            "M50 10 C33 10 22 22 22 38 C22 58 50 90 50 90 C50 90 78 58 78 38 C78 22 67 10 50 10 Z "
                    + "M50 28 C55.5 28 60 32.5 60 38 C60 43.5 55.5 48 50 48 C44.5 48 40 43.5 40 38 "
                    + "C40 32.5 44.5 28 50 28 Z"};

    private Maneuvers() {
    }

    static Icon of(String maneuver) {
        String m = maneuver == null ? "" : maneuver;
        boolean right = m.endsWith("_RIGHT");
        String[] shape;
        if (m.startsWith("TURN_SLIGHT") || m.startsWith("RAMP") || m.startsWith("FORK")) shape = SLIGHT_LEFT;
        else if (m.startsWith("TURN_SHARP")) shape = SHARP_LEFT;
        else if (m.startsWith("UTURN")) shape = UTURN_LEFT;
        else if (m.startsWith("TURN_")) shape = LEFT;
        else if (m.startsWith("ROUNDABOUT")) {
            shape = ROUNDABOUT;
            right = false;
        } else if (m.equals("MERGE")) shape = MERGE;
        else if (m.equals("DESTINATION")) shape = DESTINATION;
        else shape = STRAIGHT;
        return new Icon(shape[0], shape[1], right);
    }

    /** Draws the icon into a square at (left, top). The paints' colours are used; styles are set here. */
    static void draw(Canvas c, Icon icon, float left, float top, float size, Paint paint) {
        int save = c.save();
        c.translate(left, top);
        c.scale(size / 100f, size / 100f);
        if (icon.mirror) c.scale(-1f, 1f, 50f, 50f);
        if (!icon.stroke.isEmpty()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(11f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(parse(icon.stroke), paint);
        }
        if (!icon.fill.isEmpty()) {
            Path p = parse(icon.fill);
            p.setFillType(Path.FillType.EVEN_ODD);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(p, paint);
        }
        c.restoreToCount(save);
    }

    /** Just enough of SVG path syntax for the shapes above: absolute M, L, C and Z. */
    static Path parse(String d) {
        Path path = new Path();
        String[] t = d.trim().split("[\\s,]+");
        int i = 0;
        char cmd = 'M';
        while (i < t.length) {
            String tok = t[i];
            char first = tok.charAt(0);
            if (Character.isLetter(first)) {
                cmd = first;
                if (tok.length() > 1) t[i] = tok.substring(1);
                else i++;
                if (cmd == 'Z') {
                    path.close();
                    continue;
                }
            }
            switch (cmd) {
                case 'M':
                    path.moveTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    cmd = 'L';
                    break;
                case 'L':
                    path.lineTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case 'C':
                    path.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5]));
                    i += 6;
                    break;
                default:
                    return path;
            }
        }
        return path;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }
}
