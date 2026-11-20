package com.greenbackterminal.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

/**
 * Rings for an incoming call like the phone's own dialer, also with the app closed and the screen locked:
 *  - runs as a "phone call" foreground service started straight from the call push (Android allows this for urgent pushes),
 *  - plays the member's ringtone itself and vibrates (so a muted notification channel or a phone maker's
 *    notification tweaks can't silence it; the phone's silent / vibrate switch is respected),
 *  - wakes the screen and shows the full-screen call (over the lock screen),
 *  - stops when answered, declined, hung up, answered on another device or after 45 seconds.
 */
public class RingService extends Service {
    static final int ID = 7001;
    static volatile int ringing = -1;

    private MediaPlayer player;
    private Vibrator vib;
    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());

    static void start(Context c, int call, String from, String key, String room) {
        Intent i = new Intent(c, RingService.class).putExtra("call", call).putExtra("from", from == null ? "" : from)
                .putExtra("key", key == null ? "" : key).putExtra("room", room == null ? "" : room);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            // not allowed right now (rare): the ringing notification alone still rings through its channel
            Calls.notifyOnly(c, call, from, key, room);
        }
    }

    static void stop(Context c, int call) {
        if (ringing < 0 || (call >= 0 && call != ringing)) return;
        try { c.stopService(new Intent(c, RingService.class)); } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null) { stopSelf(); return START_NOT_STICKY; }
        int call = i.getIntExtra("call", 0);
        String from = i.getStringExtra("from"), key = i.getStringExtra("key"), room = i.getStringExtra("room");
        Notification n = Calls.build(this, call, from, key, room, true);
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            else startForeground(ID, n);
        } catch (Exception e) {
            try { startForeground(ID, n); } catch (Exception e2) { Calls.notifyOnly(this, call, from, key, room); stopSelf(); return START_NOT_STICKY; }
        }
        boolean fresh = ringing != call;
        ringing = call;
        if (fresh) {
            wakeScreen();
            showScreen(call, from, key, room);
            ring();
        }
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::stopSelf, 45000); // nobody answered
        return START_NOT_STICKY;
    }

    /** turn the screen on for a moment (the call screen then keeps it on) */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wake = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "greenback:ring");
            wake.acquire(46000);
        } catch (Exception ignored) { }
    }

    /** the full-screen intent normally opens the call screen; with "display over other apps" we can also open it ourselves */
    private void showScreen(int call, String from, String key, String room) {
        boolean canOpen = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this) || GbMessaging.visible;
        if (!canOpen) return;
        try {
            startActivity(new Intent(this, IncomingCallActivity.class).putExtra("call", call).putExtra("from", from).putExtra("key", key).putExtra("room", room)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (Exception ignored) { }
    }

    private void ring() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int mode = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            try {
                vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                long[] p = {0, 900, 700};
                if (vib != null) { if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(p, 0)); else vib.vibrate(p, 0); }
            } catch (Exception ignored) { }
        }
        if (mode != AudioManager.RINGER_MODE_NORMAL) return; // vibrate / silent
        Uri u = Calls.soundUri(this);
        if (u == null) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
            player.setLooping(true);
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (Exception e) { releasePlayer(); }
    }

    private void releasePlayer() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        int was = ringing;
        ringing = -1;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
        if (was >= 0) IncomingCallActivity.close(was);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) { /* keep ringing even if the app is swiped away */ }
}
