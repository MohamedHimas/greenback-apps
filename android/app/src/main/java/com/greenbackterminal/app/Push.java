package com.greenbackterminal.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Phone notifications through Firebase Cloud Messaging.
 * The Firebase settings come from the website (api/push.php), so nothing secret or project-specific is built into the app:
 * the admin pastes them once in Admin → Settings → Notifications.
 */
final class Push {
    static final String CHANNEL = "gb_alerts";
    private static final String PREFS = "gb_push";

    private Push() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Messages & alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Private messages, price alerts, new ideas and live streams");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    /** Start Firebase from the settings saved last time (also used when a notification wakes the app). */
    static boolean init(Context c) {
        try {
            if (!FirebaseApp.getApps(c).isEmpty()) return true;
            String raw = prefs(c).getString("cfg", null);
            if (raw == null) return false;
            JSONObject j = new JSONObject(raw);
            FirebaseOptions o = new FirebaseOptions.Builder()
                    .setApplicationId(j.getString("appId"))
                    .setApiKey(j.getString("apiKey"))
                    .setProjectId(j.getString("projectId"))
                    .setGcmSenderId(j.optString("senderId"))
                    .setStorageBucket(j.optString("storageBucket", null))
                    .build();
            FirebaseApp.initializeApp(c.getApplicationContext(), o);
            return true;
        } catch (Exception e) { return false; }
    }

    static String token(Context c) { return prefs(c).getString("token", ""); }

    /** Fetch the settings from the website, start Firebase and get this phone's token. Calls done() when there is a token. */
    static void refresh(final Context c, final Runnable done) {
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://greenbackterminal.com/api/push.php").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000);
                InputStream in = h.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                JSONObject a = new JSONObject(b.toString("UTF-8")).optJSONObject("android");
                if (a != null && a.has("appId")) {
                    String old = prefs(c).getString("cfg", "");
                    if (!a.toString().equals(old)) prefs(c).edit().putString("cfg", a.toString()).apply();
                }
            } catch (Exception ignored) { }
            if (!init(c)) return;
            try {
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                    if (!t.isSuccessful() || t.getResult() == null) return;
                    prefs(c).edit().putString("token", t.getResult()).apply();
                    if (done != null) done.run();
                });
            } catch (Exception ignored) { }
        }).start();
    }
}
