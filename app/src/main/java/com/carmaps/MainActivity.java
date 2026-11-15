package com.carmaps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Setup and settings. The map itself lives in the floating window ({@link MapService}). */
public class MainActivity extends Activity {

    private interface IntSetter {
        void set(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private Prefs prefs;
    private Updater updater;
    private LinearLayout root;
    private TextView status;
    private Button startStop, overlayButton, locationButton, backgroundButton;
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        LinearLayout column = new LinearLayout(this);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(root, new LinearLayout.LayoutParams(Math.min(dp(760),
                getResources().getDisplayMetrics().widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(column);
        setContentView(scroll);

        TextView title = text("Car Maps", 28, true);
        root.addView(title);
        status = text("", 15, false);
        status.setTextColor(0xFFB6C0CA);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        // ---- floating map
        startStop = button("", v -> {
            if (MapService.running) {
                startService(new Intent(this, MapService.class).setAction(MapService.ACTION_STOP));
            } else if (!Settings.canDrawOverlays(this)) {
                toast("Allow display over other apps first");
            } else {
                MapService.start(this);
            }
            handler.postDelayed(this::refreshStatus, 400);
        });
        root.addView(startStop);
        overlayButton = button("Allow display over other apps", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                toast("This screen has no such setting. Grant it with: adb shell appops set com.carmaps SYSTEM_ALERT_WINDOW allow");
            }
        });
        root.addView(overlayButton);
        locationButton = button("Allow location", v -> requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS}, 1));
        root.addView(locationButton);
        backgroundButton = button("Allow location all the time (needed to start with the car)", v -> {
            if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
            }
        });
        root.addView(backgroundButton);
        root.addView(button("Put the map back in its starting place", v ->
                MapService.send(this, MapService.ACTION_RESET_POSITION)));

        root.addView(note("Search for a place, or hold a finger on the "
                + "map to drop a pin, and it works out a driving route. Tap Start for turn-by-turn guidance on the "
                + "map, on the HUD and by voice."));

        // ---- map
        heading("Guidance");
        toggle("Start with the car", prefs.startOnBoot(), prefs::setStartOnBoot);
        toggle("Dark map", prefs.darkMap(), prefs::setDarkMap);
        toggle("Spoken directions", prefs.voice(), prefs::setVoice);
        toggle("Avoid tolls", prefs.avoidTolls(), prefs::setAvoidTolls);
        toggle("Avoid highways", prefs.avoidHighways(), prefs::setAvoidHighways);

        // ---- traffic
        heading("TomTom");
        root.addView(note("Without a key, the map, search and routes come from OpenStreetMap, which has no "
                + "traffic. With a free TomTom key, all three come from TomTom instead: its map with traffic "
                + "colours, its place search, and routes and arrival times that allow for traffic, re-checked "
                + "every few minutes while driving. Get a key at developer.tomtom.com (free account, no card), "
                + "then type it here."));
        EditText keyBox = new EditText(this);
        keyBox.setHint("TomTom API key");
        keyBox.setSingleLine(true);
        keyBox.setTextSize(17);
        keyBox.setTextColor(0xFFF2F4F6);
        keyBox.setHintTextColor(0xFF9AA4AE);
        keyBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyBox.setText(prefs.tomtomKey());
        keyBox.setMinHeight(dp(56));
        root.addView(keyBox);
        root.addView(button("Save and test the key", v -> {
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
        toggle("Show traffic on the map", prefs.traffic(), prefs::setTraffic);

        // ---- HUD
        heading("Head-up display");
        toggle("Show directions on the HUD", prefs.hud(), prefs::setHud);
        toggle("Mini map of the route ahead", prefs.hudMap(), prefs::setHudMap);
        toggle("Cover what the car shows behind the directions", prefs.hudSolid(), prefs::setHudSolid);
        toggle("Blank the whole HUD while directions show", prefs.hudCover(), prefs::setHudCover);
        choice("Kind of window on the HUD (change it if the directions don't appear on top)",
                new String[]{"Second-screen window (as Zeekr Shortcut uses)", "Display-over-apps window"},
                prefs.hudOverlay() ? 1 : 0, i -> prefs.setHudOverlay(i == 1));

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

        slider("Gap in the middle (for the car's speed readout)", 0, 70, prefs.hudGap(), prefs::setHudGap);
        slider("Left / right", 0, 100, prefs.hudX(), prefs::setHudX);
        slider("Up / down", 0, 100, prefs.hudY(), prefs::setHudY);
        slider("Size", 30, 100, prefs.hudSize(), prefs::setHudSize);
        slider("Brightness", 20, 100, prefs.hudOpacity(), prefs::setHudOpacity);
        root.addView(button("Show a sample on the HUD for 20 seconds", v -> {
            if (!MapService.running) {
                toast("Start the floating map first");
                return;
            }
            MapService.send(this, MapService.ACTION_HUD_TEST);
        }));
        root.addView(note("The directions sit to the left of the gap and the mini map to the right. Move the "
                + "sliders while the sample is showing to place them. Black is unlit on a HUD, so "
                + "the solid black behind the directions looks like nothing but hides whatever the car shows in "
                + "that spot; the car's readouts elsewhere on the HUD stay. Blanking the whole HUD hides the "
                + "car's speed readout too."));

        // ---- updates
        heading("App");
        root.addView(note("Version " + Updater.currentVersion(this)));
        updater = new Updater(this, prefs);
        root.addView(button("Check for updates", v -> updater.checkNow()));
        updater.autoCheck();
    }

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
        MapService.send(this, MapService.ACTION_REFRESH);
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        overlayButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
        locationButton.setVisibility(location ? View.GONE : View.VISIBLE);
        backgroundButton.setVisibility(location && !background ? View.VISIBLE : View.GONE);
        startStop.setText(MapService.running ? "Stop the floating map" : "Start the floating map");
        status.setText("Floating map: " + (MapService.running ? "running" : "stopped")
                + "\nGPS: " + MapService.gpsStatus
                + "\nTomTom: " + trafficStatus()
                + "\nHUD: " + Hud.status
                + "\n" + Hud.describeDisplays(this));
    }

    private String trafficStatus() {
        if (prefs.tomtomKey().isEmpty()) return "off (no key), using OpenStreetMap";
        String traffic = !prefs.traffic() ? "Map traffic: switched off"
                : TileMap.trafficStatus.isEmpty() ? "Map traffic: not started" : TileMap.trafficStatus;
        StringBuilder out = new StringBuilder();
        for (String part : new String[]{TileMap.mapStatus, traffic, Router.searchStatus, Router.trafficStatus}) {
            if (!part.isEmpty()) out.append(out.length() > 0 ? "\n   " : "").append(part);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ small view helpers

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFF2F4F6);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 14, false);
        t.setTextColor(0xFF9AA4AE);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private void heading(String s) {
        TextView t = text(s, 20, true);
        t.setPadding(0, dp(28), 0, dp(6));
        root.addView(t);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setMinHeight(dp(56));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void toggle(String label, boolean value, BoolSetter setter) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(17);
        s.setChecked(value);
        s.setMinHeight(dp(56));
        s.setOnCheckedChangeListener((v, on) -> {
            setter.set(on);
            changed();
        });
        root.addView(s);
    }

    private void choice(String label, String[] options, int value, IntSetter setter) {
        root.addView(note(label));
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
        root.addView(sp);
    }

    private void slider(String label, int min, int max, int value, IntSetter setter) {
        TextView t = note(label + ": " + value);
        root.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setMinimumHeight(dp(48));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                t.setText(label + ": " + (progress + min));
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
        root.addView(bar);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
