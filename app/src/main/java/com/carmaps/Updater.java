package com.carmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates the app from its GitHub releases: finds the newest release, downloads its APK and hands it
 * to Android's installer, which asks the user to confirm. The repository is public, so no account is
 * needed.
 */
final class Updater {

    private static final String TAG = "Updater";
    private static final String REPO = "shifthk/car-maps";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String AGENT = "CarMaps-Updater";
    private static final long AUTO_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L;

    private static final class Release {
        String version = "";
        String notes = "";
        String apkName = "";
        String apkUrl = "";
        long apkSize;
    }

    private final Activity activity;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    Updater(Activity activity, Prefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    void shutdown() {
        io.shutdownNow();
    }

    /** Quiet check when the settings screen opens: says something only if there is a newer version. */
    void autoCheck() {
        if (System.currentTimeMillis() - prefs.lastUpdateCheck() < AUTO_CHECK_EVERY_MS) return;
        check(false);
    }

    /** The "Check for updates" button. */
    void checkNow() {
        check(true);
    }

    private void check(boolean userAsked) {
        if (busy) return;
        busy = true;
        if (userAsked) toast("Checking for updates…");
        io.execute(() -> {
            try {
                Release r = latest();
                prefs.setLastUpdateCheck(System.currentTimeMillis());
                String mine = currentVersion(activity);
                main.post(() -> {
                    busy = false;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (compare(r.version, mine) > 0) offer(r, mine);
                    else if (userAsked) info("Up to date", "You have the newest version (" + mine + ").");
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Update check failed", e);
                main.post(() -> {
                    busy = false;
                    if (userAsked && !activity.isFinishing() && !activity.isDestroyed()) {
                        info("Couldn't check for updates", message(e));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ GitHub

    private Release latest() throws IOException, JSONException {
        HttpURLConnection c = open(LATEST);
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests right now. Try again later.");
            if (code == 404) throw new IOException("No release has been published yet.");
            if (code != 200) throw new IOException("GitHub answered HTTP " + code + ".");
            JSONObject j = new JSONObject(text(c.getInputStream()));
            Release r = new Release();
            String tag = j.optString("tag_name", "");
            r.version = tag.replaceFirst("^[vV]", "");
            r.notes = j.optString("body", "").trim();
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                        r.apkName = a.optString("name");
                        r.apkUrl = a.optString("browser_download_url");
                        r.apkSize = a.optLong("size");
                        break;
                    }
                }
            }
            if (!r.apkUrl.startsWith("https://")) {
                throw new IOException("The newest release (" + tag + ") has no APK attached.");
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Downloads the APK into the app's cache, reporting how far along it is (0..1000). */
    private File download(Release r, ProgressBar bar) throws IOException {
        File out = new File(activity.getCacheDir(), "update.apk");
        HttpURLConnection c = open(r.apkUrl);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Download failed (HTTP " + c.getResponseCode() + ").");
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int at = (int) (done * 1000 / total);
                        main.post(() -> {
                            bar.setIndeterminate(false);
                            bar.setProgress(at);
                        });
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", AGENT);
        return c;
    }

    private static String text(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void offer(Release r, String mine) {
        String notes = r.notes.isEmpty() ? "" : "\n\n" + (r.notes.length() > 1200 ? r.notes.substring(0, 1200) + "…" : r.notes);
        new AlertDialog.Builder(activity)
                .setTitle("Update available: " + r.version)
                .setMessage("You have " + mine + "." + notes)
                .setPositiveButton("Update", (d, w) -> startUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(Release r) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow installing updates")
                    .setMessage("Android needs your OK for Car Maps to install its own updates. Turn on "
                            + "“Allow from this source” on the next screen, come back, and tap "
                            + "Check for updates again.")
                    .setPositiveButton("Open settings", (d, w) -> openInstallSettings(r))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(12), pad, dp(8));
        TextView label = new TextView(activity);
        label.setText("Downloading " + r.apkName + "…");
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        box.addView(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(bar, lp);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Updating to " + r.version)
                .setView(box)
                .setCancelable(false)
                .show();

        io.execute(() -> {
            try {
                File apk = download(r, bar);
                main.post(() -> label.setText("Opening the installer…"));
                install(apk);
                main.post(() -> dismiss(dialog));
            } catch (IOException e) {
                Log.w(TAG, "Update failed", e);
                main.post(() -> {
                    dismiss(dialog);
                    if (!activity.isFinishing() && !activity.isDestroyed()) info("Update failed", message(e));
                });
            }
        });
    }

    private void dismiss(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (RuntimeException ignored) {
            // the screen it was on has gone
        }
    }

    private void openInstallSettings(Release r) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException e) {
            info("Can't install updates here", "This screen has no page to allow it. Download " + r.apkName
                    + " from github.com/" + REPO + "/releases in a browser instead.");
        }
    }

    private void info(String title, String msg) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private static String message(Exception e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return "No internet connection.";
        }
        return e.getMessage() == null ? "Something went wrong." : e.getMessage();
    }

    // ------------------------------------------------------------------ installing

    /** Blocking. Streams the APK into an install session; Android then shows its own confirm screen. */
    private void install(File apk) throws IOException {
        PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(activity, InstallResult.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(activity, id, done, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                pi.abandonSession(id);
            } catch (RuntimeException ignored) {
                // already gone
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /** Receives the installer's answer: shows its confirm screen, or reports a failure. */
    public static final class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (RuntimeException e) {
                        Toast.makeText(context, "Couldn't open the installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, "Update not installed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static String currentVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "0" : p.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares dotted versions numerically: "1.10" is newer than "1.9", "1.5.0" equals "1.5". */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
