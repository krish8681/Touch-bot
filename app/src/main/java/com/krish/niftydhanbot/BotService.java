package com.krish.niftydhanbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import com.krish.niftydhanbot.core.BotConfig;
import com.krish.niftydhanbot.core.BotState;
import com.krish.niftydhanbot.core.Charges;
import com.krish.niftydhanbot.core.DayStats;
import com.krish.niftydhanbot.core.Engine;
import com.krish.niftydhanbot.core.FeedParser;
import com.krish.niftydhanbot.core.Json;
import com.krish.niftydhanbot.core.OptionQuote;
import com.krish.niftydhanbot.core.PaperBroker;
import com.krish.niftydhanbot.core.Position;
import com.krish.niftydhanbot.core.Signal;
import com.krish.niftydhanbot.core.TradeRecord;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * Runs the whole bot on the phone as a foreground service: Dhan data in, engine decisions,
 * paper orders, exits, journal and alerts. Keeps running when the screen is closed.
 *
 * Threads: "engine" (all Engine calls), "io" (Dhan REST calls), OkHttp threads (feed).
 */
public final class BotService extends Service {
    public static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_TRADE = "trade",
            ACTION_PAUSE = "pause", ACTION_EMERGENCY = "emergency", ACTION_ACK = "ack";

    private static final int NOTIF_STATUS = 1;
    private static final String CH_STATUS = "status", CH_ALERTS = "alerts";
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private HandlerThread engineThread;
    private Handler eh;
    private ExecutorService io;
    private PowerManager.WakeLock wake;
    private OkHttpClient http;

    private Settings settings;
    private Journal journal;
    private Telegram telegram;
    private BotConfig cfg;
    private Engine engine;
    private DhanApi api;
    private DhanFeed feed;

    private volatile boolean running;
    private String expiry = "";
    private double dhanFunds = -1;
    private boolean feedConnected;
    private String feedNote = "not connected";
    private long lastReconcile, lastChainMillis, lastNotifMillis;
    private int alertId = 100;
    private String lastError = "";
    private long startedMillis;

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
        if (!running) {
            if (!ACTION_STOP.equals(a)) {
                // Commands while the bot is off: ignore, but satisfy any pending foreground start.
                BotHub.log(now() + " Bot is off — tap Start bot first");
            }
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
        startedMillis = System.currentTimeMillis();
        createChannels();
        Notification n = statusNotification("Starting…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_STATUS, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NiftyDhanBot:engine");
        wake.acquire(8 * 60 * 60 * 1000L);  // released on stop; 8h safety cap

        settings = new Settings(this);
        journal = new Journal(this);
        cfg = settings.config();
        http = new OkHttpClient();
        telegram = new Telegram(http, settings.telegramToken(), settings.telegramChat());

        engineThread = new HandlerThread("engine");
        engineThread.start();
        eh = new Handler(engineThread.getLooper());
        io = Executors.newSingleThreadExecutor();

        post(() -> {
            engine = new Engine(cfg, new Charges(), new PaperBroker(id -> engine.quote(id), cfg.paperSlippagePts), listener);
            restoreDayStats();
            engine.onTimer(System.currentTimeMillis());
            engine.setAvailableFunds(cfg.capital + engine.day().realizedPnl);
            engine.setState(BotState.STARTING, "connecting to Dhan");
            post(tick);
        });
        io.execute(this::connect);
    }

    private void end() {
        running = false;
        if (feed != null) feed.stop();
        if (eh != null) {
            eh.removeCallbacksAndMessages(null);
            post(() -> {
                if (engine != null) {
                    if (engine.position() != null) listener.onAlert("Bot stopped while holding " + engine.position().label + " (paper). It is no longer managed.");
                    engine.setState(BotState.OFF, "stopped by you");
                }
                publish();
                engineThread.quitSafely();
            });
        }
        if (io != null) io.shutdownNow();
        if (telegram != null) telegram.shutdown();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running) end();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ connect & data

