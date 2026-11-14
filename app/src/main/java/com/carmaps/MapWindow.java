package com.carmaps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating map: one overlay window showing the Google Maps website, with a round button on its
 * top right corner. Tapping the button hides the map (leaving just the button, in the same place) or
 * shows it again; dragging the button moves the window; dragging the grip in the bottom left corner
 * resizes it.
 *
 * <p>When the page shows directions to somewhere, a bar under the map offers to guide there on the
 * HUD, and then shows the next turn.</p>
 */
final class MapWindow {

    interface Host {
        /** The page is showing directions to this place. Coordinates are NaN when the page didn't give them. */
        void onDestination(String name, double lat, double lng);

        /** The page is no longer showing directions. */
        void onNoDestination();

        void startNav();

        void stopNav();

        boolean hasLocationPermission();
    }

    private static final String TAG = "MapWindow";
    private static final String HOME = "https://www.google.com/maps";

    /**
     * Runs in the Google Maps page. Tells the app when a text box is tapped (so the window can take
     * keyboard focus), and remembers the address of the page's own directions request.
     */
    private static final String SETUP_JS = "(function(){if(window.__carmaps)return;window.__carmaps=1;"
            + "try{performance.setResourceTimingBufferSize(4000)}catch(e){}"
            + "try{new PerformanceObserver(function(l){l.getEntries().forEach(function(e){"
            + "if(e.name.indexOf('/maps/preview/directions')>=0)window.__carmapsDir=e.name})})"
            + ".observe({type:'resource',buffered:true})}catch(e){}"
            + "function ed(t){return !!t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA'||t.isContentEditable)}"
            + "document.addEventListener('click',function(e){Car.focus(ed(e.target))},true);"
            + "document.addEventListener('focusin',function(e){if(ed(e.target))Car.focus(true)},true);"
            + "})()";

    /**
     * Runs in the Google Maps page while it shows directions. The destination's name is the last
     * part of the address. Its coordinates are either that same part (a dropped pin), or are read
     * from the answer to the page's own directions request, next to the place's ID.
     */
    private static final String DEST_JS = "(function(){var u=location.href;"
            + "var m=u.match(/\\/maps\\/dir\\/([^@?]*)/);if(!m||m[1].indexOf('/')<0){Car.noDest();return}"
            + "var segs=m[1].split('/').filter(function(s){return s&&s.indexOf('data=')!==0});"
            + "if(!segs.length){Car.noDest();return}"
            + "var name=segs[segs.length-1];try{name=decodeURIComponent(name.replace(/\\+/g,' '))}catch(e){}"
            + "var c=name.match(/^\\s*(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\s*$/);"
            + "if(c){Car.dest(name,+c[1],+c[2]);return}"
            + "var ids=u.match(/0x[0-9a-f]+:0x[0-9a-f]+/g);var id=ids?ids[ids.length-1]:null;"
            + "var d=window.__carmapsDir;if(!id||!d){Car.dest(name,NaN,NaN);return}"
            + "fetch(d,{credentials:'include'}).then(function(r){return r.text()}).then(function(t){"
            + "var k=t.match(new RegExp('\"'+id+'\",\\\\[null,null,(-?[0-9.]+),(-?[0-9.]+)\\\\]'));"
            + "if(k)Car.dest(name,+k[1],+k[2]);else Car.dest(name,NaN,NaN)})"
            + ".catch(function(){Car.dest(name,NaN,NaN)})})()";

    private final Context ui;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;
    private final Frame frame;
    private final LinearLayout content;
    private final FrameLayout webHolder;
    private final IconButton icon;
    private final View grip;
    private final int iconSize, inset, minW, minH;

    // The bar under the map.
    private final LinearLayout bar;
    private final TurnView barIcon;
    private final TextView barTitle, barSub, barButton;
    private Runnable barAction;

    private WebView web;
    private boolean collapsed;
    private boolean focused;
    private boolean added;
    private boolean destroyed;
    private int screenW, screenH;
    /** The map's top left corner and size when shown. */
    private int x, y, w, h;
    private String lastUrl = "";

    private final Runnable watchUrl = new Runnable() {
        @Override
        public void run() {
            checkUrl();
            main.postDelayed(this, 1500);
        }
    };

    MapWindow(Context ui, WindowManager wm, Prefs prefs, Host host) {
        this.ui = ui;
        this.wm = wm;
        this.prefs = prefs;
        this.host = host;
        iconSize = dp(56);
        inset = dp(18);
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

        webHolder = new FrameLayout(ui);
        content.addView(webHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF0C5A3C);
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
        barButton.setMinHeight(dp(48));
        barButton.setOnClickListener(v -> {
            if (barAction != null) barAction.run();
        });
        bar.addView(barButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
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
            return;
        }
        if (!collapsed) ensureWeb();
        main.postDelayed(watchUrl, 1500);
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

    /** Back to the Google Maps start page (also signs of trouble: a blank or stuck page). */
    void goHome() {
        if (web != null) web.loadUrl(HOME);
    }

    void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        CookieManager.getInstance().flush();
        if (web != null) {
            webHolder.removeView(web);
            web.destroy();
            web = null;
        }
        if (added) {
            try {
                wm.removeView(frame);
            } catch (RuntimeException ignored) {
                // already gone
            }
            added = false;
        }
    }

