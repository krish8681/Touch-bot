package com.krish.niftyregimebot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.krish.niftyregimebot.core.Json;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.Position;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Morning check at about 09:00 IST on trading days. If a position is saved but the bot is not
 * running, or the Dhan token is old, it shows an alert so nobody forgets an open WEEKLY trade.
 * Re-schedules itself; also scheduled after a phone restart and whenever the app opens.
 */
public final class Reminder extends BroadcastReceiver {
    private static final String ACTION = "com.krish.niftyregimebot.MORNING_CHECK";
    private static final String CH = "alerts";

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        ZonedDateTime now = ZonedDateTime.now(MarketClock.IST);
        LocalDate day = now.toLocalDate();
        if (!now.toLocalTime().isBefore(LocalTime.of(9, 0))) day = day.plusDays(1);
        day = MarketClock.nextTradingDay(day);
        long at = day.atTime(9, 0).atZone(MarketClock.IST).toInstant().toEpochMilli();
        Intent i = new Intent(c, Reminder.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    @Override public void onReceive(Context c, Intent intent) {
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        if (ACTION.equals(a)) check(c);
        schedule(c);
    }

    private static void check(Context c) {
        Settings s = new Settings(c);
        String saved = s.positionJson();
        double ageH = s.tokenMadeMillis() == 0 ? -1 : (System.currentTimeMillis() - s.tokenMadeMillis()) / 3_600_000.0;
        String msg = null;
        if (!saved.isEmpty() && !BotService.isRunning) {
            String what = "a position";
            try { what = Position.fromMap(Json.obj(Json.parse(saved))).summary(); } catch (RuntimeException ignored) {}
            msg = "You still hold " + what + " but the bot is NOT running. Open the app, check the Dhan token and tap Start bot before 09:15.";
        } else if (!saved.isEmpty() && ageH >= 20) {
            msg = String.format(Locale.US, "Open position and the Dhan token is %.0f hours old. Make sure it is renewed or paste a new one.", ageH);
        }
        if (msg == null) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Trade alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(c.getString(R.string.app_name) + " · morning check")
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        nm.notify(99, n);
    }
}
