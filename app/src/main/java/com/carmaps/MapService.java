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

import java.util.ArrayList;
import java.util.HashSet;
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
    /** Incidents are asked for again after this long, or sooner once the car has moved this far. */
    private static final long INCIDENT_GAP_MS = 10 * 60 * 1000;
    private static final double INCIDENT_MOVE_M = 12000;
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
    /** Every route on offer for the destination; {@link #route} is the chosen one. */
    private List<Nav.Route> routes = new ArrayList<>();
    private Nav nav;

    private List<Router.Incident> incidents = new ArrayList<>();
    private final HashSet<String> warned = new HashSet<>();
    private boolean incidentPending;
    private long incidentsAt;
    private double incidentLat, incidentLng;

    /** Map colours can depend on the time of day: look again every minute. */
    private final Runnable themeTick = new Runnable() {
        @Override
        public void run() {
            applyTheme();
            main.postDelayed(this, 60000);
        }
    };
    private boolean navigating;
    private int routeSeq;
    private boolean routePending;
    private long lastRouteRequest;
    private int offRouteCount;
    /** The usual way being followed (the rest of it, as of the last route), or null. */
    private UsualRoutes.Way usualWay;
    /** Where the car has been since guidance started, for learning the way it went. */
    private final List<double[]> driven = new ArrayList<>();
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
        Usage.init(this);
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
        main.postDelayed(themeTick, 60000);
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
        applyTheme();
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
        Usage.flush();
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
        applyTheme();   // the car may have just switched between its day and night looks
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
        hud.setCar(lat, lng, (float) bearing);
        if (navigating && nav != null) {
            if (gps && (!l.hasAccuracy() || l.getAccuracy() <= 40f) && driven.size() < 20000) {
                double[] prev = driven.isEmpty() ? null : driven.get(driven.size() - 1);
                if (prev == null || Geo.dist(prev[0], prev[1], lat, lng) >= 20) driven.add(new double[]{lat, lng});
            }
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
                learnWay();
                endNav();
                window.showMessage("You have arrived", name);
                return;
            }
            showProgress(p);
            speakFor(p, l.hasSpeed() ? l.getSpeed() : 0f);
            warnIncidents(p);
            if (!routePending && offRouteCount >= OFF_ROUTE_FIXES && now - lastRouteRequest > MIN_REROUTE_GAP_MS) {
                requestRoute(false);
            }
        }
        window.setCar(lat, lng, bearing);
        checkIncidents(l);
    }

    /** Dark or light map, by the setting: fixed, by the sun where the car is, or by the car's own theme. */
    private void applyTheme() {
        int mode = prefs.theme();
        boolean dark;
        if (mode == 0) {
            dark = false;
        } else if (mode == 1) {
            dark = true;
        } else if (mode == 3) {
            dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } else if (last != null) {
            dark = Geo.isNight(last.getLatitude(), last.getLongitude(), System.currentTimeMillis());
        } else {
            // No position yet to work the sun out from: go by the clock.
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            dark = hour < 6 || hour >= 19;
        }
        window.setDark(dark);
    }

    // ------------------------------------------------------------------ incidents

    /** Fetches TomTom's incidents around the car now and then, while the map is in use. */
    private void checkIncidents(Location l) {
        final String key = prefs.tomtomKey();
        if (key.isEmpty() || !prefs.incidents()) {
            if (!incidents.isEmpty()) {
                incidents = new ArrayList<>();
                window.setIncidents(incidents);
            }
            return;
        }
        if (incidentPending || (window.isCollapsed() && !navigating)) return;
        long now = SystemClock.elapsedRealtime();
        final double lat = l.getLatitude(), lng = l.getLongitude();
        if (incidentsAt != 0) {
            long age = now - incidentsAt;
            boolean moved = Geo.dist(incidentLat, incidentLng, lat, lng) > INCIDENT_MOVE_M;
            if (age < 60000 || (age < INCIDENT_GAP_MS && !moved)) return;
        }
        incidentPending = true;
        incidentsAt = now;
        incidentLat = lat;
        incidentLng = lng;
        io.execute(() -> {
            try {
                final List<Router.Incident> found = Router.incidents(lat, lng, key);
                main.post(() -> {
                    incidentPending = false;
                    incidents = found;
                    window.setIncidents(found);
                    matchIncidents();
                });
            } catch (Exception e) {
                Log.w(TAG, "Incidents failed: " + e.getMessage());
                main.post(() -> incidentPending = false);
            }
        });
    }

    /** Works out which incidents lie on the route being followed, and how far along it. */
    private void matchIncidents() {
        Nav.Route r = navigating ? route : null;
        for (Router.Incident inc : incidents) {
            inc.routeDist = -1;
            if (r == null || inc.line.length < 2) continue;
            double[] start = Nav.offsetOf(r, inc.line[0], inc.line[1]);
            boolean startOn = start[1] < 30;
            if (inc.line.length >= 4) {
                double[] end = Nav.offsetOf(r, inc.line[2], inc.line[3]);
                boolean endOn = end[1] < 30;
                // It runs the opposite way to the route: it is on the other side of the road.
                if (startOn && endOn && start[0] > end[0] + 20) continue;
                if (!startOn && endOn) inc.routeDist = end[0];
            }
            if (startOn) inc.routeDist = start[0];
        }
    }

    /** Says once, a couple of kilometres out, that there is something on the route ahead. */
    private void warnIncidents(Nav.Progress p) {
        if (p.offRoute) return;
        for (Router.Incident inc : incidents) {
            if (inc.routeDist < 0 || warned.contains(inc.id)) continue;
            double ahead = inc.routeDist - p.along;
            if (ahead > 150 && ahead < 3000) {
                if (warned.size() > 500) warned.clear();
                warned.add(inc.id);
                say(Router.incidentName(inc.category) + " ahead, in " + Geo.spokenDistance(ahead));
            }
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
        routes = new ArrayList<>();
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
        routes = new ArrayList<>();
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
        usualWay = route.usual;
        driven.clear();
        if (last != null) driven.add(new double[]{last.getLatitude(), last.getLongitude()});
        spokenStep = -1;
        spokenStage = 0;
        main.removeCallbacks(trafficRefresh);
        main.postDelayed(trafficRefresh, TRAFFIC_REFRESH_MS);
        window.showRoute(route, false);   // just the chosen route: the others on offer go
        window.setGuiding(true);
        hud.setRoute(route);
        matchIncidents();
        Location l = last;
        if (l != null) {
            showProgress(nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0f));
        }
        say("Starting route to " + dest.name + ". "
                + Geo.duration(route.durationSec).replace(" h ", " hours ").replace("min", "minutes") + ".");
    }

    @Override
    public void stopNav() {
        // Ended within sight of the destination: that's still the way to get there.
        Location l = last;
        if (l != null && dest != null && Geo.dist(l.getLatitude(), l.getLongitude(), dest.lat, dest.lng) < 300) {
            learnWay();
        }
        endNav();
        // Back to the overview of the same route, ready to start again.
        if (route != null && dest != null) {
            window.showRoutes(routes, Math.max(0, routes.indexOf(route)), true);
            offer();
        } else {
            window.hideBar();
        }
    }

    @Override
    public void tollsChanged() {
        final String now = prefs.avoidTolls() ? "Avoiding tolls" : "Tolls allowed";
        if (dest == null) {
            window.showMessage(now, "Used for the next route");
            main.postDelayed(() -> {
                if (dest == null) window.hideBar();
            }, 2500);
            return;
        }
        // A route is showing or being followed: work it out again with the new choice.
        if (!navigating) window.showMessage("Finding a route…", now + "  ·  " + dest.name);
        requestRoute(false);
    }

    @Override
    public void chooseRoute(int index) {
        if (navigating || index < 0 || index >= routes.size()) return;
        route = routes.get(index);
        window.showRoutes(routes, index, false);
        offer();
    }

    private void endNav() {
        window.setSpeed(0, false);
        window.setLanes(null);
        routeSeq++;
        routePending = false;
        navigating = false;
        nav = null;
        usualWay = null;
        driven.clear();
        main.removeCallbacks(trafficRefresh);
        window.setGuiding(false);
        hud.setNav(null);
        hud.setRoute(null);
    }

    private void offer() {
        String traffic = !route.traffic ? (prefs.tomtomKey().isEmpty() ? "" : "  ·  no traffic data")
                : route.trafficDelaySec >= 60 ? "  ·  +" + Geo.duration(route.trafficDelaySec) + " traffic"
                : "  ·  live traffic";
        String usual = "";
        if (route.usual != null) {
            usual = "  ·  your usual way";
        } else {
            for (Nav.Route r : routes) {
                if (r.usual == null) continue;
                double slower = r.durationSec - route.durationSec;
                if (slower >= 60) usual = "  ·  usual way +" + Geo.duration(slower) + " today";
            }
        }
        window.showOffer(dest.name, Geo.duration(route.durationSec) + "  ·  " + Geo.distance(route.totalDist)
                + "  ·  arrive " + Geo.clock(System.currentTimeMillis() + (long) (route.durationSec * 1000))
                + traffic + (route.hasTolls ? "  ·  tolls" : "") + usual);
    }

    /** Files the way the car went on the journey just finished. */
    private void learnWay() {
        if (!prefs.usualRoutes() || dest == null || driven.size() < 2) return;
        final List<double[]> path = new ArrayList<>(driven);
        final Router.Dest to = dest;
        io.execute(() -> UsualRoutes.get(this).remember(path, to.lat, to.lng, to.name));
    }

    /**
     * Puts the route that follows the usual way first and drops any other that is really the same way.
     *
     * @return the index to choose: the usual way, unless it's a good deal slower today than the best
     */
    private static int withUsual(List<Nav.Route> found, Nav.Route usual) {
        if (usual == null) return 0;
        Nav.Route best = found.isEmpty() ? null : found.get(0);
        for (int i = found.size() - 1; i >= 0; i--) {
            Nav.Route r = found.get(i);
            double[][] a = thinned(r), b = thinned(usual);
            if (UsualRoutes.overlap(a[0], a[1], usual.lat, usual.lng) > 0.9
                    && UsualRoutes.overlap(b[0], b[1], r.lat, r.lng) > 0.9) {
                found.remove(i);
            }
        }
        found.add(0, usual);
        if (best == null || best == usual || !found.contains(best)) return 0;
        double margin = Math.max(300, best.durationSec * 0.2);
        return usual.durationSec > best.durationSec + margin ? found.indexOf(best) : 0;
    }

    /** About one point every 100 m of a route's line, enough to compare two routes. */
    private static double[][] thinned(Nav.Route r) {
        List<double[]> pts = new ArrayList<>();
        double lastAt = -1e9;
        for (int i = 0; i < r.lat.length; i++) {
            if (r.cum[i] - lastAt >= 100 || i == r.lat.length - 1) {
                pts.add(new double[]{r.lat[i], r.lng[i]});
                lastAt = r.cum[i];
            }
        }
        double[][] out = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = pts.get(i)[0];
            out[1][i] = pts.get(i)[1];
        }
        return out;
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
        // Before setting off, ask for a couple of other routes to choose between.
        final int others = !navigating && !refresh ? 2 : 0;
        // The way the driver usually goes: looked up before setting off; while driving, the rest of the
        // one being followed, as long as the car is still on it.
        UsualRoutes.Way way = null;
        if (prefs.usualRoutes()) {
            if (!navigating) {
                way = UsualRoutes.get(this).find(from.getLatitude(), from.getLongitude(), to.lat, to.lng);
            } else if (usualWay != null) {
                way = UsualRoutes.rest(usualWay, from.getLatitude(), from.getLongitude());
                if (way == null) usualWay = null;   // gone another way: the router's choice from here
            }
        }
        final UsualRoutes.Way along = way;
        final boolean driving = navigating;
        routePending = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        io.execute(() -> {
            try {
                Nav.Route usual = null;
                if (along != null) {
                    try {
                        usual = Router.find(from, to, tolls, highways, key, 0, along).get(0);
                        usual.usual = along;
                        // Much longer than the way itself means the router looped round to meet a point.
                        double wayLength = UsualRoutes.length(along.lat, along.lng);
                        if (usual.totalDist > wayLength * 1.25 + 500) {
                            Log.w(TAG, "Usual route came back too long: " + usual.totalDist + " m for " + wayLength);
                            usual = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Usual route failed", e);
                    }
                }
                final List<Nav.Route> found = new ArrayList<>();
                // While driving, the usual way is the only route wanted, if there is one.
                if (usual == null || !driving) found.addAll(Router.find(from, to, tolls, highways, key, others));
                final int chosen = withUsual(found, usual);
                main.post(() -> {
                    if (seq == routeSeq) onRoute(found, refresh, chosen);
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

    private void onRoute(List<Nav.Route> all, boolean refresh, int chosen) {
        Nav.Route found = all.get(navigating ? 0 : chosen);
        routePending = false;
        // A traffic re-check that had to fall back to the router without traffic tells us nothing new,
        // and one that arrives after the car has left the route is already out of date.
        if (refresh && navigating && (!found.traffic || offRouteCount > 0)) return;
        route = found;
        if (!navigating) {
            routes = all;
            window.showRoutes(all, chosen, true);
            offer();
            return;
        }
        if (found.usual != null) usualWay = found.usual;
        routes = new ArrayList<>();
        routes.add(found);
        // A new route while driving: carry straight on with it.
        nav = new Nav(found);
        offRouteCount = 0;
        int stage = spokenStage;
        spokenStep = -1;
        spokenStage = 0;
        window.showRoute(found, false);
        hud.setRoute(found);
        matchIncidents();
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
        Location l = last;
        float kmh = l != null && l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
        p.over = p.speedLimit > 0 && kmh > p.speedLimit + 3;
        window.setSpeed(p.offRoute ? 0 : p.speedLimit, p.over);
        window.setLanes(p.offRoute ? null : p.lanes);
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