    // ------------------------------------------------------------------ the bar under the map

    /** "Guide on HUD to X" with a Start button. */
    void showOffer(String title, String sub) {
        setBar(0xFF22303C, null, title, sub, "Start", host::startNav);
    }

    void showMessage(String title, String sub) {
        setBar(0xFF22303C, null, title, sub, null, null);
    }

    /** The next turn while guiding. */
    void showTurn(Maneuvers.Icon turn, String title, String sub) {
        setBar(0xFF0C5A3C, turn, title, sub, "End", host::stopNav);
        icon.setActive(true);
    }

    void hideBar() {
        bar.setVisibility(View.GONE);
        icon.setActive(false);
    }

    private void setBar(int color, Maneuvers.Icon turn, String title, String sub, String action, Runnable run) {
        bar.setBackgroundColor(color);
        barIcon.setIcon(turn);
        barIcon.setVisibility(turn == null ? View.GONE : View.VISIBLE);
        barTitle.setText(title);
        barSub.setText(sub);
        barSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        barButton.setText(action == null ? "" : action);
        barButton.setVisibility(action == null ? View.GONE : View.VISIBLE);
        barButton.setBackgroundColor(turn == null ? 0xFF1A73E8 : 0xFFD9453A);
        barAction = run;
        bar.setVisibility(View.VISIBLE);
        if (turn == null) icon.setActive(false);
    }

    // ------------------------------------------------------------------ the page

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWeb() {
        if (web != null || destroyed) return;
        try {
            web = new WebView(ui);
        } catch (RuntimeException e) {
            Log.e(TAG, "No WebView on this device", e);
            showMessage("This screen has no web view", "The map can't be shown.");
            return;
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // Present as ordinary Chrome: Google's sign-in page turns away apps' built-in web views.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", ""));
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFF1B1F24);
        web.addJavascriptInterface(new Bridge(), "Car");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.d(TAG, "page: " + m.message());
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                // Only Google's own pages get the car's position, and only if the app itself has it.
                boolean google = origin != null && origin.matches("https://([a-z0-9.-]+\\.)?google\\.[a-z.]+/?");
                callback.invoke(origin, google && host.hasLocationPermission(), false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The site keeps offering to open the Google Maps app (intent: and market: links): stay here.
                String scheme = request.getUrl().getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) return false;
                Log.d(TAG, "Not leaving the page for " + request.getUrl());
                if ("intent".equals(scheme)) {
                    // Such links carry the web address to use when the app isn't installed.
                    try {
                        String fallback = android.content.Intent.parseUri(request.getUrl().toString(),
                                android.content.Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url");
                        if (fallback != null && fallback.startsWith("https://")) view.loadUrl(fallback);
                    } catch (java.net.URISyntaxException ignored) {
                        // not a link we can read: just stay put
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SETUP_JS, null);
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's process died (the car was short of memory): start a fresh one.
                Log.w(TAG, "Map page process gone; reloading");
                main.post(() -> {
                    if (destroyed) return;
                    webHolder.removeView(view);
                    view.destroy();
                    if (web == view) web = null;
                    if (!collapsed) ensureWeb();
                });
                return true;
            }
        });
        webHolder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(HOME);
    }

    /** The site changes its address without loading a new page, so the address is checked on a timer. */
    private void checkUrl() {
        if (web == null || destroyed) return;
        String url = web.getUrl();
        if (url == null) return;
        boolean directions = url.contains("/maps/dir/");
        if (url.equals(lastUrl) && !retryDest) return;
        lastUrl = url;
        retryDest = false;
        web.evaluateJavascript(SETUP_JS, null);
        if (directions) web.evaluateJavascript(DEST_JS, null);
        else host.onNoDestination();
    }

    private boolean retryDest;

    /** The page hadn't finished fetching its directions: look again on the next check. */
    void retryDestination() {
        retryDest = true;
    }

    /** Called by the page, on a WebView thread: everything hops to the main thread. */
    private final class Bridge {
        @JavascriptInterface
        public void focus(boolean on) {
            main.post(() -> setFocused(on));
        }

        @JavascriptInterface
        public void dest(String name, double lat, double lng) {
            main.post(() -> host.onDestination(name, lat, lng));
        }

        @JavascriptInterface
        public void noDest() {
            main.post(host::onNoDestination);
        }
    }

    // ------------------------------------------------------------------ keyboard focus

    /**
     * An overlay normally never takes keyboard focus, so the app underneath keeps working. While a
     * text box on the page is in use the window has to take focus for the on-screen keyboard to appear.
     */
    private void setFocused(boolean on) {
        if (focused == on || destroyed || web == null) return;
        focused = on;
        lp.flags = on
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                : baseFlags();
        update();
        InputMethodManager imm = ui.getSystemService(InputMethodManager.class);
        if (on) {
            main.postDelayed(() -> {
                if (!focused || web == null) return;
                web.requestFocus();
                if (imm != null) imm.showSoftInput(web, 0);
            }, 150);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(frame.getWindowToken(), 0);
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
        if (!collapsed) ensureWeb();
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
                    // Back closes the keyboard first, then steps back through the site's pages.
                    if (web != null && web.canGoBack()) web.goBack();
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
