# Car Maps

Google Maps in a floating window for an Android car screen (built for the Zeekr 7X), with turn-by-turn directions and a mini map on the head-up display.

- **Floating window**: the Google Maps website, drawn over every other app. Search, traffic, Google's route and its arrival time all work there, signed in to Google or not.
  - Tap the round button on the window's top right corner to hide or show the map.
  - Drag the round button to move the window; drag the grip in the bottom left corner to resize it.
- **Guide on HUD**: when the page shows directions to a place, a bar under the map offers guidance to the same place. While guiding, the HUD shows the next turn (arrow, distance, instruction), the arrival time, time and distance left, and a mini map of the route ahead with the car pointing up. The bar under the map shows the same turn, and directions are spoken if the car has a text-to-speech engine.

No API key or billing is needed.

## How it works

| Part | What it does |
| --- | --- |
| `MapWindow` | The overlay window and its `WebView` on google.com/maps. Reads the destination from the page while it shows directions. |
| `Router` | Driving routes from OpenStreetMap data (the FOSSGIS Valhalla server), and Nominatim as a fallback to find a place by name. |
| `Nav` | Follows the route by GPS: snaps the position to the route line, finds the next turn, notices when the car leaves the route. |
| `Hud` | Finds the HUD display and draws on it. |
| `MapService` | Foreground service tying these together; asks for a new route when the car leaves the old one. |
| `MainActivity` | Permissions and settings, including where the panel sits on the HUD. |

**The HUD route is not Google's route.** The Google Maps website gives an app no route data, so guidance comes from OpenStreetMap. It can take different roads from the line Google draws, and its arrival time ignores traffic. Google's traffic-aware time stays visible in the window.

**Reading the destination.** The site's address only carries the place's name and ID. The coordinates come from the answer to the page's own directions request, which the app fetches again from inside the page. If Google changes that page, the app falls back to looking the name up in OpenStreetMap, which is less exact (the bar then says "approximate location").

**The HUD.** The 7X exposes its HUD to Android as a second display, 1440 x 480 (display 3 on the cars checked). The app opens a transparent `Presentation` on it. A HUD is a projection, so black is see-through: only the arrow, text and route line light up. This approach was learned from [zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car) (dts88) and CAMCORDER (FerixzTanz); no code was copied from either (both are GPL-3.0).

**Signing in to Google** is optional and only adds saved places and history. Google turns away sign-ins from apps' built-in web views, so the window identifies itself as ordinary Chrome. Google may still refuse.

## Build

JDK 17+ (Android Studio's bundled one works) and Android SDK platform 35.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up on the car

1. Install the APK and open **Car Maps**.
2. Tap **Allow display over other apps**, then **Allow location**, then **Allow location all the time** (needed for the map to come back by itself when the car starts).
3. Tap **Start the floating map**. In the window, choose **Keep using web** when Google offers its app.
4. Under **Head-up display**, tap **Show a sample on the HUD** and use the sliders to place it clear of the car's own HUD readouts. The status lines at the top list every display the car reports and which one looks like the HUD; pick one by hand under **HUD display** if the automatic choice is wrong.

## Tested and not tested

Tested on a Lenovo tablet (Android 15) with a simulated 1440 x 480 second display: the floating window, typing in the search box, directions, reading the destination, routing, and the HUD picture.

Not tested: anything on the car itself (whether it gives the app a GPS position, the real HUD, starting with the car), driving along a route (turn progress, rerouting, arrival, speech), and signing in to Google.
