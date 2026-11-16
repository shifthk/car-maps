package com.carmaps;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Signs in to Spotify: shows Spotify's own login page, and when Spotify sends the user back to
 * {@link Spotify#REDIRECT} (a loopback address, as Spotify requires), catches it before it loads and
 * swaps the code for tokens. When signing in through the browser instead, the app listens on that
 * loopback address for a few minutes to receive the browser's return.
 */
public class SpotifyLoginActivity extends Activity {

    static final String EXTRA_BROWSER = "browser";

    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finishing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        if (prefs.spotifyClientId().isEmpty()) {
            Toast.makeText(this, "Type the Spotify Client ID in first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String url = Spotify.authorizeUrl(prefs);
        if (getIntent().getBooleanExtra(EXTRA_BROWSER, false)) {
            listen(getApplicationContext(), prefs);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException e) {
                Toast.makeText(this, "No browser on this screen", Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u.toString().startsWith(Spotify.REDIRECT)) {
                    finishSignIn(u);
                    return true;
                }
                return false;
            }
        });
        setContentView(web);
        web.loadUrl(url);
    }

    private static volatile java.net.ServerSocket server;

    /** Waits (up to five minutes) for the browser to come back to the loopback address. */
    private static void listen(android.content.Context app, Prefs prefs) {
        java.net.ServerSocket old = server;
        if (old != null) {
            try {
                old.close();
            } catch (java.io.IOException ignored) {
                // already closed
            }
        }
        new Thread(() -> {
            Handler ui = new Handler(Looper.getMainLooper());
            try (java.net.ServerSocket ss = new java.net.ServerSocket(Spotify.REDIRECT_PORT, 4,
                    java.net.InetAddress.getByName("127.0.0.1"))) {
                server = ss;
                ss.setSoTimeout(5 * 60 * 1000);
                while (true) {
                    try (java.net.Socket sock = ss.accept()) {
                        java.io.BufferedReader in = new java.io.BufferedReader(
                                new java.io.InputStreamReader(sock.getInputStream(), "UTF-8"));
                        String line = in.readLine();   // GET /carmaps-spotify?code=... HTTP/1.1
                        if (line == null) continue;
                        String[] parts = line.split(" ");
                        String path = parts.length > 1 ? parts[1] : "";
                        boolean ours = path.startsWith("/carmaps-spotify");
                        String problem = ours ? Spotify.finishSignIn(prefs, Uri.parse("http://127.0.0.1" + path))
                                : "not found";
                        String body = "<html><body style='font-family:sans-serif;background:#111;color:#eee;"
                                + "padding:40px'><h2>" + (problem == null ? "Signed in to Spotify."
                                : ours ? "Sign-in didn't work: " + android.text.Html.escapeHtml(problem) : "Not found")
                                + "</h2><p>You can go back to Car Maps.</p></body></html>";
                        byte[] bytes = body.getBytes("UTF-8");
                        java.io.OutputStream out = sock.getOutputStream();
                        out.write(("HTTP/1.1 " + (ours ? "200 OK" : "404 Not Found")
                                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length
                                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        out.write(bytes);
                        out.flush();
                        if (!ours) continue;
                        ui.post(() -> {
                            Toast.makeText(app, problem == null ? "Signed in to Spotify" : problem,
                                    Toast.LENGTH_LONG).show();
                            if (MapService.running) MapService.send(app, MapService.ACTION_REFRESH);
                        });
                        break;
                    }
                }
            } catch (java.io.IOException e) {
                // timed out, or a newer sign-in took over the address
            } finally {
                server = null;
            }
        }).start();
    }

    private void finishSignIn(Uri back) {
        if (finishing) return;
        finishing = true;
        Toast.makeText(this, "Signing in to Spotify…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String problem = Spotify.finishSignIn(prefs, back);
            main.post(() -> {
                Toast.makeText(this, problem == null ? "Signed in to Spotify" : problem, Toast.LENGTH_LONG).show();
                if (MapService.running) MapService.send(this, MapService.ACTION_REFRESH);
                finish();
            });
        }).start();
    }
}
