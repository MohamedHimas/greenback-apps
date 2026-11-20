package com.greenbackterminal.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** "Remind me" on an incoming call: a notification later saying to call that person back (opens the chat). */
public class Reminders extends BroadcastReceiver {

    static void callBack(Context c, String name, String room, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, Reminders.class).setAction("GB_REMIND").putExtra("name", name).putExtra("room", room == null ? "" : room);
        int req = ("remind" + name).hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        long at = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        // inexact is fine for a reminder (and needs no special alarm permission)
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String name = i.getStringExtra("name"), room = i.getStringExtra("room");
        if (name == null) name = "them";
        Push.channel(c);
        Intent open = new Intent(c, MainActivity.class).setAction("GB_OPEN").putExtra("link", room != null && !room.isEmpty() ? "/m?chat=" + room : "/m#chats")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, ("open" + name).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, Push.CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF22C55E).setContentTitle("📞 Call " + name + " back")
                .setContentText("You asked Greenback to remind you. Tap to open the chat.").setAutoCancel(true).setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify("remind-" + name, 7002, b.build());
    }
}
