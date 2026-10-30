package com.trough.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps Trough's process (and the userland it hosts) alive in the background, including after
 * its Activity is swiped from Recents: an attachable bundle keeps running (HotLoader.process).
 * The notification's Quit is the way to end the process on purpose.
 */
public class TroughService extends Service {
    private static final String CHANNEL = "trough", QUIT = "t.t.QUIT";

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Trough", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, TroughActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent quit = PendingIntent.getService(this, 1, new Intent(this, TroughService.class).setAction(QUIT), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Trough")
            .setContentText("running")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Quit", quit).build())
            .build();
        startForeground(1, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && QUIT.equals(intent.getAction())) {
            HotLoader l = HotLoader.process;
            if (l != null) l.stopCurrent();   // drafts saved, sessions closed
            stopForeground(true);
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
