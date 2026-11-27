package com.krish.niftyregimebot;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Broker;
import com.krish.niftyregimebot.core.Charges;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.Engine;
import com.krish.niftyregimebot.core.Json;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.PaperBroker;
import com.krish.niftyregimebot.core.Position;
import com.krish.niftyregimebot.core.TradeRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import okhttp3.OkHttpClient;

/**
 * Runs the bot on the phone as a foreground service, with the screen off too.
 *
 * Parts: {@link Engine} (the rules, on the "engine" thread) · {@link DhanLink} (all Dhan
 * traffic, on its "io" thread) · {@link Alerts} (notifications) · {@link StatusBuilder}
 * (what the screen shows) · {@link Journal} and {@link Telegram} (records and messages).
 */
public final class BotService extends Service implements DhanLink.Host {
    public static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_TRADE = "trade",
            ACTION_PAUSE = "pause", ACTION_EMERGENCY = "emergency", ACTION_ACK = "ack", ACTION_RELOAD = "reload";

    /** True while the service is running in this process (used by the morning reminder). */
    public static volatile boolean isRunning;

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(MarketClock.IST);
    private static final long WAKE_SPAN = 12 * 60 * 60 * 1000L;

    private HandlerThread engineThread;
    private Handler eh;
    private PowerManager.WakeLock wake;

    private Settings settings;
    private Journal journal;
    private Telegram telegram;
    private Alerts alerts;
    private Config cfg;
    private Engine engine;
    private DhanLink link;
    private boolean liveMode;

    private volatile boolean running;
    private long startedMillis, lastNotifMillis, lastWakeRenew, lastEngineErrorAlert;

    public static void send(Context c, String action) {
        Intent i = new Intent(c, BotService.class).setAction(action);
        if (ACTION_START.equals(action)) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : String.valueOf(intent.getAction());
        if (ACTION_START.equals(a)) {
            if (!running) begin();
            return START_STICKY;
        }
        if (ACTION_RELOAD.equals(a)) {
            if (running) link.reconnect(new Settings(this), "new settings / token");
            return START_STICKY;
        }
        if (!running) {
            if (!ACTION_STOP.equals(a)) BotHub.log(now() + " Bot is off — tap Start bot first");
            stopSelf();
            return START_NOT_STICKY;
        }
        switch (a) {
            case ACTION_STOP: end(); break;
            case ACTION_TRADE: post(this::startTrading); break;
            case ACTION_PAUSE: post(() -> engine.pause("paused by you")); break;
            case ACTION_EMERGENCY: post(() -> engine.emergencyExit(System.currentTimeMillis())); break;
            case ACTION_ACK: post(() -> engine.acknowledgeRecovery()); break;
            default: break;
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------------ lifecycle

    private void begin() {
        running = true;
        isRunning = true;
        startedMillis = System.currentTimeMillis();
        settings = new Settings(this);
        journal = new Journal(this);
        cfg = settings.config();
        MarketClock.setExtraHolidays(settings.extraHolidayList());
        MarketClock.setExtraEvents(settings.extraEventList());
        liveMode = settings.live();

        alerts = new Alerts(this, liveMode);
        Notification n = alerts.status("Starting…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(Alerts.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Alerts.STATUS_ID, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NiftyRegimeBot:engine");
        wake.setReferenceCounted(false);
        wake.acquire(WAKE_SPAN);  // renewed every 30 min while running; released on stop
        lastWakeRenew = startedMillis;

        OkHttpClient http = new OkHttpClient();
        telegram = new Telegram(http, settings.telegramToken(), settings.telegramChat());
        link = new DhanLink(http, settings, cfg, liveMode, this);

        engineThread = new HandlerThread("engine");
        engineThread.start();
        eh = new Handler(engineThread.getLooper());

        post(() -> {
            Broker broker = liveMode ? link.liveBroker()
                    : new PaperBroker(id -> engine.quote(id), cfg.paperSlippage, cfg.depthMaxAgeMillis, System::currentTimeMillis);
            engine = new Engine(cfg, new Charges(), broker, listener);
            restoreDayStats();
            restorePosition();
            engine.onTimer(System.currentTimeMillis());
            engine.setAvailableFunds(cfg.capital + engine.day().realizedPnl);
            engine.setState(BotState.STARTING, "connecting to Dhan");
            post(tick);
        });
        link.start();
    }

    private void end() {
        running = false;
        isRunning = false;
        Reminder.schedule(this);
        if (link != null) link.stop();
        if (eh != null) {
            eh.removeCallbacksAndMessages(null);
            post(() -> {
                if (engine != null) {
                    Position p = engine.position();
                    if (p != null) {
                        String note = p.holding == Config.Holding.WEEKLY
                                ? "It is saved and will be managed again when you start the bot."
                                : "It is saved; on the next start an intraday position is closed straight away.";
                        listener.onAlert("Bot stopped while holding " + p.type.title + (liveMode ? " (LIVE)" : " (paper)") + ". " + note);
                    }
                    engine.setState(BotState.OFF, "stopped by you");
                }
                publish();
                engineThread.quitSafely();
            });
        }
        if (telegram != null) telegram.shutdown();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running) end();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ engine thread

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            try {
                engine.onTimer(now);
                engine.setAvailableFunds(liveMode && link.funds >= 0 ? link.funds : cfg.capital + engine.day().realizedPnl);
                link.mirror(engine);
                if (now - lastWakeRenew > 30 * 60_000L && wake != null) { wake.acquire(WAKE_SPAN); lastWakeRenew = now; }
                publish();
                if (now - lastNotifMillis > 5_000) { lastNotifMillis = now; alerts.updateStatus(statusLine()); }
            } catch (RuntimeException e) {
                engineError(e);
            }
            eh.postDelayed(this, 1_000);
        }
    };

