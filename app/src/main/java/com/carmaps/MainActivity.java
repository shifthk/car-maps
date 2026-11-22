package com.carmaps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Setup and settings, as tabs: down the left on a wide screen like the car's, across the top on a
 * narrow one. Each tab is a column of cards, one per group of settings. The map itself lives in the
 * floating window ({@link MapService}).
 */
public class MainActivity extends Activity {

    private interface IntSetter {
        void set(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    // Colours
    private static final int BG = 0xFF0F1215, RAIL = 0xFF161A1E, CARD = 0xFF1B2026, LINE = 0xFF2A3038;
    private static final int TEXT = 0xFFF2F4F6, DIM = 0xFF9AA4AE, FAINT = 0xFF6C7681;
    private static final int ACCENT = 0xFF4C9BFF, PRIMARY = 0xFF1A73E8, SECONDARY = 0xFF2B323A;
    private static final int GOOD = 0xFF34C77B, WARN = 0xFFF2B33D, OFF = 0xFF5A6470;

    private static final String[] TABS = {"Overview", "Map and directions", "Traffic (TomTom)", "Head-up display",
            "Spotify", "About"};

    private Prefs prefs;
    private Updater updater;
    /** The card that helpers add rows to while the pages are being built. */
    private LinearLayout root;
    /** The page being built. */
    private LinearLayout page;
    private final List<View> pages = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private ScrollView scroll;
    /** Things on the pages that show live state, updated every second and a half. */
    private final List<Runnable> live = new ArrayList<>();
    /** The HUD panel editor, redrawn when other HUD settings change (the circle, the mini map switch). */
    private HudLayoutView hudLayout;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        Usage.init(this);
        getWindow().getDecorView().setBackgroundColor(BG);

        buildOverview();
        buildMap();
        buildTraffic();
        buildHud();
        buildSpotify();
        buildAbout();

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        shell.setFitsSystemWindows(true);
        shell.setBackgroundColor(BG);

        // ---- the tabs
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(RAIL);
        bar.setPadding(dp(12), dp(wide ? 24 : 8), dp(12), dp(wide ? 24 : 8));
        if (wide) {
            TextView name = text("Car Maps", 24, true);
            name.setPadding(dp(12), 0, dp(12), dp(2));
            bar.addView(name);
            TextView ver = text("Version " + Updater.currentVersion(this), 13, false);
            ver.setTextColor(FAINT);
            ver.setPadding(dp(12), 0, dp(12), dp(20));
            bar.addView(ver);
        }
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = text(TABS[i], 17, false);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setMinHeight(dp(56));
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setSingleLine(true);
            t.setOnClickListener(v -> showTab(index));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    wide ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMargins(0, dp(2), wide ? 0 : dp(4), dp(2));
            bar.addView(t, tlp);
            tabs.add(t);
        }
        if (wide) {
            shell.addView(bar, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setBackgroundColor(RAIL);
            hs.addView(bar);
            shell.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---- the page, centred and no wider than reads well
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout holder = new FrameLayout(this);
        int pad = dp(wide ? 32 : 16);
        holder.setPadding(pad, dp(wide ? 28 : 16), pad, dp(32));
        for (View p : pages) {
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL);
            holder.addView(p, plp);
        }
        LinearLayout centre = new LinearLayout(this);
        centre.setGravity(Gravity.CENTER_HORIZONTAL);
        centre.addView(holder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        holder.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(centre);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1f));
        setContentView(shell);
        // Keep the text a comfortable width on very wide screens.
        holder.post(() -> {
            int max = dp(820);
            if (holder.getWidth() > max + 2 * pad) {
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(max + 2 * pad,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                holder.setLayoutParams(hlp);
            }
        });

        showTab(Math.max(0, Math.min(TABS.length - 1, prefs.settingsTab())));
        updater.autoCheck();
    }