    private void connect() {
        String id = settings.clientId(), token = settings.token();
        if (id.isEmpty() || token.isEmpty()) {
            fail("Add your Dhan client ID and access token in Settings");
            return;
        }
        api = new DhanApi(http, id, token);
        try {
            DhanApi.Funds f = api.funds();
            dhanFunds = f.available;
            info("Connected to Dhan. Available funds ₹" + money(f.available) + " (shown only; paper mode uses your capital setting)");

            List<Engine.BrokerPosition> pos = api.foPositions();
            lastReconcile = System.currentTimeMillis();
            post(() -> engine.reconcile(pos));

            expiry = pickExpiry(api.expiries());
            if (expiry.isEmpty()) { fail("No NIFTY expiry found from Dhan"); return; }
            info("Using expiry " + expiry);

            LocalDate today = LocalDate.now(Engine.IST);
            try {
                List<com.krish.niftydhanbot.core.Candle> hist = api.niftyIntraday(today);
                post(() -> engine.seedCandles(hist));
            } catch (Exception e) {
                warn("Could not load earlier candles (" + e.getMessage() + "). The bot will warm up from live ticks (~27 min).");
            }

            refreshChain();
        } catch (DhanApi.DhanException e) {
            fail(e.code == 401 || e.code == 403 ? "Dhan rejected the token (expired or wrong). Paste a fresh token in Settings." : e.getMessage());
            return;
        } catch (Exception e) {
            fail("Could not reach Dhan: " + e.getMessage());
            return;
        }

        feed = new DhanFeed(http, id, token, feedCallback);
        feed.start();
        post(() -> {
            if (engine.state() == BotState.STARTING) engine.setState(BotState.READY, "connected; tap Start trading");
        });
        scheduleIo();
    }

