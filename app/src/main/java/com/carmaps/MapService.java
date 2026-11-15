package com.carmaps;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.WindowManager;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the floating map on screen, follows the car by GPS, finds places and routes from
 * OpenStreetMap data ({@link Router}), and gives turn-by-turn guidance on the map window, on the
 * HUD and by voice.
 */
public class MapService extends Service implements MapWindow.Host, LocationListener {

    private static final String TAG = "MapService";

    static final String ACTION_START = "com.carmaps.action.START";
    static final String ACTION_STOP = "com.carmaps.action.STOP";
    static final String ACTION_REFRESH = "com.carmaps.action.REFRESH";
    static final String ACTION_RESET_POSITION = "com.carmaps.action.RESET_POSITION";
    static final String ACTION_HUD_TEST = "com.carmaps.action.HUD_TEST";

    private static final String CHANNEL = "map";
    private static final int NOTIFICATION_ID = 7;

    private static final long MIN_REROUTE_GAP_MS = 8000;
    private static final int OFF_ROUTE_FIXES = 3;
    private static final double ARRIVE_M = 35;
    /** While following a TomTom route, ask again this often so the route and arrival time keep up with traffic. */
    private static final long TRAFFIC_REFRESH_MS = 3 * 60 * 1000;

    static volatile boolean running;
    /** For the settings screen. */
    static volatile String gpsStatus = "Not started";

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, MapService.class).setAction(ACTION_START));
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't start", e);
        }
    }

    static void send(Context c, String action) {
        if (!running) return;
        try {
            c.startService(new Intent(c, MapService.class).setAction(action));
        } catch (RuntimeException e) {
            Log.w(TAG, "send " + action + " failed", e);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private Prefs prefs;
    private WindowManager wm;
    private MapWindow window;
    private Hud hud;
    private Speaker speaker;
    private LocationManager locations;
    private boolean listening;

    private Location last;
    private long lastGpsAt;
    private double bearing;

    /** Where the user wants to go, the route there once it is known, and whether it is being followed. */
    private Router.Dest dest;
    private Nav.Route route;
    private Nav nav;
    private boolean navigating;
    private int routeSeq;
    private boolean routePending;
    private long lastRouteRequest;
    private int offRouteCount;
    /** Which spoken prompts have been given for the coming turn: 0 none, 1 the early one, 2 the final one. */
    private int spokenStage;
    private int spokenStep = -1;
    private String spokenText = "";

    private final Runnable trafficRefresh = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, TRAFFIC_REFRESH_MS);
            if (navigating && route != null && route.traffic && !routePending && offRouteCount == 0) {
                requestRoute(true);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        Context windowContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            windowContext = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        Context ui = new ContextThemeWrapper(windowContext, R.style.AppTheme);
        wm = windowContext.getSystemService(WindowManager.class);
        window = new MapWindow(ui, wm, prefs, this);
        hud = new Hud(this, prefs);
        locations = getSystemService(LocationManager.class);
        goForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No permission to draw over other apps");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            prefs.setEnabled(true);
            int[] size = screenSize();
            window.open(size[0], size[1]);
        }
        if (ACTION_RESET_POSITION.equals(action)) window.resetPosition();
        if (ACTION_HUD_TEST.equals(action)) hud.test();
        // Start and refresh both pick up whatever changed: permissions and settings.
        startLocation();
        window.refresh();
        hud.refresh();
        if (prefs.voice() && speaker == null) speaker = new Speaker(this);
        if (!prefs.voice() && speaker != null) {
            speaker.release();
            speaker = null;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        gpsStatus = "Not started";
        Hud.status = "Not started";
        main.removeCallbacksAndMessages(null);
        if (listening) locations.removeUpdates(this);
        io.shutdownNow();
        window.destroy();
        hud.release();
        if (speaker != null) speaker.release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int[] size = screenSize();
        window.onScreenChanged(size[0], size[1]);
    }

    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating map",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nav)
                .setContentTitle("Car Maps is running")
                .setContentText("Tap to open settings")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        int type = 0;
        if (hasLocationPermission()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        try {
            startForeground(NOTIFICATION_ID, n, type);
        } catch (RuntimeException e) {
            // Started in the background without "allow all the time": run without the location type.
            Log.w(TAG, "Foreground with location refused", e);
            try {
                startForeground(NOTIFICATION_ID, n,
                        Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
            } catch (RuntimeException e2) {
                Log.e(TAG, "Couldn't become a foreground service", e2);
            }
        }
    }

    // ------------------------------------------------------------------ position

    private void startLocation() {
        if (listening) return;
        if (!hasLocationPermission()) {
            gpsStatus = "Location permission not granted";
            return;
        }
        StringBuilder used = new StringBuilder();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                if (!locations.getAllProviders().contains(provider)) continue;
                locations.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper());
                used.append(used.length() > 0 ? ", " : "").append(provider);
                Location known = locations.getLastKnownLocation(provider);
                if (known != null && (last == null || known.getTime() > last.getTime())) last = known;
            } catch (RuntimeException e) {
                Log.w(TAG, "Provider " + provider + " unavailable", e);
            }
        }
        listening = used.length() > 0;
        gpsStatus = listening ? "Waiting for a position (" + used + ")" : "This device offers no location source";
        if (last != null) {
            if (last.hasBearing()) bearing = last.getBearing();
            window.setCar(last.getLatitude(), last.getLongitude(), bearing);
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        long now = SystemClock.elapsedRealtime();
        // Once the satellites are giving positions, ignore the rougher network ones.
        if (gps) lastGpsAt = now;
        else if (lastGpsAt != 0 && now - lastGpsAt < 10000) return;
        last = l;
        gpsStatus = String.format(Locale.US, "%s fix, accurate to %.0f m", l.getProvider(), l.getAccuracy());
        boolean moving = l.hasSpeed() && l.getSpeed() > 1.5f;
        if (l.hasBearing() && moving) bearing = l.getBearing();
        hud.setBearing(bearing);

        double lat = l.getLatitude(), lng = l.getLongitude();
        if (navigating && nav != null) {
            Nav.Progress p = nav.update(lat, lng, l.hasAccuracy() ? l.getAccuracy() : 0f);
            if (p.offRoute) {
                offRouteCount++;
            } else {
                // On the route: show the car on the road, pointing along it.
                offRouteCount = 0;
                lat = p.snapLat;
                lng = p.snapLng;
                if (moving || !l.hasSpeed()) bearing = p.snapBearing;
            }
            if (!p.offRoute && p.remainDist < ARRIVE_M) {
                window.setCar(lat, lng, bearing);
                say("You have arrived");
                String name = dest == null ? "" : dest.name;
                endNav();
                window.showMessage("You have arrived", name);
                return;
            }
            showProgress(p);
            speakFor(p, l.hasSpeed() ? l.getSpeed() : 0f);
            if (!routePending && offRouteCount >= OFF_ROUTE_FIXES && now - lastRouteRequest > MIN_REROUTE_GAP_MS) {
                requestRoute(false);
            }
        }
        window.setCar(lat, lng, bearing);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // needed on older Android versions, where this method has no default
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    // ------------------------------------------------------------------ search and destination

    @Override
    public void search(String text, int id) {
        final Location near = last;
        final String key = prefs.tomtomKey();
        io.execute(() -> {
            try {
                List<Router.Dest> found = Router.search(text, near, key);
                main.post(() -> window.showResults(id, found, null));
            } catch (Exception e) {
                Log.w(TAG, "Search failed", e);
                main.post(() -> window.showResults(id, null, friendly(e)));
            }
        });
    }

    @Override
    public List<Router.Dest> recents() {
        return prefs.recents();
    }

    @Override
    public void pick(Router.Dest place) {
        if (navigating) endNav();
        dest = place;
        route = null;
        if (!"Dropped pin".equals(place.name)) prefs.addRecent(place);
        window.showPlace(place);
        window.showMessage("Finding a route…", place.name);
        requestRoute(false);
    }

    @Override
    public void clearDestination() {
        if (navigating) return;
        routeSeq++;
        routePending = false;
        dest = null;
        route = null;
        window.clearRoute();
        window.hideBar();
    }

    @Override
    public void startNav() {
        if (route == null || dest == null) {
            // "Start" on a failed request: try for the route again.
            if (dest != null && !routePending) {
                window.showMessage("Finding a route…", dest.name);
                requestRoute(false);
            }
            return;
        }
        nav = new Nav(route);
        navigating = true;
        offRouteCount = 0;
        spokenStep = -1;
        spokenStage = 0;
        main.removeCallbacks(trafficRefresh);
        main.postDelayed(trafficRefresh, TRAFFIC_REFRESH_MS);
        window.setGuiding(true);
        hud.setRoute(route);
        Location l = last;
        if (l != null) {
            showProgress(nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0f));
        }
        say("Starting route to " + dest.name + ". "
                + Geo.duration(route.durationSec).replace(" h ", " hours ").replace("min", "minutes") + ".");
    }

    @Override
    public void stopNav() {
        endNav();
        // Back to the overview of the same route, ready to start again.
        if (route != null && dest != null) {
            window.showRoute(route, true);
            offer();
        } else {
            window.hideBar();
        }
    }

    private void endNav() {
        routeSeq++;
        routePending = false;
        navigating = false;
        nav = null;
        main.removeCallbacks(trafficRefresh);
        window.setGuiding(false);
        hud.setNav(null);
        hud.setRoute(null);
    }

    private void offer() {
        String traffic = !route.traffic ? (prefs.tomtomKey().isEmpty() ? "" : "  ·  no traffic data")
                : route.trafficDelaySec >= 60 ? "  ·  +" + Geo.duration(route.trafficDelaySec) + " traffic"
                : "  ·  live traffic";
        window.showOffer(dest.name, Geo.duration(route.durationSec) + "  ·  " + Geo.distance(route.totalDist)
                + "  ·  arrive " + Geo.clock(System.currentTimeMillis() + (long) (route.durationSec * 1000))
                + traffic);
    }

    // ------------------------------------------------------------------ routes

    /** @param refresh a routine re-check for traffic while driving, rather than a new or changed journey */
    private void requestRoute(boolean refresh) {
        final Router.Dest to = dest;
        final Location from = last;
        if (to == null) return;
        if (from == null) {
            window.showOffer("Waiting for GPS", hasLocationPermission()
                    ? "Tap Start once the car has a position." : "Allow location in the Car Maps app first.");
            return;
        }
        final int seq = ++routeSeq;
        final boolean tolls = prefs.avoidTolls(), highways = prefs.avoidHighways();
        final String key = prefs.tomtomKey();
        routePending = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        io.execute(() -> {
            try {
                Nav.Route found = Router.find(from, to, tolls, highways, key);
                main.post(() -> {
                    if (seq == routeSeq) onRoute(found, refresh);
                });
            } catch (Exception e) {
                Log.w(TAG, "Route failed", e);
                main.post(() -> {
                    if (seq != routeSeq) return;
                    routePending = false;
                    // While driving, keep following the route we have; only a first request reports the failure.
                    if (!navigating) window.showOffer("Couldn't get a route", friendly(e) + " Tap Start to try again.");
                });
            }
        });
    }

    private void onRoute(Nav.Route found, boolean refresh) {
        routePending = false;
        // A traffic re-check that had to fall back to the router without traffic tells us nothing new,
        // and one that arrives after the car has left the route is already out of date.
        if (refresh && navigating && (!found.traffic || offRouteCount > 0)) return;
        route = found;
        if (!navigating) {
            window.showRoute(found, true);
            offer();
            return;
        }
        // A new route while driving: carry straight on with it.
        nav = new Nav(found);
        offRouteCount = 0;
        int stage = spokenStage;
        spokenStep = -1;
        spokenStage = 0;
        window.showRoute(found, false);
        hud.setRoute(found);
        Location l = last;
        if (l != null) {
            Nav.Progress p = nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0f);
            // The same turn is still coming up on the re-checked route: don't announce it a second time.
            if (refresh && !p.spoken.isEmpty() && p.spoken.equals(spokenText)) {
                spokenStep = p.stepIndex + 1;
                spokenStage = stage;
            }
            showProgress(p);
        }
    }

    private void showProgress(Nav.Progress p) {
        hud.setNav(p);
        if (p.offRoute) {
            window.showTurn(null, "Rerouting…", "Finding a new route");
            return;
        }
        String title = Geo.distance(p.distToManeuver) + "  ·  " + p.text;
        String sub = Geo.clock(p.eta) + " arrival  ·  " + Geo.duration(p.remainSec) + "  ·  " + Geo.distance(p.remainDist);
        window.showTurn(Maneuvers.of(p.maneuver), title, sub);
    }

    private static String friendly(Exception e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return "No internet connection.";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Something went wrong." : m;
    }

    // ------------------------------------------------------------------ speech

    private void say(String text) {
        if (speaker != null && prefs.voice()) speaker.say(text);
    }

    /** An early prompt well before each turn, and a final one just before it. */
    private void speakFor(Nav.Progress p, float speed) {
        if (p.offRoute || p.spoken.isEmpty()) return;
        int turn = p.stepIndex + 1;
        if (turn != spokenStep) {
            spokenStep = turn;
            spokenStage = 0;
        }
        spokenText = p.spoken;
        double finalAt = Math.max(60, speed * 6);          // about six seconds out
        double earlyAt = Math.max(350, speed * 22);        // about twenty seconds out
        if (spokenStage < 2 && p.distToManeuver <= finalAt) {
            spokenStage = 2;
            say(p.spoken);
        } else if (spokenStage < 1 && p.distToManeuver <= earlyAt && p.distToManeuver > finalAt * 1.8) {
            spokenStage = 1;
            say("In " + Geo.spokenDistance(p.distToManeuver) + ", " + lowerFirst(p.spoken));
        }
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
