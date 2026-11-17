package com.carmaps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The floating map: one overlay window with a search box, an OpenStreetMap map and, under the map, a
 * bar for the route and the next turn. A round button sits on the window's top right corner: tapping
 * it hides the map (leaving just the button, in the same place) or shows it again; dragging it moves
 * the window; dragging the grip in the bottom left corner resizes it.
 */
final class MapWindow implements TileMap.Listener {

    interface Host {
        /** Look for places; answer with {@link #showResults}. */
        void search(String text, int id);

        /** Places picked before, newest first. */
        List<Router.Dest> recents();

        /** The user chose somewhere to go. */
        void pick(Router.Dest place);

        /** The user dismissed the destination (only when not being guided). */
        void clearDestination();

        void startNav();

        void stopNav();

        /** The tolls button was tapped: the setting has already been changed. */
        void tollsChanged();

        /** One of the routes on offer was tapped. */
        void chooseRoute(int index);
    }

    private static final String TAG = "MapWindow";
    private static final long TYPING_PAUSE_MS = 600;

    private final Context ui;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;
    private final Frame frame;
    private final LinearLayout content;
    private final IconButton icon;
    private final View grip;
    private final int iconSize, inset, minW, minH;

    private final EditText search;
    private final TextView clear;
    private final TileMap map;
    private final ScrollView resultsScroll;
    private final LinearLayout results;
    private final TextView recenter, trafficButton, tollButton, compass;
    private final LinearLayout chips, choices;
    private final SpeedSign speedSign;
    private final LaneStrip laneStrip;

    // The bar under the map.
    private final LinearLayout bar;
    private final TurnView barIcon;
    private final TextView barTitle, barSub, barButton;
    private Runnable barAction;

    private boolean collapsed;
    private boolean focused;
    private boolean added;
    private boolean destroyed;
    private boolean guiding;
    private boolean settingText;
    private int searchId;
    private int screenW, screenH;
    /** The map's top left corner and size when shown. */
    private int x, y, w, h;

    private final Runnable searchNow = this::runSearch;

