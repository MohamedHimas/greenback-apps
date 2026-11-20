package com.greenbackterminal.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Receives notifications from greenbackterminal.com. */
public class GbMessaging extends FirebaseMessagingService {

    /** the app on screen right now (chat messages then show inside the app instead) */
    static volatile boolean visible = false;

    @Override
    public void onNewToken(String token) {
        Push.prefs(this).edit().putString("token", token).apply();
        MainActivity.tokenChanged();
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        // with the app in the background Android shows the notification itself; this runs when the app is open
        Map<String, String> d = m.getData();
        String type = d.get("type");
        if ("call".equals(type) || "call_end".equals(type)) { call(d, type); return; }
        String title = d.get("title"), body = d.get("body"), link = d.get("link"), tag = d.get("tag");
        if (title == null && m.getNotification() != null) { title = m.getNotification().getTitle(); body = m.getNotification().getBody(); }
        if (title == null) return;
        if (visible && tag != null && tag.startsWith("chat-")) return;
        show(title, body, link, tag);
    }

    /** a voice call is ringing (or stopped ringing) */
    private void call(Map<String, String> d, String type) {
        int id;
        try { id = Integer.parseInt(d.get("call")); } catch (Exception e) { return; }
        if ("call".equals(type)) {
            if (visible && MainActivity.incomingCall(id)) return; // the app is open: it rings inside the app
            Calls.ring(this, id, d.get("from"), d.get("key"), d.get("room"));
        } else {
            Calls.stop(this, id);
            MainActivity.callChanged(id);
            String t = d.get("title");
            if (t != null && !t.isEmpty() && !visible) show(t, d.get("body"), d.get("link"), "call-" + id + "-missed");
        }
    }

    private void show(String title, String body, String link, String tag) {
        Push.channel(this);
        Intent i = new Intent(this, MainActivity.class);
        i.setAction("GB_OPEN");
        i.putExtra("link", link);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, (tag == null ? title : tag).hashCode(), i, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, Push.CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setAutoCancel(true).setContentIntent(pi).setColor(0xFF22C55E);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(tag == null ? null : tag, tag == null ? (int) (System.currentTimeMillis() % 100000) : 1, b.build());
    }
}
