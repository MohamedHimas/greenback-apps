package com.greenbackterminal.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** "Decline" pressed on a ringing call notification: stop ringing and tell the website (no need to open the app). */
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final int call = i.getIntExtra("call", 0);
        final String key = i.getStringExtra("key");
        Calls.stop(c, call);
        if (call <= 0 || key == null || key.isEmpty()) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://greenbackterminal.com/api/rtc.php").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000); h.setDoOutput(true); h.setRequestMethod("POST");
                h.setRequestProperty("Content-Type", "application/json");
                byte[] body = new JSONObject().put("action", "call_decline_key").put("call", call).put("key", key).toString().getBytes("UTF-8");
                try (OutputStream os = h.getOutputStream()) { os.write(body); }
                h.getResponseCode(); h.disconnect();
            } catch (Exception ignored) { }
            pr.finish();
        }).start();
    }
}
