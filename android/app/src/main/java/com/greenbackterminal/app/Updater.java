package com.greenbackterminal.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * One-tap app updates: downloads the new APK from the website, checks it, and hands it to Android's package
 * installer. The first time, Android asks once to allow updates from this app and shows "Update"; from then on
 * (Android 12 and newer) the app installs its own updates without any more questions, then reopens.
 * The page shows the progress through window.GBUpd(stage, value).
 */
final class Updater {
    private static final String APK_URL = "https://greenbackterminal.com/downloads/GreenbackTerminal.apk";
    private static volatile boolean busy = false;
    private static String pendingVersion = null, pendingSha = null;

    private Updater() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gb_update", Context.MODE_PRIVATE); }

    /** Android 8+: the "allow updates from this app" switch (asked once) */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void start(MainActivity a, String version, String sha) {
        if (busy) return;
        Context c = a.getApplicationContext();
        if (!allowed(c)) {
            pendingVersion = version; pendingSha = sha;
            a.tellUpdate("permission", "0");
            a.runOnUiThread(() -> {
                try {
                    a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName())));
                } catch (Exception e) { a.tellUpdate("error", "Open Android Settings → Apps → Greenback Terminal → Install unknown apps, and turn it on."); }
            });
            return;
        }
        busy = true;
        new Thread(() -> run(a, version, sha), "gb-update").start();
    }

    /** back from the settings screen: carry on if the switch was turned on */
    static void resume(MainActivity a) {
        if (pendingVersion == null) return;
        String v = pendingVersion, s = pendingSha;
        if (allowed(a.getApplicationContext())) { pendingVersion = null; pendingSha = null; start(a, v, s); }
        else { pendingVersion = null; pendingSha = null; a.tellUpdate("cancel", "Updates need the “Allow from this source” switch. Tap Update again to try."); }
    }

    private static void run(MainActivity a, String version, String sha) {
        Context c = a.getApplicationContext();
        File apk = new File(c.getCacheDir(), "update.apk");
        try {
            a.tellUpdate("download", "0");
            HttpURLConnection con = (HttpURLConnection) new URL(APK_URL + "?v=" + Uri.encode(version == null ? "" : version)).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "GreenbackApp/android/" + BuildConfig.VERSION_NAME);
            if (con.getResponseCode() != 200) throw new Exception("the server said " + con.getResponseCode());
            long total = con.getContentLengthLong(), got = 0;
            int last = -1;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); md.update(buf, 0, n); got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : 0;
                    if (pct != last) { last = pct; a.tellUpdate("download", String.valueOf(pct)); }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (sha != null && sha.trim().length() == 64 && !hex.toString().equalsIgnoreCase(sha.trim()))
                throw new Exception("the download was damaged — please try again");

            a.tellUpdate("install", "0");
            prefs(c).edit().putLong("started", System.currentTimeMillis()).putString("to", version == null ? "" : version).apply();
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            p.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            if (Build.VERSION.SDK_INT >= 26) p.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER);
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, Result.class).setAction("GB_UPDATE_RESULT");
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, 7401, done, fl).getIntentSender());
            }
        } catch (Exception e) {
            busy = false;
            try { apk.delete(); } catch (Exception ignored) { }
            String m = e.getMessage() == null ? "please check your internet and try again" : e.getMessage();
            a.tellUpdate("error", m);
        }
    }

    /** what Android's installer says: needs a tap ("Update"), done, or failed */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                    MainActivity.tellUpdateStatic("confirm", "0");
                }
                return;
            }
            busy = false;
            if (st == PackageInstaller.STATUS_SUCCESS) {
                MainActivity.tellUpdateStatic("done", "0"); // the app restarts now
                return;
            }
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_FAILURE_ABORTED) MainActivity.tellUpdateStatic("cancel", "Update cancelled. Tap Update when you are ready.");
            else MainActivity.tellUpdateStatic("error", msg == null ? "Android could not install the update" : msg);
            try { new File(c.getCacheDir(), "update.apk").delete(); } catch (Exception ignored) { }
        }
    }

    /** after an update the app was replaced: reopen it (or, if Android doesn't allow that, show a notification) */
    public static class Replaced extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
            try { new File(c.getCacheDir(), "update.apk").delete(); } catch (Exception ignored) { }
            SharedPreferences sp = prefs(c);
            long t = sp.getLong("started", 0);
            sp.edit().remove("started").apply();
            if (System.currentTimeMillis() - t > 15 * 60_000L) return; // not an update started from the app
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { c.startActivity(open); } catch (Exception ignored) { }
            Push.channel(c);
            PendingIntent pi = PendingIntent.getActivity(c, 7402, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, Push.CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF22C55E).setAutoCancel(true).setContentIntent(pi)
                    .setContentTitle("Greenback Terminal updated ✓")
                    .setContentText("You're on version " + BuildConfig.VERSION_NAME + ". Tap to open.");
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify("updated", 7403, b.build());
        }
    }
}