    MapWindow(Context ui, WindowManager wm, Prefs prefs, Host host) {
        this.ui = ui;
        this.wm = wm;
        this.prefs = prefs;
        this.host = host;
        iconSize = dp(80);
        inset = dp(26);
        minW = dp(320);
        minH = dp(300);
        collapsed = prefs.collapsed();

        lp = new WindowManager.LayoutParams(iconSize, iconSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

        frame = new Frame(ui);
        content = new LinearLayout(ui);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF1B1F24);
        content.setClipToOutline(true);
        content.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        content.setElevation(dp(6));
        // Room at the top and right so the button sits on the map's corner instead of over its controls.
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(dp(4), inset, inset, dp(4));
        frame.addView(content, clp);

        // ---- search row
        LinearLayout top = new LinearLayout(ui);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF262B31);
        top.setPadding(dp(16), 0, dp(58), 0);   // clear of the round button on the right
        search = new EditText(ui);
        search.setHint("Search for a place");
        search.setHintTextColor(0xFF9AA4AE);
        search.setTextColor(0xFFF2F4F6);
        search.setTextSize(19);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // No copy-and-paste pop-ups: they belong to an app screen, which a floating window isn't.
        search.setLongClickable(false);
        search.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
            }
        });
        top.addView(search, new LinearLayout.LayoutParams(0, dp(56), 1f));
        clear = label(26, false);
        clear.setText("×");
        clear.setGravity(Gravity.CENTER);
        clear.setTextColor(0xFFB6C0CA);
        clear.setVisibility(View.GONE);
        top.addView(clear, new LinearLayout.LayoutParams(dp(52), dp(56)));
        content.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- saved places: Home, Work and favourites, one tap each
        HorizontalScrollView chipScroll = new HorizontalScrollView(ui);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(0xFF20252B);
        chips = new LinearLayout(ui);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(10), dp(6), dp(10), dp(6));
        chipScroll.addView(chips);
        content.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- map, with the results list and the buttons over it
        FrameLayout middle = new FrameLayout(ui);
        map = new TileMap(ui);
        map.setListener(this);
        middle.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: where the map looks. Left: how it behaves.
        LinearLayout side = new LinearLayout(ui);
        side.setOrientation(LinearLayout.VERTICAL);
        recenter = roundButton("◎", v -> {
            map.setFollow(true);
            updateRecenter();
        });
        side.addView(recenter);
        side.addView(roundButton("+", v -> map.zoomBy(1)));
        side.addView(roundButton("−", v -> map.zoomBy(-1)));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        slp.setMargins(0, 0, dp(10), dp(26));
        middle.addView(side, slp);

        LinearLayout left = new LinearLayout(ui);
        left.setOrientation(LinearLayout.VERTICAL);
        // North up, or turned so the way ahead is up while following the car.
        compass = roundButton("N", v -> {
            prefs.setHeadingUp(!prefs.headingUp());
            refresh();
        });
        left.addView(compass);
        // Tolls allowed or avoided; crossed out in red while they are being avoided.
        tollButton = roundButton("$", v -> {
            prefs.setAvoidTolls(!prefs.avoidTolls());
            refresh();
            host.tollsChanged();
        });
        left.addView(tollButton);
        // Live traffic on and off; only there once a TomTom key has been entered in the settings.
        trafficButton = roundButton("\uD83D\uDEA6", v -> {
            prefs.setTraffic(!prefs.traffic());
            refresh();
        });
        trafficButton.setTextSize(20);
        left.addView(trafficButton);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        llp.setMargins(dp(10), 0, 0, dp(26));
        middle.addView(left, llp);

        // The speed limit (top left) and the lanes to be in (top middle), while being guided.
        speedSign = new SpeedSign(ui);
        speedSign.setVisibility(View.GONE);
        FrameLayout.LayoutParams sgp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP | Gravity.START);
        sgp.setMargins(dp(10), dp(10), 0, 0);
        middle.addView(speedSign, sgp);
        laneStrip = new LaneStrip(ui);
        laneStrip.setVisibility(View.GONE);
        FrameLayout.LayoutParams lnp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lnp.topMargin = dp(10);
        middle.addView(laneStrip, lnp);

        resultsScroll = new ScrollView(ui);
        resultsScroll.setBackgroundColor(0xF2262B31);
        resultsScroll.setVisibility(View.GONE);
        results = new LinearLayout(ui);
        results.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(results);
        middle.addView(resultsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        content.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- the routes on offer, when there is more than one
        choices = new LinearLayout(ui);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setBackgroundColor(0xFF1B1F24);
        choices.setPadding(dp(6), dp(6), dp(2), dp(6));
        choices.setVisibility(View.GONE);
        content.addView(choices, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- bar
        bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(52), dp(8), dp(12), dp(8));   // clear of the resize grip on the left
        barIcon = new TurnView(ui);
        bar.addView(barIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(ui);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(10), 0);
        barTitle = label(19, true);
        barSub = label(14, false);
        barSub.setAlpha(0.9f);
        texts.addView(barTitle);
        texts.addView(barSub);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barButton = label(17, true);
        barButton.setGravity(Gravity.CENTER);
        barButton.setPadding(dp(20), 0, dp(20), 0);
        barButton.setOnClickListener(v -> {
            if (barAction != null) barAction.run();
        });
        bar.addView(barButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        bar.setVisibility(View.GONE);
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grip = new Grip(ui);
        frame.addView(grip, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.START));
        grip.setElevation(dp(7));
        grip.setOnTouchListener(new ResizeTouch());

        icon = new IconButton(ui);
        icon.setElevation(dp(8));
        frame.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP | Gravity.END));
        icon.setOnTouchListener(new MoveTouch());

        wireSearch();
        updateRecenter();
        refresh();
    }

    private TextView label(int sp, boolean bold) {
        TextView t = new TextView(ui);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView roundButton(String text, View.OnClickListener l) {
        TextView b = label(24, false);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF2262B31);
        b.setBackground(bg);
        b.setElevation(dp(3));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private static int baseFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    // ------------------------------------------------------------------ lifecycle

    void open(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        place();
        apply();
        try {
            wm.addView(frame, lp);
            added = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't show the map window", e);
        }
    }

    void onScreenChanged(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        clamp();
        apply();
        update();
    }

    void resetPosition() {
        prefs.clearWindow();
        place();
        apply();
        update();
    }

    /** Settings changed. */
    void refresh() {
        boolean up = prefs.headingUp();
        map.setHeadingUp(up);
        compass.setText(up ? "▲" : "N");
        compass.setTextColor(up ? 0xFF4C9BFF : 0xFFFFFFFF);
        refreshSaved();
        String key = prefs.tomtomKey();
        boolean on = !key.isEmpty() && prefs.traffic();
        map.setMap(key.isEmpty() ? null : key);
        map.setTraffic(on ? key : null);
        map.setTrafficFresh(prefs.trafficFreshMs());
        trafficButton.setVisibility(key.isEmpty() ? View.GONE : View.VISIBLE);
        trafficButton.setAlpha(on ? 1f : 0.4f);
        boolean avoid = prefs.avoidTolls();
        tollButton.setTextColor(avoid ? 0xFFFF6B5E : 0xFFFFFFFF);
        tollButton.setPaintFlags(avoid ? tollButton.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : tollButton.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        map.release();
        if (added) {
            try {
                wm.removeView(frame);
            } catch (RuntimeException ignored) {
                // already gone
            }
            added = false;
        }
    }

    // ------------------------------------------------------------------ the map

    void setCar(double lat, double lng, double bearing) {
        map.setCar(lat, lng, (float) bearing);
    }

    /** A place was picked: mark it while its route is being worked out. */
    void showPlace(Router.Dest d) {
        setSearchText(d.name);
        hideResults();
        choices.setVisibility(View.GONE);
        map.clearRoute();
        map.setPin(d.lat, d.lng);
        map.centerOn(d.lat, d.lng, 14);
        updateRecenter();
    }

    /** Draws a route. {@code overview}: step back to show all of it (before setting off). */
    void showRoute(Nav.Route r, boolean overview) {
        choices.setVisibility(View.GONE);
        map.setAlternates(null, null);
        map.setRoute(r.lat, r.lng);
        int last = r.lat.length - 1;
        map.setPin(r.lat[last], r.lng[last]);
        if (overview) map.fitRoute();
        updateRecenter();
    }

    void clearRoute() {
        choices.setVisibility(View.GONE);
        map.clearRoute();
        map.clearPin();
    }

    /**
     * Draws the routes on offer, the chosen one in blue and the others in grey, with a button for each
     * under the map. {@code overview}: step back to show the whole of the chosen route.
     */
    void showRoutes(List<Nav.Route> routes, int chosen, boolean overview) {
        Nav.Route r = routes.get(chosen);
        List<double[]> lats = new ArrayList<>(), lngs = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            if (i == chosen) continue;
            lats.add(routes.get(i).lat);
            lngs.add(routes.get(i).lng);
        }
        map.setAlternates(lats, lngs);
        map.setRoute(r.lat, r.lng);
        int last = r.lat.length - 1;
        map.setPin(r.lat[last], r.lng[last]);
        if (overview) map.fitRoute();
        updateRecenter();

        choices.removeAllViews();
        if (routes.size() < 2) {
            choices.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < routes.size(); i++) {
            final int index = i;
            Nav.Route o = routes.get(i);
            TextView t = label(15, i == chosen);
            t.setSingleLine(false);
            t.setMaxLines(2);
            t.setGravity(Gravity.CENTER);
            t.setText(Geo.duration(o.durationSec) + "\n" + Geo.distance(o.totalDist) + (o.hasTolls ? "  ·  tolls" : ""));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(i == chosen ? 0xFF1A73E8 : 0xFF2F363E);
            t.setBackground(bg);
            t.setPadding(dp(6), dp(6), dp(6), dp(6));
            t.setOnClickListener(v -> host.chooseRoute(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            choices.addView(t, lp);
        }
        choices.setVisibility(View.VISIBLE);
    }

    void setDark(boolean on) {
        map.setDark(on);
    }

    /** The limit on this stretch (0 for none known), red when the car is over it. */
    void setSpeed(int limitKmh, boolean over) {
        speedSign.set(limitKmh, over);
    }

    /** The lanes to be in for the coming junction, or null. */
    void setLanes(Nav.LaneSpot spot) {
        laneStrip.set(spot);
    }

    void setIncidents(List<Router.Incident> list) {
        int n = list.size();
        double[] lat = new double[n], lng = new double[n];
        int[] category = new int[n];
        for (int i = 0; i < n; i++) {
            lat[i] = list.get(i).lat;
            lng[i] = list.get(i).lng;
            category[i] = list.get(i).category;
        }
        map.setIncidents(lat, lng, category);
    }

    /** True while only the round button is showing. */
    boolean isCollapsed() {
        return collapsed;
    }

    /** Guidance started or ended: while guiding, the map stays on the car, zoomed in. */
    void setGuiding(boolean on) {
        guiding = on;
        icon.setActive(on);
        if (on) {
            choices.setVisibility(View.GONE);
            map.setFollow(true);
            map.setZoom(17);
            hideResults();
            setFocused(false);
        }
        updateRecenter();
    }

    @Override
    public void onUserMoved() {
        updateRecenter();
    }

    @Override
    public void onLongPress(double lat, double lng) {
        if (guiding) return;
        frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        host.pick(new Router.Dest("Dropped pin", String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng), lat, lng));
    }

    @Override
    public void onTap() {
        hideResults();
        setFocused(false);
    }

    /** The recentre button is lit while the map is following the car. */
    private void updateRecenter() {
        recenter.setTextColor(map.isFollowing() ? 0xFF4C9BFF : 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ search

    @SuppressLint("ClickableViewAccessibility")
    private void wireSearch() {
        search.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                setFocused(true);
                if (search.getText().length() == 0) showRecents();
            }
            return false;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (settingText) return;
                main.removeCallbacks(searchNow);
                searchId++;   // whatever is still on its way is for older text
                if (s.toString().trim().length() < 3) {
                    if (s.length() == 0) showRecents();
                    return;
                }
                main.postDelayed(searchNow, TYPING_PAUSE_MS);
            }
        });
        search.setOnEditorActionListener((v, action, event) -> {
            main.removeCallbacks(searchNow);
            runSearch();
            setFocused(false);
            return true;
        });
        clear.setOnClickListener(v -> {
            setSearchText("");
            hideResults();
            if (!guiding) host.clearDestination();
        });
    }

    private void runSearch() {
        String text = search.getText().toString().trim();
        if (text.length() < 2) return;
        host.search(text, ++searchId);
    }

    private void setSearchText(String text) {
        settingText = true;
        search.setText(text);
        search.setSelection(search.getText().length());
        settingText = false;
    }

    /** The answer to {@link Host#search}; ignored if the text has changed since it was asked. */
    void showResults(int id, List<Router.Dest> places, String error) {
        if (id != searchId || destroyed) return;
        if (error != null) {
            listRows(null, null, error);
        } else if (places.isEmpty()) {
            listRows(null, null, "Nothing found");
        } else {
            listRows(null, places, null);
        }
    }

    private void showRecents() {
        List<Router.Dest> recent = host.recents();
        if (recent.isEmpty()) hideResults();
        else listRows("RECENT", recent, null);
    }

    private void hideResults() {
        resultsScroll.setVisibility(View.GONE);
    }

    private void listRows(String heading, List<Router.Dest> places, String message) {
        results.removeAllViews();
        if (heading != null) {
            TextView t = label(12, true);
            t.setText(heading);
            t.setTextColor(0xFF9AA4AE);
            t.setPadding(dp(18), dp(10), dp(18), dp(2));
            results.addView(t);
        }
        if (message != null) {
            TextView t = label(16, false);
            t.setText(message);
            t.setTextColor(0xFFB6C0CA);
            t.setSingleLine(false);
            t.setPadding(dp(18), dp(16), dp(18), dp(16));
            results.addView(t);
        }
        if (places != null) {
            for (Router.Dest d : places) results.addView(row(d));
        }
        resultsScroll.scrollTo(0, 0);
        resultsScroll.setVisibility(View.VISIBLE);
    }

    private View row(Router.Dest d) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setMinimumHeight(dp(60));
        LinearLayout texts = new LinearLayout(ui);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(18, false);
        name.setText(d.name);
        texts.addView(name);
        if (!d.detail.isEmpty()) {
            TextView detail = label(14, false);
            detail.setText(d.detail);
            detail.setTextColor(0xFF9AA4AE);
            texts.addView(detail);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!d.distance.isEmpty()) {
            TextView far = label(14, false);
            far.setText(d.distance);
            far.setTextColor(0xFF9AA4AE);
            far.setPadding(dp(12), 0, 0, 0);
            row.addView(far);
        }
        // A star to save the place as Home, Work or a favourite.
        boolean saved = prefs.isFavourite(d) || Prefs.same(d, prefs.place("home")) || Prefs.same(d, prefs.place("work"));
        TextView star = label(24, false);
        star.setText(saved ? "★" : "☆");
        star.setTextColor(saved ? 0xFFFFC53D : 0xFF9AA4AE);
        star.setGravity(Gravity.CENTER);
        star.setOnClickListener(v -> showSaveMenu(d));
        row.addView(star, new LinearLayout.LayoutParams(dp(56), dp(48)));
        row.setOnClickListener(v -> {
            setFocused(false);
            host.pick(d);
        });
        return row;
    }

    // ------------------------------------------------------------------ the bar under the map

    /** A route ready to follow, with a Start button. */
    void showOffer(String title, String sub) {
        setBar(0xFF22303C, null, title, sub, "Start", host::startNav, false);
    }

    void showMessage(String title, String sub) {
        setBar(0xFF22303C, null, title, sub, null, null, false);
    }

    /** The next turn while guiding; {@code turn} is null while rerouting. */
    void showTurn(Maneuvers.Icon turn, String title, String sub) {
        setBar(0xFF0C5A3C, turn, title, sub, "End", host::stopNav, true);
    }

    void hideBar() {
        bar.setVisibility(View.GONE);
    }

    private void setBar(int color, Maneuvers.Icon turn, String title, String sub, String action, Runnable run,
                        boolean whileGuiding) {
        bar.setBackgroundColor(color);
        barIcon.setIcon(turn);
        barIcon.setVisibility(turn == null ? View.GONE : View.VISIBLE);
        barTitle.setText(title);
        barSub.setText(sub);
        barSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        barButton.setText(action == null ? "" : action);
        barButton.setVisibility(action == null ? View.GONE : View.VISIBLE);
        barButton.setBackgroundColor(whileGuiding ? 0xFFD9453A : 0xFF1A73E8);
        barAction = run;
        bar.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ saved places

    /** The row of buttons under the search box: Home, Work, then the favourites. */
    private void refreshSaved() {
        chips.removeAllViews();
        chips.addView(chip("Home", prefs.place("home")));
        chips.addView(chip("Work", prefs.place("work")));
        for (Router.Dest d : prefs.favourites()) chips.addView(chip(d.name, d));
    }

    private View chip(String text, Router.Dest d) {
        TextView t = label(15, d != null);
        t.setText(text);
        t.setMaxWidth(dp(190));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(d == null ? 0xFF2A3037 : 0xFF34404C);
        t.setBackground(bg);
        if (d == null) t.setTextColor(0xFF9AA4AE);
        t.setOnClickListener(v -> {
            if (d == null) {
                listRows(null, null, "To set " + text + ": search for the place, tap ☆ beside it, then choose Set as "
                        + text + ".");
                return;
            }
            setFocused(false);
            hideResults();
            host.pick(d);
        });
        // Holding a saved place offers to clear or change it.
        if (d != null) {
            t.setOnLongClickListener(v -> {
                showSaveMenu(d);
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** In place of the results: what to save a place as. */
    private void showSaveMenu(Router.Dest d) {
        final boolean home = Prefs.same(d, prefs.place("home")), work = Prefs.same(d, prefs.place("work"));
        final boolean favourite = prefs.isFavourite(d);
        results.removeAllViews();
        TextView heading = label(12, true);
        heading.setText(d.name.toUpperCase(java.util.Locale.ROOT));
        heading.setTextColor(0xFF9AA4AE);
        heading.setPadding(dp(18), dp(10), dp(18), dp(2));
        results.addView(heading);
        results.addView(action(home ? "Clear Home" : "Set as Home", () -> prefs.setPlace("home", home ? null : d)));
        results.addView(action(work ? "Clear Work" : "Set as Work", () -> prefs.setPlace("work", work ? null : d)));
        results.addView(action(favourite ? "Remove from favourites" : "Add to favourites",
                () -> prefs.setFavourite(d, !favourite)));
        results.addView(action("Cancel", null));
        resultsScroll.scrollTo(0, 0);
        resultsScroll.setVisibility(View.VISIBLE);
    }

    private View action(String text, Runnable run) {
        TextView t = label(18, false);
        t.setText(text);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(56));
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setOnClickListener(v -> {
            if (run != null) run.run();
            refreshSaved();
            hideResults();
            setFocused(false);
        });
        return t;
    }

    // ------------------------------------------------------------------ keyboard focus

    /**
     * An overlay normally never takes keyboard focus, so the app underneath keeps working. While the
     * search box is in use the window has to take focus for the on-screen keyboard to appear.
     */
    private void setFocused(boolean on) {
        if (focused == on || destroyed) return;
        focused = on;
        lp.flags = on
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                : baseFlags();
        update();
        InputMethodManager imm = ui.getSystemService(InputMethodManager.class);
        if (on) {
            main.postDelayed(() -> {
                if (!focused) return;
                search.requestFocus();
                if (imm != null) imm.showSoftInput(search, 0);
            }, 150);
        } else {
            if (imm != null) imm.hideSoftInputFromWindow(frame.getWindowToken(), 0);
            search.clearFocus();
        }
    }

    // ------------------------------------------------------------------ show / hide, position, size

    private void toggle() {
        if (destroyed) return;
        setFocused(false);
        collapsed = !collapsed;
        prefs.setCollapsed(collapsed);
        apply();
        update();
    }

    /** First time: the right-hand side of the screen. */
    private void place() {
        int[] saved = prefs.window();
        if (saved != null) {
            x = saved[0];
            y = saved[1];
            w = saved[2];
            h = saved[3];
        } else {
            w = Math.max(minW, Math.round(screenW * 0.42f));
            h = Math.max(minH, Math.round(screenH * 0.78f));
            x = screenW - w - dp(24);
            y = Math.round(screenH * 0.10f);
        }
        clamp();
    }

    private void clamp() {
        w = Math.max(minW, Math.min(w, screenW));
        h = Math.max(minH, Math.min(h, screenH));
        x = Math.max(0, Math.min(screenW - w, x));
        y = Math.max(0, Math.min(screenH - h, y));
    }

    /** Works out the window from the map's position: when hidden, the window is just the button, in the same spot. */
    private void apply() {
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        grip.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapsed) {
            lp.width = iconSize;
            lp.height = iconSize;
            lp.x = x + w - iconSize;
            lp.y = y;
        } else {
            lp.width = w;
            lp.height = h;
            lp.x = x;
            lp.y = y;
        }
    }

    private void update() {
        if (!added) return;
        try {
            wm.updateViewLayout(frame, lp);
        } catch (RuntimeException ignored) {
            // the window went away underneath us
        }
    }

    private void save() {
        prefs.setWindow(x, y, w, h);
    }

    private int dp(float v) {
        return Math.round(v * ui.getResources().getDisplayMetrics().density);
    }

    /** Tap the round button to show or hide the map; drag it to move the window. */
    private final class MoveTouch implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop() * 2;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = x;
                    startY = y;
                    dragging = false;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true;
                        v.setPressed(false);
                    }
                    if (dragging) {
                        x = startX + dx;
                        y = startY + dy;
                        // Only the button has to stay on screen, so the map can be parked mostly off the edge.
                        x = Math.max(iconSize - w, Math.min(screenW - w, x));
                        y = Math.max(0, Math.min(screenH - iconSize, y));
                        apply();
                        update();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (dragging) save();
                    else {
                        v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                        toggle();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (dragging) save();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Drag the bottom left grip to resize; the button's corner (top right) stays where it is. */
    private final class ResizeTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startW, startH;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = x;
                    startW = w;
                    startH = h;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                    int right = startX + startW;
                    w = Math.max(minW, Math.min(right, startW - dx));
                    h = Math.max(minH, Math.min(screenH - y, startH + dy));
                    x = right - w;
                    apply();
                    update();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    save();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** The window's root: notices touches outside the window and the back key. */
    private final class Frame extends FrameLayout {
        Frame(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                setFocused(false);
                return false;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && focused) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    hideResults();
                    setFocused(false);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    /** The round button: a navigation arrow, blue while a route is being followed. */
    private static final class IconButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private boolean active;

        IconButton(Context c) {
            super(c);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }

        void setActive(boolean on) {
            if (active != on) {
                active = on;
                invalidate();
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth(), r = s / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0xFF1A73E8 : 0xF21F2428);
            c.drawCircle(r, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.03f);
            paint.setColor(isPressed() ? 0xFFFFFFFF : 0x40FFFFFF);
            c.drawCircle(r, r, r - s * 0.015f, paint);
            arrow.rewind();
            arrow.moveTo(s * 0.50f, s * 0.24f);
            arrow.lineTo(s * 0.70f, s * 0.72f);
            arrow.lineTo(s * 0.50f, s * 0.62f);
            arrow.lineTo(s * 0.30f, s * 0.72f);
            arrow.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            c.drawPath(arrow, paint);
        }
    }

    /** A round speed limit sign: red ring on white, or solid red once the car is over the limit. */
    private static final class SpeedSign extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int limit;
        private boolean over;

        SpeedSign(Context c) {
            super(c);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void set(int limit, boolean over) {
            if (this.limit == limit && this.over == over) return;
            this.limit = limit;
            this.over = over;
            setVisibility(limit > 0 ? VISIBLE : GONE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f, x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(over ? 0xFFD93025 : 0xFFFFFFFF);
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.2f);
            paint.setColor(over ? 0xFFFFFFFF : 0xFFD93025);
            c.drawCircle(x, y, r * 0.88f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(over ? 0xFFFFFFFF : 0xFF111111);
            paint.setTextSize(limit >= 100 ? r * 0.78f : r * 0.95f);
            c.drawText(String.valueOf(limit), x, y - (paint.ascent() + paint.descent()) / 2f, paint);
        }
    }

    /** One arrow per lane, the lanes to be in drawn in white and the others in grey. */
    private final class LaneStrip extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Nav.LaneSpot spot;

        LaneStrip(Context c) {
            super(c);
            on.setColor(0xFFFFFFFF);
            off.setColor(0xFF6B7682);
            back.setColor(0xE61B1F24);
        }

        void set(Nav.LaneSpot spot) {
            if (this.spot == spot) return;
            this.spot = spot;
            setVisibility(spot == null ? GONE : VISIBLE);
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int lanes = spot == null ? 0 : spot.arrows.length;
            setMeasuredDimension(lanes * dp(44) + dp(16), dp(52));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (spot == null) return;
            c.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), back);
            Lanes.draw(c, spot, dp(8), dp(4), dp(44), on, off);
        }
    }

    /** The turn arrow in the bar. */
    private static final class TurnView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Maneuvers.Icon turn;

        TurnView(Context c) {
            super(c);
            paint.setColor(0xFFFFFFFF);
        }

        void setIcon(Maneuvers.Icon turn) {
            this.turn = turn;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (turn != null) Maneuvers.draw(c, turn, 0, 0, Math.min(getWidth(), getHeight()), paint);
        }
    }

    /** Three short diagonal lines in the bottom left corner. */
    private static final class Grip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Grip(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xCCFFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth();
            paint.setStrokeWidth(s * 0.07f);
            paint.setShadowLayer(s * 0.06f, 0, 0, 0xFF000000);
            for (int i = 1; i <= 3; i++) {
                float d = s * 0.14f * i;
                c.drawLine(s * 0.22f, s * 0.78f - d, s * 0.22f + d, s * 0.78f, paint);
            }
        }
    }
}
