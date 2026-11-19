package com.greenbackterminal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * Incoming voice calls ("📞 Talk" in private chats): a ringing, full-screen call notification like a normal phone call,
 * also when the app is closed. The website sends a high-priority data message (type=call); this shows the call with
 * Answer / Decline, rings with the member's ringtone until answered, declined, missed (45 s) or answered elsewhere.
 */
final class Calls {
    static final String[] TONES = {"greenback", "classic", "bell", "digital", "chime", "pulse", "phone", "silent"};
    private static final long[] VIBRATE = {0, 900, 600, 900, 600, 900, 600};
    private static final int ID = 7001;

    private Calls() { }

    static String tone(Context c) {
        String t = Push.prefs(c).getString("ringtone", "greenback");
        for (String k : TONES) if (k.equals(t)) return t;
        return "greenback";
    }

    static void setTone(Context c, String t) {
        boolean ok = false;
        for (String k : TONES) if (k.equals(t)) ok = true;
        if (!ok) return;
        Push.prefs(c).edit().putString("ringtone", t).apply();
        channel(c);
    }

    private static Uri sound(Context c, String t) {
        if ("silent".equals(t)) return null;
        if ("phone".equals(t)) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        int res = c.getResources().getIdentifier("ring_" + t, "raw", c.getPackageName());
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + (res != 0 ? res : R.raw.ring_greenback));
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }

    /** Android fixes a channel's sound once it exists, so every ringtone has its own channel ("Calls"). */
    static String channel(Context c) {
        String t = tone(c), id = "gb_calls_" + t;
        if (Build.VERSION.SDK_INT < 26) return id;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return id;
        for (NotificationChannel old : nm.getNotificationChannels())
            if (old.getId().startsWith("gb_calls_") && !old.getId().equals(id)) nm.deleteNotificationChannel(old.getId());
        if (nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, "Calls", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Incoming voice calls from Greenback members");
            ch.setSound(sound(c, t), attrs());
            ch.enableVibration(true);
            ch.setVibrationPattern(VIBRATE);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setBypassDnd(false);
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    private static PendingIntent open(Context c, String link, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("GB_OPEN");
        i.putExtra("link", link);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** Show the ringing call. */
    @SuppressWarnings("deprecation")
    static void ring(Context c, int call, String from, String key) {
        String who = from == null || from.isEmpty() ? "A Greenback member" : from;
        PendingIntent show = open(c, "/m?call=" + call, call * 4);
        PendingIntent answer = open(c, "/m?call=" + call + "&answer=1", call * 4 + 1);
        Intent d = new Intent(c, CallReceiver.class).setAction("GB_DECLINE").putExtra("call", call).putExtra("key", key == null ? "" : key);
        PendingIntent decline = PendingIntent.getBroadcast(c, call * 4 + 2, d, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel(c)) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF22C55E)
                .setContentTitle(who).setContentText("📞 Incoming voice call · Greenback")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(true)
                .setContentIntent(show).setFullScreenIntent(show, true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(true).setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(null, "✕ Decline", decline).build())
                .addAction(new Notification.Action.Builder(null, "📞 Answer", answer).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(45000);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MAX).setVibrate(VIBRATE);
            Uri s = sound(c, tone(c));
            if (s != null) b.setSound(s, attrs());
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // keep ringing until answered / declined
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify("call-" + call, ID, n);
    }

    /** Stop ringing (answered, declined, hung up, missed). */
    static void stop(Context c, int call) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel("call-" + call, ID);
    }

    /** Android 14+: may this app show calls full screen over the lock screen? */
    static boolean canFullScreen(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.canUseFullScreenIntent();
    }
}
