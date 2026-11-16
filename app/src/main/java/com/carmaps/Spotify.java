package com.carmaps;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * What is playing on the user's Spotify account, from Spotify's Web API, for the HUD's now playing
 * card. Works for whatever device is playing (phone, car, speaker), as long as it is the same account.
 *
 * <p>Spotify needs an app registered by the user (developer.spotify.com, free; since February 2026
 * the owner of such an app must have Premium). The user types its Client ID into settings and logs in
 * once; sign-in uses Spotify's PKCE flow, which needs no client secret. The app keeps only the tokens
 * Spotify hands back, never the password.</p>
 */
final class Spotify {

    private static final String TAG = "Spotify";
    static final String REDIRECT = "http://127.0.0.1:43821/carmaps-spotify";
    static final int REDIRECT_PORT = 43821;
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";
    private static final String AUTH = "https://accounts.spotify.com/authorize";
    private static final String TOKEN = "https://accounts.spotify.com/api/token";
    private static final String NOW = "https://api.spotify.com/v1/me/player/currently-playing";

    /** For the settings screen. */
    static volatile String status = "";

    private Spotify() {
    }

    /** What is playing. */
    static final class Track {
        String title = "", artist = "", artUrl;
        long progressMs, durationMs;
        boolean playing;
        /** When the progress was read (SystemClock.elapsedRealtime), to move the bar on between reads. */
        long readAt;
    }

    // ------------------------------------------------------------------ signing in

    /** Starts a sign-in: a fresh verifier is kept in the settings until Spotify sends the user back. */
    static String authorizeUrl(Prefs prefs) {
        byte[] raw = new byte[48];
        new SecureRandom().nextBytes(raw);
        String verifier = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        prefs.setSpotifyVerifier(verifier);
        String challenge;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            challenge = Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Uri.parse(AUTH).buildUpon()
                .appendQueryParameter("client_id", prefs.spotifyClientId())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .build().toString();
    }

    /**
     * Spotify sent the user back with a code: swap it for tokens. Runs network work, so not on the
     * main thread. @return null if it worked, otherwise what went wrong
     */
    static String finishSignIn(Prefs prefs, Uri back) {
        String error = back.getQueryParameter("error");
        if (error != null) return "Spotify said: " + error;
        String code = back.getQueryParameter("code");
        if (code == null) return "Spotify didn't send a sign-in code";
        try {
            JSONObject j = postToken("grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + enc(prefs.spotifyClientId())
                    + "&code_verifier=" + enc(prefs.spotifyVerifier()));
            save(prefs, j);
            status = "Signed in";
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Sign-in failed", e);
            return "Sign-in failed: " + e.getMessage();
        }
    }

    static boolean signedIn(Prefs prefs) {
        return !prefs.spotifyRefresh().isEmpty() && !prefs.spotifyClientId().isEmpty();
    }

    private static void save(Prefs prefs, JSONObject j) throws org.json.JSONException {
        String refresh = j.optString("refresh_token", "");
        prefs.setSpotifyTokens(j.getString("access_token"), refresh.isEmpty() ? prefs.spotifyRefresh() : refresh,
                System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000);
    }

    /** A current access token, refreshing it when it has run out. */
    private static String token(Prefs prefs) throws Exception {
        if (System.currentTimeMillis() < prefs.spotifyExpiry() && !prefs.spotifyAccess().isEmpty()) {
            return prefs.spotifyAccess();
        }
        JSONObject j = postToken("grant_type=refresh_token&refresh_token=" + enc(prefs.spotifyRefresh())
                + "&client_id=" + enc(prefs.spotifyClientId()));
        save(prefs, j);
        return prefs.spotifyAccess();
    }

    private static JSONObject postToken(String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                String why = text;
                try {
                    JSONObject e = new JSONObject(text);
                    why = e.optString("error_description", e.optString("error", text));
                } catch (Exception ignored) {
                    // not JSON: show it as it came
                }
                throw new java.io.IOException("HTTP " + code + " " + why);
            }
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ what's playing

    /**
     * Network work, so not on the main thread. @return the track, or null when nothing is playing
     * (or Spotify can't be reached; {@link #status} says which)
     */
    static Track nowPlaying(Prefs prefs) {
        if (!signedIn(prefs)) {
            status = prefs.spotifyClientId().isEmpty() ? "No Client ID" : "Not signed in";
            return null;
        }
        HttpURLConnection c = null;
        try {
            String token = token(prefs);
            c = (HttpURLConnection) new URL(NOW + "?additional_types=episode").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code == 204) {
                status = "Signed in; nothing playing";
                return null;
            }
            if (code == 401) {
                prefs.setSpotifyTokens("", prefs.spotifyRefresh(), 0);   // refresh on the next try
                status = "Spotify turned the sign-in away; trying again";
                return null;
            }
            if (code == 403) {
                status = "Spotify refused (the app's owner needs Premium, and your account must be "
                        + "added as a user of the app)";
                return null;
            }
            if (code == 429) {
                status = "Spotify asked the app to slow down";
                return null;
            }
            if (code != 200) {
                status = "Spotify answered " + code;
                return null;
            }
            JSONObject j = new JSONObject(read(c.getInputStream()));
            JSONObject item = j.optJSONObject("item");
            if (item == null) {
                status = "Signed in; nothing playing";
                return null;
            }
            Track t = new Track();
            t.title = item.optString("name", "");
            t.durationMs = item.optLong("duration_ms", 0);
            t.progressMs = j.optLong("progress_ms", 0);
            t.playing = j.optBoolean("is_playing", false);
            t.readAt = android.os.SystemClock.elapsedRealtime();
            JSONArray artists = item.optJSONArray("artists");
            if (artists != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < artists.length(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(artists.getJSONObject(i).optString("name"));
                }
                t.artist = sb.toString();
            } else if (item.optJSONObject("show") != null) {
                t.artist = item.getJSONObject("show").optString("name", "");   // a podcast episode
            }
            JSONObject album = item.optJSONObject("album");
            JSONArray images = album != null ? album.optJSONArray("images") : item.optJSONArray("images");
            if (images != null && images.length() > 0) {
                // Spotify lists the biggest first; the card is small, so take the one nearest 300 px.
                String best = null;
                int bestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < images.length(); i++) {
                    JSONObject im = images.getJSONObject(i);
                    int diff = Math.abs(im.optInt("width", 300) - 300);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = im.optString("url", null);
                    }
                }
                t.artUrl = best;
            }
            status = "Signed in; showing " + (t.playing ? "what's playing" : "the paused track");
            return t;
        } catch (Exception e) {
            Log.d(TAG, "Now playing: " + e);
            status = "Couldn't reach Spotify: " + e.getMessage();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The album picture. Network work. */
    static Bitmap art(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
