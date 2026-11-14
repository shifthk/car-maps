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

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the floating Google Maps window on screen. When the page shows directions to somewhere, it
 * offers turn-by-turn guidance to the same place: a route from OpenStreetMap data ({@link Router}),
 * followed by GPS, shown on the HUD, in the bar under the map, and spoken.
 */
public class MapService extends Service implements MapWindow.Host, LocationListener {

    private static final String TAG = "MapService";

    static final String ACTION_START = "com.carmaps.action.START";
    static final String ACTION_STOP = "com.carmaps.action.STOP";
    static final String ACTION_REFRESH = "com.carmaps.action.REFRESH";
    static final String ACTION_RESET_POSITION = "com.carmaps.action.RESET_POSITION";
    static final String ACTION_HOME = "com.carmaps.action.HOME";
    static final String ACTION_HUD_TEST = "com.carmaps.action.HUD_TEST";

    private static final String CHANNEL = "map";
    private static final int NOTIFICATION_ID = 7;

    private static final long MIN_REROUTE_GAP_MS = 8000;
    private static final int OFF_ROUTE_FIXES = 3;
    private static final double ARRIVE_M = 35;
    /** How many times to look at the page again for a destination's coordinates before searching by name. */
    private static final int PAGE_TRIES = 4;

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

    /** What the page is showing directions to, and what guidance is heading for. */
    private String offeredName = "";
    private int offerTries;
    private Router.Dest offered;
    private Router.Dest dest;
    private Nav nav;
    private boolean navigating;
    private int routeSeq;
    private boolean routePending;
    /** The request the page has been asked to answer with Google's route, or 0. */
    private int googleSeq;
    private static final long GOOGLE_WAIT_MS = 12000;
    private long lastRouteRequest;
    private int offRouteCount;
    /** Which spoken prompts have been given for the coming turn: 0 none, 1 the early one, 2 the final one. */
    private int spokenStage;
    private int spokenStep = -1;

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
        if (ACTION_HOME.equals(action)) window.goHome();
        if (ACTION_HUD_TEST.equals(action)) hud.test();
        // Start and refresh both pick up whatever changed: permissions and settings.
        startLocation();
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

    @Override
    public boolean hasLocationPermission() {
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
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) bearing = l.getBearing();
        hud.setBearing(bearing);
        if (!navigating || nav == null) return;

