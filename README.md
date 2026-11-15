# Car Maps

An OpenStreetMap map in a floating window for an Android car screen (built for the Zeekr 7X), with turn-by-turn directions and a mini map on the head-up display. Everything comes from OpenStreetMap: no Google, no API key, no account.

- **Floating window**: a map drawn over every other app, with a search box above it.
  - Tap the round button on the window's top right corner to hide or show the map.
  - Drag the round button to move the window; drag the grip in the bottom left corner to resize it.
  - Drag the map to move it, pinch or double-tap to zoom. The ◎ button goes back to following the car.
- **Finding somewhere**: type in the search box (results appear as you type, nearest first), pick one of your recent places, or hold a finger on the map to drop a pin.
- **Route**: the app draws the driving route and shows its time, distance and arrival time. Tap **Start**.
- **Guidance**: the next turn shows in the bar under the map, on the HUD (arrow, distance, instruction, arrival time, and a mini map of the route ahead with the car pointing up), and is spoken if the car has a text-to-speech engine. Leave the route and it finds a new one.

OpenStreetMap has no live traffic, so there is no traffic layer and arrival times assume clear roads.

## How it works

| Part | What it does |
| --- | --- |
| `TileMap` | The map itself: draws OpenStreetMap's standard tiles natively, with the route, destination and car on top. Tiles are kept on disk for two weeks. |
| `Router` | Place search (Photon, run by Komoot) and driving routes (the Valhalla server run by FOSSGIS). |
| `Nav` | Follows the route by GPS: snaps the position to the route line, finds the next turn, notices when the car leaves the route. |
| `Hud` | Finds the HUD display and draws on it. |
| `MapWindow` | The floating window: search box, results, map, and the bar under it. |
| `MapService` | Foreground service tying these together. |
| `MainActivity` | Permissions and settings, including where the panel sits on the HUD. |
| `Updater` | Checks this repository's releases and installs a newer APK. |

**The HUD.** The 7X exposes its HUD to Android as a second display, 1440 x 480 (display 3 on the cars checked). The app puts an overlay window on it (the "display over other apps" kind), which Android stacks above everything else shown there. A HUD is a projection, so black is see-through: only the arrow, text and route line light up, and the car's own readouts still show around them. The setting **Blank the car's own HUD readouts** paints the whole HUD black behind the directions instead, which hides the car's speed readout too. If the car refuses an overlay window on that display, the app falls back to an ordinary second-screen window, and the status line on the settings screen says so. How to find and draw on the HUD was learned from [zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car) (dts88) and CAMCORDER (FerixzTanz); no code was copied from either (both are GPL-3.0).

**The services are free community ones**, meant for light personal use: [OpenStreetMap's tile servers](https://operations.osmfoundation.org/policies/tiles/), [Photon](https://photon.komoot.io) and the [FOSSGIS Valhalla server](https://valhalla.openstreetmap.de). The app identifies itself to each, fetches only the tiles on screen, and keeps them on disk. Map data © OpenStreetMap contributors.

## Build

JDK 17+ (Android Studio's bundled one works) and Android SDK platform 35.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up on the car

1. Install the APK and open **Car Maps**.
2. Tap **Allow display over other apps**, then **Allow location**, then **Allow location all the time** (needed for the map to come back by itself when the car starts).
3. Tap **Start the floating map**.
4. Under **Head-up display**, tap **Show a sample on the HUD** and use the sliders to place it. The status lines at the top list every display the car reports and which one looks like the HUD; pick one by hand under **HUD display** if the automatic choice is wrong.

**Updates**: the settings screen checks for a newer release when it opens, and **Check for updates** does it on demand. The first time, Android asks you to allow Car Maps to install apps.

## Tested and not tested

Version 2.0 (the OpenStreetMap map, search, the on-top HUD window and the updater) compiles, and the services it calls were checked to answer, but it has not been run on any device yet. The earlier Google-based version was run on a tablet with a simulated HUD display; the route following, HUD picture and floating window mechanics carried over from it.

Never tested: anything on the car itself (whether it gives the app a GPS position, the real HUD, starting with the car) or while driving (turn progress, rerouting, arrival, speech).