    private void startTrading() {
        if (!settings.lotSizeConfirmed()) {
            listener.onAlert("Confirm the NIFTY lot size in Settings before trading (current setting: " + cfg.lotSize + ")");
            return;
        }
        if (engine.startTrading()) listener.onAlert((liveMode ? "LIVE" : "Paper") + " trading started (" + cfg.holding + ")");
    }

    private final Engine.Listener listener = new Engine.Listener() {
        @Override public void onLog(String level, String message) {
            BotHub.log(now() + " " + (level.equals("INFO") ? "" : level + " ") + message);
            journal.event(today(), level, message);
        }

        @Override public void onDecision(Engine.Decision d) {
            BotHub.decision(BotHub.describe(d, HM.format(Instant.ofEpochMilli(d.timeMillis))));
            journal.event(today(), "DECISION", (d.regime == null ? "-" : d.regime.confirmed) + " / "
                    + (d.iv == null ? "-" : d.iv.level) + " → " + d.pick.title + " — " + d.action);
        }

        @Override public void onStateChanged(BotState s, String why) { publish(); }
        @Override public void onPositionChanged(Position p) { publish(); }
        @Override public void onPositionSaved(String json) { settings.savePosition(json); }
        @Override public void onTradeClosed(TradeRecord t) { journal.trade(t); }
        @Override public void onStatsChanged(DayStats d) { settings.saveDayStats(Json.write(d.toMap())); }

        @Override public void onAlert(String message) {
            BotHub.log(now() + " ⚠ " + message);
            journal.event(today(), "ALERT", message);
            telegram.send(message);
            alerts.show(message);
        }
    };

    private void restoreDayStats() {
        String s = settings.dayStatsJson();
        if (s.isEmpty()) return;
        try { engine.restoreDay(DayStats.fromMap(Json.obj(Json.parse(s)))); } catch (IllegalArgumentException ignored) { }
    }

    private void restorePosition() {
        String s = settings.positionJson();
        if (s.isEmpty()) return;
        Position p;
        try { p = Position.fromMap(Json.obj(Json.parse(s))); } catch (RuntimeException e) {
            listener.onAlert("Could not read the saved position (" + e.getMessage() + "). Check Dhan.");
            return;
        }
        if (p.live != liveMode) {
            if (p.live) {
                listener.onAlert("A LIVE position was saved (" + p.summary() + ") but the bot is now in PAPER mode. "
                        + "Switch Live on to manage it, or close it yourself in Dhan.");
                engine.setState(BotState.RECOVERY, "live position saved, paper mode on");
            } else {
                listener.onAlert("Dropped a saved PAPER position because Live mode is on.");
                settings.savePosition("");
            }
            return;
        }
        engine.restorePosition(p);
    }

    private void publish() {
        BotHub.set(StatusBuilder.build(engine, cfg, settings, link, liveMode, startedMillis));
    }

    private String statusLine() {
        Position p = engine.position();
        return String.format(Locale.US, "%s · NIFTY %.1f · day ₹%s%s", engine.state(), engine.spot(),
                money(engine.day().realizedPnl), p != null ? " · " + p.type.title + " ₹" + money(p.openPnl) : "");
    }

    // ------------------------------------------------------------------ DhanLink.Host

    @Override public void onEngine(DhanLink.EngineTask task) { post(() -> task.run(engine)); }
    @Override public void info(String msg) { post(() -> listener.onLog("INFO", msg)); }
    @Override public void warn(String msg) { post(() -> listener.onLog("WARN", msg)); }
    @Override public void alert(String msg) { post(() -> listener.onAlert(msg)); }

    @Override public void fail(String msg) {
        post(() -> {
            engine.setState(BotState.ERROR, msg);
            listener.onAlert(msg);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Run on the engine thread; an unexpected error is logged instead of crashing (exits keep running). */
    private void post(Runnable r) {
        if (eh == null) return;
        eh.post(() -> {
            try { r.run(); } catch (RuntimeException e) { engineError(e); }
        });
    }

    private void engineError(RuntimeException e) {
        String msg = "Internal error: " + e;
        StackTraceElement[] st = e.getStackTrace();
        if (st.length > 0) msg += " at " + st[0];
        BotHub.log(now() + " ERROR " + msg);
        journal.event(today(), "ERROR", msg);
        long t = System.currentTimeMillis();
        if (t - lastEngineErrorAlert > 60_000) {
            lastEngineErrorAlert = t;
            telegram.send(msg);
            alerts.show("Bot hit an internal error (see Log). It keeps running and managing exits.");
        }
    }

    static String now() { return LocalTime.now(MarketClock.IST).format(HMS); }
    static String today() { return LocalDate.now(MarketClock.IST).toString(); }
    static String money(double v) {
        if (Double.isNaN(v)) return "-";
        if (Double.isInfinite(v)) return "unlimited";
        return String.format(Locale.US, "%,.0f", v);
    }
}
