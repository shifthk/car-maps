package com.carmaps;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Draws a row of lane arrows: the lanes to be in drawn with one paint, the others with another. */
final class Lanes {

    private Lanes() {
    }

    /** @param cell width and height of each lane's arrow */
    static void draw(Canvas c, Nav.LaneSpot spot, float left, float top, float cell, Paint on, Paint off) {
        for (int i = 0; i < spot.arrows.length; i++) {
            Maneuvers.draw(c, Maneuvers.of(name(spot.arrows[i])), left + i * cell, top, cell,
                    spot.follow[i] ? on : off);
        }
    }

    /** TomTom's lane directions, as the names {@link Maneuvers} draws. */
    private static String name(String direction) {
        switch (direction) {
            case "SLIGHT_RIGHT": return "TURN_SLIGHT_RIGHT";
            case "RIGHT": return "TURN_RIGHT";
            case "SHARP_RIGHT": return "TURN_SHARP_RIGHT";
            case "RIGHT_U_TURN": return "UTURN_RIGHT";
            case "SLIGHT_LEFT": return "TURN_SLIGHT_LEFT";
            case "LEFT": return "TURN_LEFT";
            case "SHARP_LEFT": return "TURN_SHARP_LEFT";
            case "LEFT_U_TURN": return "UTURN_LEFT";
            default: return "STRAIGHT";
        }
    }
}
