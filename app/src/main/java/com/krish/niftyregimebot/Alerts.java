package com.krish.niftyregimebot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Phone notifications: the ongoing status line and pop-up trade alerts. */
final class Alerts {
    static final int STATUS_ID = 1;
    private static final String CH_STATUS = "status", CH_ALERTS = "alerts";

    private final Context ctx;
    private final NotificationManager nm;
    private final boolean live;
    private int alertId = 100;

    Alerts(Context ctx, boolean live) {
        this.ctx = ctx;
        this.live = live;
        this.nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Bot status", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Trade alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    Notification status(String text) {
        return new Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(ctx.getString(R.string.app_name) + (live ? " · LIVE" : " · PAPER"))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    void updateStatus(String text) { nm.notify(STATUS_ID, status(text)); }

    void show(String text) {
        Notification n = new Notification.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        nm.notify(alertId++, n);
        if (alertId > 150) alertId = 100;
    }
}