    private void scheduleIo() {
        // Background refresh loop on the io thread.
        io.execute(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                    long now = System.currentTimeMillis();
                    if (now - lastChainMillis > 15_000) refreshChain();
                    if (now - lastReconcile > 60_000) {
                        lastReconcile = now;
                        List<Engine.BrokerPosition> pos = api.foPositions();
                        post(() -> engine.reconcile(pos));
                        dhanFunds = api.funds().available;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (DhanApi.DhanException e) {
                    if (e.code == 401 || e.code == 403) {
                        lastError = "Dhan token expired";
                        post(() -> {
                            engine.pause("token expired");
                            engine.setState(BotState.ERROR, "Dhan token expired — paste a new one, then restart the bot");
                            listener.onAlert("Dhan token expired. Exits now use the last known prices only.");
                        });
                        return;
                    }
                    warn("Dhan: " + e.getMessage());
                } catch (Exception e) {
                    warn("Refresh failed: " + e.getMessage());
                }
            }
        });
    }

    private void refreshChain() throws Exception {
        lastChainMillis = System.currentTimeMillis();
        DhanApi.Chain c = api.optionChain(expiry);
        long now = System.currentTimeMillis();
        post(() -> {
            double spot = engine.spot() > 0 ? engine.spot() : c.spot;
            Set<String> want = new LinkedHashSet<>();
            for (DhanApi.ChainRow r : c.rows) {
                if (Math.abs(r.strike - spot) > 300) continue;  // ±6 strikes
                engine.upsertContract(r.securityId, r.call ? Signal.Side.CE : Signal.Side.PE, r.strike,
                        r.delta, r.iv, r.oi, r.ltp, r.bid, r.ask, now);
                want.add(r.securityId);
            }
            if (engine.position() != null) want.add(engine.position().securityId);
            if (feed != null) feed.setOptions(want);
            subscribedCount = want.size();
        });
    }

    private int subscribedCount;

    private String pickExpiry(List<String> list) {
        List<String> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        String today = LocalDate.now(Engine.IST).toString();
        for (String e : sorted) {
            if (e.compareTo(today) < 0) continue;
            if (cfg.avoidExpiryDayContract && e.equals(today)) continue;
            return e;
        }
        return "";
    }

    private final DhanFeed.Callback feedCallback = new DhanFeed.Callback() {
        @Override public void onConnected() {
            feedConnected = true;
            feedNote = "live";
            info("Live feed connected");
        }

        @Override public void onClosed(String why) {
            feedConnected = false;
            feedNote = why;
            warn("Live feed lost: " + why + " — reconnecting");
        }

        @Override public void onPrice(int segment, String securityId, double ltp, double bid, double ask, double oi) {
            long now = System.currentTimeMillis();
            if (segment == FeedParser.SEG_IDX_I && DhanApi.NIFTY_ID.equals(securityId)) {
                if (ltp > 0) post(() -> engine.onSpot(ltp, now));
            } else if (segment == FeedParser.SEG_NSE_FNO) {
                post(() -> engine.onOptionTick(securityId, ltp, bid, ask, now));
            }
        }

        @Override public void onDisconnect(int reasonCode) {
            warn("Dhan feed disconnect code " + reasonCode + " (805 = too many connections; 806/807/808 = token/plan/auth problem)");
        }

        @Override public void onParseError(String message) { warn("Feed: " + message); }
    };

    // ------------------------------------------------------------------ engine thread

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            try {
                engine.onTimer(now);
                engine.setAvailableFunds(cfg.capital + engine.day().realizedPnl);
                publish();
                if (now - lastNotifMillis > 5_000) { lastNotifMillis = now; updateStatusNotification(); }
            } catch (RuntimeException e) {
                engineError(e);
            }
            eh.postDelayed(this, 1_000);  // re-arms even after an error (see post())
        }
    };

    private void startTrading() {
        if (!settings.lotSizeConfirmed()) {
            listener.onAlert("Confirm the NIFTY lot size in Settings before trading (current setting: " + cfg.lotSize + ")");
            return;
        }
        if (engine.startTrading()) listener.onAlert("Paper trading started");
    }

    private final Engine.Listener listener = new Engine.Listener() {
        @Override public void onLog(String level, String message) {
            BotHub.log(now() + " " + (level.equals("INFO") ? "" : level + " ") + message);
            journal.event(today(), level, message);
        }

        @Override public void onSignal(Signal s) {
            BotHub.signal(s);
            journal.event(today(), "SIGNAL", s.side + " " + s.regime + " " + s.strength + " — " + s.reasonText());
        }

        @Override public void onStateChanged(BotState s, String why) { publish(); }

        @Override public void onPositionChanged(Position p) { publish(); }

        @Override public void onTradeClosed(TradeRecord t) { journal.trade(t); }

        @Override public void onStatsChanged(DayStats d) { settings.saveDayStats(Json.write(d.toMap())); }

        @Override public void onAlert(String message) {
            BotHub.log(now() + " ⚠ " + message);
            journal.event(today(), "ALERT", message);
            telegram.send(message);
            showAlert(message);
        }
    };

    private void restoreDayStats() {
        String s = settings.dayStatsJson();
        if (s.isEmpty()) return;
        try {
            engine.restoreDay(DayStats.fromMap(Json.obj(Json.parse(s))));
        } catch (IllegalArgumentException ignored) {}
    }

    private void publish() {
        BotHub.Snapshot s = new BotHub.Snapshot();
        if (engine != null) {
            s.state = engine.state();
            s.spot = engine.spot();
            s.spotMillis = engine.lastSpotMillis();
            s.day = DayStats.fromMap(engine.day().toMap());
            s.pendingOrder = engine.hasPendingOrder();
            Position p = engine.position();
            if (p != null) {
                BotHub.PositionView v = new BotHub.PositionView();
                v.label = p.label; v.qty = p.qty; v.entry = p.entryPrice; v.stop = p.stop; v.target = p.target;
                v.bid = p.lastBid; v.openPnl = p.openPnl(); v.rMultiple = p.rMultiple();
                v.entryMillis = p.entryMillis; v.stopReason = p.stopReason;
                s.position = v;
            }
            int live = 0;
            for (OptionQuote q : engine.quotes()) if (System.currentTimeMillis() - q.updatedMillis < 10_000) live++;
            s.contracts = live;
        }
        s.feedConnected = feedConnected;
        s.feedNote = feedNote;
        s.dhanFunds = dhanFunds;
        s.expiry = expiry;
        s.lastReconcileMillis = lastReconcile;
        s.lastError = lastError;
        s.startedMillis = startedMillis;
        BotHub.set(s);
    }

    // ------------------------------------------------------------------ notifications

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Bot status", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Trade alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification statusNotification(String text) {
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name) + " · PAPER")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void updateStatusNotification() {
        if (engine == null) return;
        String text = String.format(Locale.US, "%s · NIFTY %.1f · day ₹%s%s", engine.state(), engine.spot(),
                money(engine.day().realizedPnl), engine.position() != null ? " · holding " + engine.position().label : "");
        getSystemService(NotificationManager.class).notify(NOTIF_STATUS, statusNotification(text));
    }

    private void showAlert(String text) {
        Notification n = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Nifty Dhan Bot")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(alertId++, n);
        if (alertId > 150) alertId = 100;
    }

    // ------------------------------------------------------------------ helpers

    /** Run on the engine thread; an unexpected error is logged instead of crashing the app (exits keep running). */
    private void post(Runnable r) {
        if (eh == null) return;
        eh.post(() -> {
            try { r.run(); } catch (RuntimeException e) { engineError(e); }
        });
    }

    private long lastEngineErrorAlert;

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
            showAlert("Bot hit an internal error (see Log). It keeps running and managing exits.");
        }
    }

    private void fail(String msg) {
        lastError = msg;
        post(() -> {
            engine.setState(BotState.ERROR, msg);
            listener.onAlert(msg);
        });
    }

    private void info(String msg) { post(() -> listener.onLog("INFO", msg)); }
    private void warn(String msg) { post(() -> listener.onLog("WARN", msg)); }

    static String now() { return LocalTime.now(Engine.IST).format(HMS); }
    static String today() { return LocalDate.now(Engine.IST).toString(); }
    static String money(double v) { return String.format(Locale.US, "%,.0f", v); }
}