        Nav.Progress p = nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0f);
        if (p.offRoute) offRouteCount++;
        else offRouteCount = 0;
        if (!p.offRoute && p.remainDist < ARRIVE_M) {
            say("You have arrived");
            endNav();
            window.showMessage("You have arrived", dest == null ? "" : dest.shortName());
            dest = null;
            return;
        }
        showProgress(p);
        speakFor(p, l.hasSpeed() ? l.getSpeed() : 0f);
        if (!routePending && offRouteCount >= OFF_ROUTE_FIXES && now - lastRouteRequest > MIN_REROUTE_GAP_MS) {
            requestRoute();
        }
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

    // ------------------------------------------------------------------ what the page is showing

    @Override
    public void onDestination(String name, double lat, double lng) {
        if (navigating) return;   // already guiding: browsing the map doesn't change that
        boolean known = !Double.isNaN(lat) && !Double.isNaN(lng);
        if (!name.equals(offeredName)) {
            offeredName = name;
            offerTries = 0;
            offered = null;
        }
        if (known) {
            offered = new Router.Dest(name, lat, lng);
            window.showOffer("Guide on HUD", offered.shortName());
            return;
        }
        if (offered != null) return;
        if (++offerTries < PAGE_TRIES) {
            // The page hasn't fetched its directions yet: look again shortly.
            window.retryDestination();
            return;
        }
        if (offerTries > PAGE_TRIES) return;   // already searching by name, or gave up
        // The page never gave coordinates: find the place by name instead.
        final String wanted = name;
        io.execute(() -> {
            double[] found = null;
            try {
                found = Router.geocode(wanted);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't look up " + wanted, e);
            }
            final double[] at = found;
            main.post(() -> {
                if (navigating || !wanted.equals(offeredName) || offered != null) return;
                if (at == null) return;   // nothing to offer; the Google page still works on its own
                offered = new Router.Dest(wanted, at[0], at[1]);
                window.showOffer("Guide on HUD", offered.shortName() + " (approximate location)");
            });
        });
    }

    @Override
    public void onNoDestination() {
        offeredName = "";
        offered = null;
        offerTries = 0;
        if (!navigating && !routePending) window.hideBar();
    }

    @Override
    public void startNav() {
        if (offered == null) return;
        dest = offered;
        nav = null;
        window.showMessage("Finding a route…", dest.shortName());
        requestRoute();
    }

    @Override
    public void stopNav() {
        endNav();
        dest = null;
        if (offered != null) window.showOffer("Guide on HUD", offered.shortName());
        else window.hideBar();
    }

    private void endNav() {
        routeSeq++;
        googleSeq = 0;
        routePending = false;
        navigating = false;
        nav = null;
        hud.setNav(null);
        hud.setRoute(null);
    }

    // ------------------------------------------------------------------ routes

    private void requestRoute() {
        final Router.Dest to = dest;
        final Location from = last;
        if (to == null) return;
        if (from == null) {
            dest = null;
            window.showOffer("Waiting for GPS", hasLocationPermission()
                    ? "Try again once the car has a position." : "Allow location in the Car Maps app first.");
            return;
        }
        final int seq = ++routeSeq;
        routePending = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        // First choice: the route Google itself draws, from where the car is now. When the car has
        // left the route, Google's old route is no use, so that request must start from here.
        googleSeq = seq;
        window.requestGoogleRoute(from.getLatitude(), from.getLongitude(), navigating);
        main.postDelayed(() -> {
            if (googleSeq == seq) {
                Log.w(TAG, "The page didn't answer with Google's route");
                googleSeq = 0;
                if (seq == routeSeq) requestOwnRoute(seq, from, to);
            }
        }, GOOGLE_WAIT_MS);
    }

    @Override
    public void onGoogleRoute(String line, double seconds, double metres) {
        final int seq = googleSeq;
        final Router.Dest to = dest;
        final Location from = last;
        if (seq == 0 || seq != routeSeq || to == null || from == null) return;
        googleSeq = 0;
        if (line.isEmpty()) {
            Log.w(TAG, "The page couldn't supply Google's route");
            requestOwnRoute(seq, from, to);
            return;
        }
        io.execute(() -> {
            Nav.Route route = null;
            try {
                org.json.JSONObject trip = Router.trace(line, metres);
                if (trip != null) {
                    route = Nav.Route.parse(trip);
                    route.google = true;
                    route.scaleDuration(seconds);
                } else {
                    Log.w(TAG, "Google's route couldn't be matched to OpenStreetMap's roads");
                }
            } catch (Exception e) {
                Log.w(TAG, "Matching Google's route failed", e);
            }
            final Nav.Route matched = route;
            main.post(() -> {
                if (seq != routeSeq) return;
                if (matched != null) onRoute(matched);
                else requestOwnRoute(seq, from, to);
            });
        });
    }

    /** The fallback: OpenStreetMap picks the roads itself. */
    private void requestOwnRoute(int seq, Location from, Router.Dest to) {
        final boolean tolls = prefs.avoidTolls(), highways = prefs.avoidHighways();
        io.execute(() -> {
            try {
                Nav.Route route = Nav.Route.parse(Router.route(from, to, tolls, highways));
                main.post(() -> {
                    if (seq == routeSeq) onRoute(route);
                });
            } catch (Exception e) {
                Log.w(TAG, "Route failed", e);
                main.post(() -> {
                    if (seq != routeSeq) return;
                    routePending = false;
                    // While driving, keep following the route we have; only a first request reports the failure.
                    if (!navigating) {
                        dest = null;
                        window.showOffer("Couldn't get a route", friendly(e));
                    }
                });
            }
        });
    }

    private void onRoute(Nav.Route route) {
        routePending = false;
        boolean first = !navigating;
        nav = new Nav(route);
        navigating = true;
        offRouteCount = 0;
        spokenStep = -1;
        spokenStage = 0;
        hud.setRoute(route);
        Location l = last;
        if (l != null) {
            showProgress(nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0f));
        }
        if (first) {
            say("Starting route to " + dest.shortName() + ". "
                    + Geo.duration(route.durationSec).replace(" h ", " hours ").replace("min", "minutes") + ".");
        }
    }

    private void showProgress(Nav.Progress p) {
        hud.setNav(p);
        if (p.offRoute) {
            window.showTurn(null, "Rerouting…", "Finding a new route");
            return;
        }
        String title = Geo.distance(p.distToManeuver) + "  ·  " + p.text;
        String sub = Geo.clock(p.eta) + " arrival  ·  " + Geo.duration(p.remainSec) + "  ·  " + Geo.distance(p.remainDist)
                + (nav != null && nav.route().google ? "  ·  Google's route" : "  ·  own route");
        window.showTurn(Maneuvers.of(p.maneuver), title, sub);
    }

    private static String friendly(Exception e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return "No internet connection";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Something went wrong" : m;
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