    private void showTab(int index) {
        prefs.setSettingsTab(index);
        for (int i = 0; i < pages.size(); i++) {
            boolean on = i == index;
            pages.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
            TextView t = tabs.get(i);
            t.setTextColor(on ? TEXT : DIM);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(14));
            pill.setColor(on ? 0xFF243A57 : 0x00000000);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill, null));
        }
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    // ------------------------------------------------------------------ the pages

    private void buildOverview() {
        newPage("Overview");

        // The floating map: what it's doing and the one button that matters.
        section(null);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(20), dp(18), dp(20), dp(6));
        View dot = dot();
        hero.addView(dot);
        TextView heroText = text("", 20, true);
        heroText.setPadding(dp(12), 0, 0, 0);
        hero.addView(heroText);
        root.addView(hero);
        TextView heroNote = note("Search for a place, or hold a finger on the map to drop a pin, and it works out "
                + "a driving route. Tap Start for turn-by-turn guidance on the map, on the HUD and by voice.");
        heroNote.setPadding(dp(20), 0, dp(20), dp(4));
        root.addView(heroNote);
        TextView startStop = button("", true, v -> {
            if (MapService.running) {
                startService(new Intent(this, MapService.class).setAction(MapService.ACTION_STOP));
            } else if (!Settings.canDrawOverlays(this)) {
                toast("Allow display over other apps first");
            } else {
                MapService.start(this);
            }
            handler.postDelayed(this::refreshStatus, 400);
        });
        padded(startStop);
        live.add(() -> {
            boolean on = MapService.running;
            heroText.setText(on ? "The floating map is running" : "The floating map is stopped");
            tint(dot, on ? GOOD : OFF);
            startStop.setText(on ? "Stop the floating map" : "Start the floating map");
        });

        // Permissions still needed, only while some are.
        LinearLayout perms = section("Needs your permission");
        View permsTitle = page.getChildAt(page.getChildCount() - 2);
        TextView overlayButton = button("Allow display over other apps", true, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                toast("This screen has no such setting. Grant it with: adb shell appops set com.carmaps SYSTEM_ALERT_WINDOW allow");
            }
        });
        padded(overlayButton);
        TextView locationButton = button("Allow location", true, v -> requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS}, 1));
        padded(locationButton);
        TextView backgroundButton = button("Allow location all the time (needed to start with the car)", true, v -> {
            if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
            }
        });
        padded(backgroundButton);
        live.add(() -> {
            boolean overlay = Settings.canDrawOverlays(this);
            boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean background = Build.VERSION.SDK_INT < 29 || checkSelfPermission(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
            ((View) overlayButton.getParent()).setVisibility(overlay ? View.GONE : View.VISIBLE);
            ((View) locationButton.getParent()).setVisibility(location ? View.GONE : View.VISIBLE);
            ((View) backgroundButton.getParent()).setVisibility(location && !background ? View.VISIBLE : View.GONE);
            boolean any = !overlay || !location || !background;
            perms.setVisibility(any ? View.VISIBLE : View.GONE);
            permsTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        });

        // How each part is doing.
        section("Status");
        statusRow("GPS", () -> MapService.gpsStatus, s -> s.contains("accurate") ? GOOD : WARN);
        statusRow("TomTom", this::trafficStatus, s -> prefs.tomtomKey().isEmpty() ? OFF
                : s.contains("didn't") || s.contains("couldn't") || s.contains("used up") ? WARN : GOOD);
        statusRow("HUD", () -> Hud.status, s -> s.startsWith("Showing") ? GOOD
                : s.equals("Off") || s.equals("Not started") ? OFF : WARN);
        statusRow("Spotify", this::spotifyStatus, s -> s.equals("not set up") || s.equals("Signed out") ? OFF
                : s.contains("signed in") || s.contains("Playing") || s.contains("playing") ? GOOD : WARN);
    }

    private void buildMap() {
        newPage("Map and directions");

        section("Map");
        choice("Map colours", new String[]{"Light", "Dark", "Dark from sunset to sunrise",
                "Follow the car's day and night theme"}, prefs.theme(), prefs::setTheme);
        toggle("Turn the map to face the way ahead", "While following the car", prefs.headingUp(),
                prefs::setHeadingUp);

        section("Directions");
        toggle("Spoken directions", null, prefs.voice(), prefs::setVoice);
        toggle("Avoid tolls", null, prefs.avoidTolls(), prefs::setAvoidTolls);
        toggle("Avoid highways", null, prefs.avoidHighways(), prefs::setAvoidHighways);

        section("Your usual routes");
        toggle("Learn and use my usual routes", "Each route you follow to the end is remembered. Next time you go "
                + "there from along the way, it's offered first.", prefs.usualRoutes(), prefs::setUsualRoutes);
        statusRow("Learned", () -> {
            int n = UsualRoutes.get(this).size();
            return n == 0 ? "None yet" : n == 1 ? "1 route" : n + " routes";
        }, s -> s.startsWith("None") ? OFF : GOOD);
        rowNote("If you usually take a different way, it wins after you've driven it a couple of times. "
                + "Kept on this device only.");
        padded(button("Forget my usual routes", false, v -> {
            UsualRoutes.get(this).forgetAll();
            toast("Usual routes forgotten");
        }));

        section("Floating window");
        toggle("Start with the car", "Opens the floating map when the car starts (needs location all the time)",
                prefs.startOnBoot(), prefs::setStartOnBoot);
        padded(button("Put the map back in its starting place", false, v ->
                MapService.send(this, MapService.ACTION_RESET_POSITION)));
    }

    private void buildTraffic() {
        newPage("Traffic (TomTom)");
        pageNote("Without a key, the map, search and routes come from OpenStreetMap, which has no traffic. "
                + "With a free TomTom key, all three come from TomTom instead: its map with traffic colours, its "
                + "place search, and routes and arrival times that allow for traffic, re-checked every few "
                + "minutes while driving.");

        section("Your TomTom key");
        rowNote("Get one at developer.tomtom.com (free account, no card needed) and paste it here. "
                + "It's kept only on this device.");
        EditText keyBox = field("TomTom API key", prefs.tomtomKey());
        padded(button("Save and test the key", true, v -> {
            final String key = keyBox.getText().toString().trim();
            prefs.setTomtomKey(key);
            changed();
            if (key.isEmpty()) {
                toast("Key removed. Back to OpenStreetMap.");
                return;
            }
            toast("Testing the key…");
            new Thread(() -> {
                final String problem = Router.testKey(key);
                handler.post(() -> toast(problem == null ? "The key works. TomTom is on." : problem));
            }).start();
        }));
        statusRow("Status", this::trafficStatus, s -> prefs.tomtomKey().isEmpty() ? OFF
                : s.contains("didn't") || s.contains("couldn't") || s.contains("used up") ? WARN : GOOD);

        section("On the map");
        toggle("Show traffic", "Colours along the roads (also the 🚦 button on the map)", prefs.traffic(),
                prefs::setTraffic);
        choice("Update the traffic", new String[]{"Every 2 minutes", "Every 5 minutes", "Every 10 minutes",
                        "Every 15 minutes", "Never: only load traffic for new areas"},
                prefs.trafficRefresh(), prefs::setTrafficRefresh);
        rowNote("Less often uses less of the free allowance.");
        toggle("Show incidents", "And warn about those on the route", prefs.incidents(), prefs::setIncidents);

        section("This month's requests");
        for (int i = 0; i < Usage.kinds(); i++) usageRow(i);
        rowNote("Counted by this app on this device, from the 1st of each month. TomTom's own dashboard "
                + "is the final word.");
    }

    private void buildHud() {
        newPage("Head-up display");

        section("Head-up display");
        toggle("Use the HUD", "Directions and the mini map while following a route", prefs.hud(), prefs::setHud);
        toggle("Mini map of the route ahead", null, prefs.hudMap(), prefs::setHudMap);
        slider("Brightness", 20, 100, prefs.hudOpacity(), "%", prefs::setHudOpacity);
        padded(button("Show a sample on the HUD for 20 seconds", false, v -> {
            if (!MapService.running) {
                toast("Start the floating map first");
                return;
            }
            MapService.send(this, MapService.ACTION_HUD_TEST);
        }));

        section("When not following a route");
        choice("The HUD shows", new String[]{"Nothing", "Map of the roads around the car",
                "Now playing on Spotify", "Time and date", "Now playing, and time and date"}, prefs.hudIdle(), i -> {
            prefs.setHudIdle(i);
            if (hudLayout != null) hudLayout.reload();
        });
        slider("Empty circle in the middle of the map", 0, 100, prefs.hudHole(), "%", prefs::setHudHole);
        rowNote("The circle leaves room for the car's speed readout. Now playing needs the Spotify tab set up.");

        section("Panel layout");
        rowNote("Drag a box to move it, drag its corner square to resize it. The HUD follows while you do, "
                + "with the arrow and text sized to fit each box. Leave the middle clear for the car's speed "
                + "readout (the dashed circle).");
        final HudLayoutView layout = new HudLayoutView(this, prefs, new HudLayoutView.Listener() {
            @Override
            public void onStart() {
                if (!MapService.running) {
                    toast("Start the floating map to see the panels on the HUD");
                } else if (!hudLayout.isIdle()) {
                    MapService.send(MainActivity.this, MapService.ACTION_HUD_TEST);
                } else {
                    // The card itself is already on the HUD while no route is being followed.
                    if (!prefs.hudMusic() && !prefs.hudClock()) {
                        toast("Choose Now playing or Time and date above to see them on the HUD");
                    }
                }
            }

            @Override
            public void onChange() {
                changed();
            }
        });
        hudLayout = layout;
        boolean startIdle = prefs.hudMusic() || prefs.hudClock();
        layout.setIdle(startIdle);
        segmented(new String[]{"Following a route", "Not following a route"}, startIdle ? 1 : 0,
                i -> layout.setIdle(i == 1));
        LinearLayout.LayoutParams layoutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutLp.setMargins(dp(16), dp(10), dp(16), dp(4));
        root.addView(layout, layoutLp);
        padded(button("Put the panels back where they started", false, v -> {
            prefs.resetHudBoxes();
            layout.reload();
            changed();
        }));

        section("If the HUD doesn't show");
        statusRow("Status", () -> Hud.status, s -> s.startsWith("Showing") ? GOOD
                : s.equals("Off") || s.equals("Not started") ? OFF : WARN);
        choice("Kind of window", new String[]{"Second-screen window (as Zeekr Shortcut uses)",
                "Display-over-apps window"}, prefs.hudOverlay() ? 1 : 0, i -> prefs.setHudOverlay(i == 1));
        rowNote("Change it if the directions don't appear on top of what the car shows.");
        final List<Integer> displayIds = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        displayIds.add(-1);
        displayNames.add("Find it automatically");
        for (Display d : getSystemService(DisplayManager.class).getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            Point size = Hud.realSize(d);
            displayIds.add(d.getDisplayId());
            displayNames.add("Display " + d.getDisplayId() + " (" + size.x + " x " + size.y + ")");
        }
        int chosen = Math.max(0, displayIds.indexOf(prefs.hudDisplay()));
        choice("HUD display", displayNames.toArray(new String[0]), chosen,
                i -> prefs.setHudDisplay(displayIds.get(i)));
        rowNote("Black is unlit on a HUD, so only the arrow, text and route line show.");
    }

    private void buildSpotify() {
        newPage("Spotify");
        pageNote("Shows what's playing on your Spotify account, on any device, on the HUD while no route is "
                + "being followed (choose Now playing on Spotify in the Head-up display tab).");

        section("Account");
        statusRow("Status", this::spotifyStatus, s -> s.equals("not set up") || s.equals("Signed out") ? OFF
                : s.contains("signed in") || s.contains("Playing") || s.contains("playing") ? GOOD : WARN);
        EditText spotifyBox = field("Spotify Client ID", prefs.spotifyClientId());
        padded(button("Save the Client ID and sign in", true, v -> {
            String id = spotifyBox.getText().toString().trim();
            if (!id.equals(prefs.spotifyClientId())) prefs.signOutSpotify();
            prefs.setSpotifyClientId(id);
            if (id.isEmpty()) {
                toast("Client ID removed");
                changed();
                return;
            }
            startActivity(new Intent(this, SpotifyLoginActivity.class));
        }));
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setPadding(dp(16), dp(4), dp(16), dp(12));
        TextView browser = button("Sign in using the browser", false, v -> {
            String id = spotifyBox.getText().toString().trim();
            if (!id.equals(prefs.spotifyClientId())) prefs.signOutSpotify();
            prefs.setSpotifyClientId(id);
            if (id.isEmpty()) {
                toast("Type the Client ID first");
                return;
            }
            startActivity(new Intent(this, SpotifyLoginActivity.class)
                    .putExtra(SpotifyLoginActivity.EXTRA_BROWSER, true));
        });
        TextView signOut = button("Sign out", false, v -> {
            prefs.signOutSpotify();
            Spotify.status = "Signed out";
            toast("Signed out of Spotify");
            changed();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pair.addView(browser, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(10);
        pair.addView(signOut, half2);
        root.addView(pair);

        section("Setting up (once)");
        step(1, "At developer.spotify.com/dashboard, create an app and choose Web API.");
        step(2, "Add this redirect URI to the app: " + Spotify.REDIRECT);
        step(3, "Copy the app's Client ID into the box above and sign in.");
        rowNote("Since February 2026 Spotify requires the app's owner to have Premium. If you sign in with a "
                + "different account, add it under the app's User Management.");
    }

    private void buildAbout() {
        newPage("About");
        section("Car Maps");
        statusRow("Version", () -> Updater.currentVersion(this), s -> ACCENT);
        updater = new Updater(this, prefs);
        padded(button("Check for updates", true, v -> updater.checkNow()));

        section("Screens this car has");
        TextView displays = rowNote("");
        displays.setTextColor(TEXT);
        live.add(() -> displays.setText(Hud.describeDisplays(this)));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        if (updater != null) updater.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        changed();
        refreshStatus();
    }

    /** A setting changed: tell the running service. */
    private void changed() {
        if (hudLayout != null) hudLayout.invalidate();
        MapService.send(this, MapService.ACTION_REFRESH);
    }

    private void refreshStatus() {
        for (Runnable r : live) r.run();
    }

    private String spotifyStatus() {
        return Spotify.status.isEmpty() ? (Spotify.signedIn(prefs) ? "signed in" : "not set up") : Spotify.status;
    }

    private String trafficStatus() {
        if (prefs.tomtomKey().isEmpty()) return "Off (no key), using OpenStreetMap";
        String traffic = !prefs.traffic() ? "Map traffic: switched off"
                : TileMap.trafficStatus.isEmpty() ? "Map traffic: not started" : TileMap.trafficStatus;
        StringBuilder out = new StringBuilder();
        for (String part : new String[]{TileMap.mapStatus, traffic, Router.searchStatus, Router.trafficStatus}) {
            if (!part.isEmpty()) out.append(out.length() > 0 ? "\n" : "").append(part);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ building blocks

    private void newPage(String title) {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        pages.add(page);
        TextView t = text(title, 28, true);
        t.setPadding(dp(4), 0, 0, dp(8));
        page.addView(t);
    }

    /** A note under the page title, outside any card. */
    private void pageNote(String s) {
        TextView t = note(s);
        t.setPadding(dp(4), 0, dp(4), dp(4));
        page.addView(t);
    }

    /** Starts a new card on the page, with an optional small title above it. @return the card */
    private LinearLayout section(String title) {
        if (title != null) {
            TextView t = text(title.toUpperCase(Locale.getDefault()), 13, true);
            t.setTextColor(ACCENT);
            t.setLetterSpacing(0.08f);
            t.setPadding(dp(8), dp(22), 0, dp(8));
            page.addView(t);
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (title == null) lp.topMargin = dp(10);
        page.addView(card, lp);
        root = card;
        return card;
    }

    /** Adds a row to the current card, with a thin line above it if it isn't the first. */
    private void row(View v) {
        if (root.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(16), 0, dp(16), 0);
            root.addView(line, lp);
        }
        root.addView(v);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 14, false);
        t.setTextColor(DIM);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** Small grey text inside the current card, without a line above it. */
    private TextView rowNote(String s) {
        TextView t = note(s);
        t.setPadding(dp(16), dp(6), dp(16), dp(12));
        root.addView(t);
        return t;
    }

    private View dot() {
        View d = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(OFF);
        d.setBackground(g);
        d.setLayoutParams(new LinearLayout.LayoutParams(dp(12), dp(12)));
        return d;
    }

    private static void tint(View dot, int colour) {
        ((GradientDrawable) dot.getBackground()).setColor(colour);
    }

    private interface Source {
        String get();
    }

    private interface Colour {
        int of(String s);
    }

    /** A label, a coloured dot and a live value. */
    private void statusRow(String label, Source value, Colour colour) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView l = text(label, 16, false);
        l.setMinWidth(dp(110));
        r.addView(l);
        View d = dot();
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.setMargins(0, dp(6), dp(10), 0);
        r.addView(d, dlp);
        TextView v = text("", 15, false);
        v.setTextColor(DIM);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row(r);
        live.add(() -> {
            String s = value.get();
            v.setText(s);
            tint(d, colour.of(s));
        });
    }

    private void usageRow(int kind) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout top = new LinearLayout(this);
        TextView l = text(Usage.name(kind), 16, false);
        top.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = text("", 14, false);
        n.setTextColor(DIM);
        top.addView(n);
        r.addView(top);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(8);
        r.addView(bar, blp);
        row(r);
        live.add(() -> {
            int used = Usage.count(kind), free = Usage.free(kind);
            double part = used / (double) free;
            n.setText(String.format(Locale.US, "%,d of %,d", used, free));
            bar.setProgress((int) Math.min(1000, Math.round(part * 1000)));
            bar.setProgressTintList(ColorStateList.valueOf(part >= 0.9 ? 0xFFE5534B : part >= 0.6 ? WARN : ACCENT));
        });
    }

    private void step(int number, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView n = text(String.valueOf(number), 14, true);
        n.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF243A57);
        n.setBackground(g);
        n.setTextColor(ACCENT);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(28), dp(28));
        nlp.rightMargin = dp(14);
        r.addView(n, nlp);
        TextView t = text(s, 15, false);
        t.setTextIsSelectable(true);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row(r);
    }

    private TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView b = text(label, 16, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(56));
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? PRIMARY : SECONDARY);
        bg.setCornerRadius(dp(14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Adds a full-width button to the current card, with room around it. */
    private void padded(TextView b) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(16), dp(8), dp(16), dp(10));
        f.addView(b, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(f);
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(17);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTypeface(Typeface.DEFAULT);   // that input type otherwise switches to a typewriter font
        e.setText(value);
        e.setMinHeight(dp(56));
        e.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF12161A);
        bg.setCornerRadius(dp(12));
        bg.setStroke(1, LINE);
        e.setBackground(bg);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(16), dp(6), dp(16), dp(4));
        f.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(f);
        return e;
    }

    private void toggle(String label, String hint, boolean value, BoolSetter setter) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 17, false));
        if (hint != null) {
            TextView h = note(hint);
            h.setPadding(0, dp(2), 0, 0);
            texts.addView(h);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch s = new Switch(this);
        s.setChecked(value);
        s.setOnCheckedChangeListener((v, on) -> {
            setter.set(on);
            changed();
        });
        r.addView(s);
        // The whole row flips the switch, not just the switch itself.
        r.setOnClickListener(v -> s.toggle());
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row(r);
    }

    private void choice(String label, String[] options, int value, IntSetter setter) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(6), dp(8), dp(6));
        r.addView(text(label, 17, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(Math.max(0, Math.min(options.length - 1, value)));
        sp.setMinimumHeight(dp(56));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(ACCENT);
                    ((TextView) view).setTextSize(16);
                }
                if (first) {
                    first = false;   // the spinner reports its starting value once
                    return;
                }
                setter.set(position);
                changed();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        // Long option names would squash the label, so the list takes at most a bit over half the row.
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        r.addView(sp, slp);
        r.post(() -> {
            int max = Math.round(r.getWidth() * 0.58f);
            if (sp.getWidth() > max) {
                slp.width = max;
                sp.setLayoutParams(slp);
            }
        });
        row(r);
    }

    private void slider(String label, int min, int max, int value, String unit, IntSetter setter) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(label, 17, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value + unit, 16, true);
        v.setTextColor(ACCENT);
        top.addView(v);
        r.addView(top);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setMinimumHeight(dp(48));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                v.setText((progress + min) + unit);
                setter.set(progress + min);
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        r.addView(bar);
        row(r);
    }

    /** A row of buttons of which one is chosen, like tabs within a card. */
    private void segmented(String[] options, int value, IntSetter setter) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF12161A);
        bg.setCornerRadius(dp(14));
        r.setBackground(bg);
        final TextView[] parts = new TextView[options.length];
        final Runnable[] paint = new Runnable[1];
        final int[] chosen = {value};
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView t = text(options[i], 15, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(48));
            t.setOnClickListener(x -> {
                chosen[0] = index;
                paint[0].run();
                setter.set(index);
            });
            parts[i] = t;
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        paint[0] = () -> {
            for (int i = 0; i < parts.length; i++) {
                boolean on = i == chosen[0];
                GradientDrawable g = new GradientDrawable();
                g.setColor(on ? PRIMARY : 0x00000000);
                g.setCornerRadius(dp(11));
                parts[i].setBackground(g);
                parts[i].setTextColor(on ? 0xFFFFFFFF : DIM);
            }
        };
        paint[0].run();
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(16), dp(4), dp(16), 0);
        f.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
