package com.greenbackterminal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Runs while a voice call is going on, like a phone call:
 *  - an "Ongoing call" notification with a timer and Hang up (Android keeps the app and its microphone alive with
 *    the screen off or while the member uses other apps),
 *  - sound through the earpiece by default (speaker on request, Bluetooth / wired headsets automatically),
 *  - the screen turns off when the phone is held to the ear.
 */
public class CallService extends Service {
    private static final String CH = "gb_ongoing_call";
    private static final int ID = 7003;
    static volatile boolean running = false;
    private static volatile boolean speaker = false;

    private PowerManager.WakeLock cpu, prox;
    private String name = "";
    private boolean active = false;
    private long since = 0;
    private int oldMode = AudioManager.MODE_NORMAL;

    static void update(Context c, String state, String who) {
        Intent i = new Intent(c, CallService.class).putExtra("state", state).putExtra("name", who == null ? "" : who);
        try {
            if ("ended".equals(state)) { if (running) c.startService(i); return; }
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { } // e.g. not allowed from the background: the call still works while the app is open
    }

    static void setSpeaker(Context c, boolean on) {
        speaker = on;
        if (running) try { c.startService(new Intent(c, CallService.class).putExtra("state", "route")); } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String state = i == null ? "ended" : i.getStringExtra("state");
        if (state == null) state = "ended";
        if ("hangup".equals(state)) { MainActivity.hangUp(); stopAll(); return START_NOT_STICKY; }
        if ("ended".equals(state)) { stopAll(); return START_NOT_STICKY; }
        if ("route".equals(state)) { route(); return START_NOT_STICKY; }
        String who = i.getStringExtra("name");
        if (who != null && !who.isEmpty()) name = who;
        if ("active".equals(state) && !active) { active = true; since = System.currentTimeMillis(); }
        if (!running) start();
        else notifyNow();
        return START_NOT_STICKY;
    }

    private void start() {
        running = true;
        speaker = false;
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, n);
        } catch (Exception e) {
            try { startForeground(ID, n); } catch (Exception ignored) { }
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) { oldMode = am.getMode(); try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (Exception ignored) { } }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "greenback:call");
            cpu.setReferenceCounted(false);
            cpu.acquire(4 * 60 * 60 * 1000L);
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "greenback:ear");
                prox.setReferenceCounted(false);
            }
        }
        route();
    }

    /** where the sound goes: headset if one is connected, else earpiece, or the loudspeaker when asked */
    @SuppressWarnings("deprecation")
    private void route() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        boolean ear = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                List<AudioDeviceInfo> ds = am.getAvailableCommunicationDevices();
                AudioDeviceInfo pick = null;
                int[] order = speaker ? new int[]{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                        : new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE};
                for (int t : order) { for (AudioDeviceInfo d : ds) if (d.getType() == t) { pick = d; break; } if (pick != null) break; }
                if (pick != null) { am.setCommunicationDevice(pick); ear = pick.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE; }
            } else {
                am.setSpeakerphoneOn(speaker);
                ear = !speaker && !am.isWiredHeadsetOn() && !am.isBluetoothA2dpOn();
            }
        } catch (Exception ignored) { }
        // screen off at the ear only when the sound comes out of the earpiece
        try {
            if (prox != null) { if (ear && !prox.isHeld()) prox.acquire(4 * 60 * 60 * 1000L); else if (!ear && prox.isHeld()) prox.release(); }
        } catch (Exception ignored) { }
    }

    private Notification build() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "Ongoing calls", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shown while you are on a Greenback voice call");
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent back = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 7301, back, fl);
        PendingIntent hang = PendingIntent.getService(this, 7302, new Intent(this, CallService.class).putExtra("state", "hangup"), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF22C55E).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle(name.isEmpty() ? "Greenback voice call" : name)
                .setContentText(active ? "Ongoing voice call · tap to return" : "Calling…")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Hang up", hang).build());
        if (active) b.setUsesChronometer(true).setWhen(since).setShowWhen(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void notifyNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, build());
    }

    @SuppressWarnings("deprecation")
    private void stopAll() {
        running = false;
        try { if (cpu != null && cpu.isHeld()) cpu.release(); } catch (Exception ignored) { }
        try { if (prox != null && prox.isHeld()) prox.release(); } catch (Exception ignored) { }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            try { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else am.setSpeakerphoneOn(false); } catch (Exception ignored) { }
            try { am.setMode(oldMode == AudioManager.MODE_IN_COMMUNICATION ? AudioManager.MODE_NORMAL : oldMode); } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() { if (running) stopAll(); super.onDestroy(); }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // the app was swiped away during the call: the call can't go on without it
        MainActivity.hangUp();
        stopAll();
        super.onTaskRemoved(rootIntent);
    }
}
