package com.krish.niftyregimebot;

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

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Broker;
import com.krish.niftyregimebot.core.BrokerPosition;
import com.krish.niftyregimebot.core.Candle;
import com.krish.niftyregimebot.core.Charges;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.Engine;
import com.krish.niftyregimebot.core.FeedParser;
import com.krish.niftyregimebot.core.Json;
import com.krish.niftyregimebot.core.Leg;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.OptionQuote;
import com.krish.niftyregimebot.core.PaperBroker;
import com.krish.niftyregimebot.core.Position;
import com.krish.niftyregimebot.core.TradeRecord;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * Runs the whole bot on the phone as a foreground service: Dhan data in, engine decisions,
 * orders (paper or live), exits, journal and alerts. Keeps running with the screen off.
 *
 * Threads: "engine" (all Engine calls), "io" (Dhan REST calls), OkHttp threads (feed).
 */
public final class BotService extends Service {
    public static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_TRADE = "trade",
            ACTION_PAUSE = "pause", ACTION_EMERGENCY = "emergency", ACTION_ACK = "ack", ACTION_RELOAD = "reload";

    /** True while the service is running in this process (used by the morning reminder). */
    public static volatile boolean isRunning;

    private static final int NOTIF_STATUS = 1;
    private static final String CH_STATUS = "status", CH_ALERTS = "alerts";
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(MarketClock.IST);

    private HandlerThread engineThread;
    private Handler eh;
    private ExecutorService io;
    private PowerManager.WakeLock wake;
    private OkHttpClient http;

    private Settings settings;
    private Journal journal;
    private Telegram telegram;
    private Config cfg;
    private Engine engine;
    private DhanApi api;
    private DhanFeed feed;
    private final LiveRef liveRef = new LiveRef();
    private boolean liveMode;

    private volatile boolean running;
    private volatile List<String> expiries = new ArrayList<>();
    private double dhanFunds = -1;
    private boolean feedConnected;
    private String feedNote = "not connected";
    private long lastReconcile, lastChainMillis, lastExpiryListMillis, lastNotifMillis;
    private int chainTurn;
    private int alertId = 100;
    private String lastError = "";
    private long startedMillis;
    private String vixId = "21";

    // v1.1 data fixes
    private volatile int ioGen;                      // bumps to stop the current io loop (reconnect)
    private volatile long pendingBarStart;           // engine waits for Dhan's copy of this bar
    private volatile Set<String> lastWanted = new java.util.LinkedHashSet<>();
    private volatile boolean tokenExpired;
    private volatile long lastVixTickMillis;
    private volatile boolean vixHistoryOk;
    private long lastBarSync, lastFullBarSync, lastTokenWarn, lastWakeRenew, renewFailedMillis;
    /** Lets the engine be built before Dhan is connected; live orders go through once it is. */
    private static final class LiveRef implements Broker {
        volatile DhanLiveBroker target;
        public OrderUpdate submit(OrderRequest r) throws IOException { return need().submit(r); }
        public OrderUpdate status(String id) throws IOException { return need().status(id); }
        public void cancel(String id) throws IOException { need().cancel(id); }
        public boolean isLive() { return true; }
        private DhanLiveBroker need() throws IOException {
            if (target == null) throw new IOException("not connected to Dhan yet");
            return target;
        }
    }

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
            if (running) reconnect("new settings / token");
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
        startedMillis = System.currentTimeMillis();
        createChannels();
        Notification n = statusNotification("Starting…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_STATUS, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NiftyRegimeBot:engine");
        wake.setReferenceCounted(false);
        wake.acquire(12 * 60 * 60 * 1000L);  // renewed every 30 min while running; released on stop
        lastWakeRenew = System.currentTimeMillis();
        isRunning = true;

        settings = new Settings(this);
        journal = new Journal(this);
        cfg = settings.config();
        MarketClock.setExtraHolidays(settings.extraHolidayList());
        liveMode = settings.live();
        vixId = settings.vixId();
        http = new OkHttpClient();
        telegram = new Telegram(http, settings.telegramToken(), settings.telegramChat());

        engineThread = new HandlerThread("engine");
        engineThread.start();
        eh = new Handler(engineThread.getLooper());
        io = Executors.newSingleThreadExecutor();

        post(() -> {
            Broker broker = liveMode ? liveRef
                    : new PaperBroker(id -> engine.quote(id), cfg.paperSlippage, cfg.depthMaxAgeMillis, System::currentTimeMillis);
            engine = new Engine(cfg, new Charges(), broker, listener);
            restoreDayStats();
            restorePosition();
            engine.onTimer(System.currentTimeMillis());
            engine.setAvailableFunds(cfg.capital + engine.day().realizedPnl);
            engine.setState(BotState.STARTING, "connecting to Dhan");
            post(tick);
        });
        io.execute(this::connect);
    }

    private void end() {
        running = false;
        isRunning = false;
        ioGen++;
        Reminder.schedule(this);
        if (feed != null) feed.stop();
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
        if (settings.tokenMadeMillis() == 0) settings.saveToken(token, System.currentTimeMillis());  // age unknown: count from now
        double ageH = (System.currentTimeMillis() - settings.tokenMadeMillis()) / 3_600_000.0;
        if (ageH >= 24) warn(String.format(Locale.US, "Dhan token is %.0f hours old — it has probably expired. Paste a new one if connecting fails.", ageH));
        api = new DhanApi(http, id, token);
        if (liveMode) liveRef.target = new DhanLiveBroker(api, cfg.holding == Config.Holding.WEEKLY);
        tokenExpired = false;
        try {
            DhanApi.Funds f = api.funds();
            dhanFunds = f.available;
            info("Connected to Dhan. Available funds ₹" + money(f.available)
                    + (liveMode ? "" : " (paper mode uses your capital setting)"));

            List<BrokerPosition> pos = api.foPositions();
            lastReconcile = System.currentTimeMillis();
            post(() -> engine.reconcile(pos));

            refreshExpiries();
            LocalDate today = LocalDate.now(MarketClock.IST);

            int barMin = cfg.barMinutes();
            int backDays = cfg.holding == Config.Holding.WEEKLY ? 45 : 7;
            try {
                List<Candle> hist = api.niftyBars(barMin, today.minusDays(backDays), today);
                post(() -> engine.seedBars(hist));
            } catch (Exception e) {
                warn("Could not load earlier NIFTY bars (" + e.getMessage() + "). The regime will form from live data (slow).");
            }
            try {
                List<Double> vix = api.indexDailyCloses(vixId, today.minusDays(400), today.plusDays(1));
                vixHistoryOk = vix.size() >= 60;
                post(() -> engine.setVixHistory(vix));
            } catch (Exception e) {
                vixHistoryOk = false;
                warn("Could not load India VIX history (id " + vixId + "): " + e.getMessage() + ". IV level will use ATM IV.");
            }

            refreshChains(true);
            if (!MarketClock.tradingDay(today)) info("Market is closed today (weekend or NSE holiday). The bot will wait.");
            if (MarketClock.holidayListOutdated(today)) warn("NSE holiday list has no dates for " + today.getYear() + ". Add them in Settings.");
        } catch (DhanApi.DhanException e) {
            fail(e.code == 401 || e.code == 403 ? "Dhan rejected the token (expired or wrong). Paste a fresh token in Settings." : e.getMessage());
            return;
        } catch (Exception e) {
            fail("Could not reach Dhan: " + e.getMessage());
            return;
        }

        feed = new DhanFeed(http, id, token, feedCallback, vixId);
        feed.start();
        if (!lastWanted.isEmpty()) feed.setOptions(lastWanted);
        post(() -> {
            if (engine.state() == BotState.STARTING) engine.setState(BotState.READY, "connected; tap Start trading");
        });
        loopIo(ioGen);
    }

    /** Drop the Dhan connection and connect again (new token or settings). The open position stays managed. */
    private void reconnect(String why) {
        ioGen++;
        if (feed != null) feed.stop();
        feedConnected = false;
        feedNote = "reconnecting";
        settings = new Settings(this);
        post(() -> {
            if (engine.state() == BotState.ERROR && (tokenExpired || lastError.contains("token") || lastError.contains("Dhan")))
                engine.setState(BotState.STARTING, "reconnecting: " + why);
            listener.onLog("INFO", "Reconnecting to Dhan (" + why + ")");
        });
        lastError = "";
        io.execute(this::connect);
    }

    private void refreshExpiries() throws IOException {
        List<String> list = api.expiries();
        expiries = list;
        lastExpiryListMillis = System.currentTimeMillis();
        int minDays = cfg.holding == Config.Holding.WEEKLY ? cfg.minDaysToExpiryWeekly : cfg.minDaysToExpiryIntraday;
        String e = MarketClock.chooseExpiry(list, LocalDate.now(MarketClock.IST), minDays);
        if (e.isEmpty()) { warn("No suitable NIFTY expiry found"); return; }
        chosenExpiry = e;
        post(() -> engine.setTradeExpiry(e));
    }

    private void loopIo(int gen) {
        io.execute(() -> {
            while (running && gen == ioGen && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                    long now = System.currentTimeMillis();
                    boolean open = MarketClock.sessionOpen(now);
                    if (now - lastExpiryListMillis > 30 * 60_000L) refreshExpiries();
                    if (now - lastChainMillis > (open ? 12_000 : 120_000)) refreshChains(false);
                    syncBars(now, open);
                    // Paper mode does not need Dhan's positions or funds every minute (checked once at start).
                    if (liveMode && now - lastReconcile > 60_000) {
                        lastReconcile = now;
                        List<BrokerPosition> pos = api.foPositions();
                        post(() -> engine.reconcile(pos));
                        dhanFunds = api.funds().available;
                    }
                    maybeRenewToken(now);
                } catch (InterruptedException e) {
                    return;
                } catch (DhanApi.DhanException e) {
                    if (e.code == 401 || e.code == 403) {
                        tokenExpired = true;
                        lastError = "Dhan token expired";
                        post(() -> {
                            engine.pause("token expired");
                            engine.setState(BotState.ERROR, "Dhan token expired — paste a new one in Settings and tap Save");
                            Position p = engine.position();
                            listener.onAlert("Dhan token expired" + (p != null ? " while holding " + p.type.title
                                    + ". The bot cannot see prices or exit until you paste a new token in Settings and tap Save." : ". Paste a new one in Settings."));
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

    /**
     * Ask Dhan for its own copy of recent bars: quickly after each bar closes (the engine waits
     * up to a minute for it), and every 10 minutes in market hours to repair any gaps.
     */
    private void syncBars(long now, boolean open) throws IOException {
        boolean wanted = pendingBarStart > 0 && now - lastBarSync >= 5_000;
        boolean periodic = open && now - lastFullBarSync >= 10 * 60_000L;
        if (!wanted && !periodic) return;
        lastBarSync = now;
        if (periodic) lastFullBarSync = now;
        LocalDate today = LocalDate.now(MarketClock.IST);
        try {
            List<Candle> bars = api.niftyBars(cfg.barMinutes(), MarketClock.previousTradingDay(today), today);
            post(() -> engine.onDhanBars(bars));
        } catch (DhanApi.DhanException e) {
            if (e.code == 401 || e.code == 403) throw e;
            if (periodic) warn("Could not fetch Dhan's bars: " + e.getMessage());
        } catch (IOException e) {
            if (periodic) warn("Could not fetch Dhan's bars: " + e.getMessage());
        }
    }

    /**
     * Dhan tokens last 24 hours. Between 20 and 24 hours old the bot swaps it for a fresh one
     * (Dhan's RenewToken; works for tokens made on Dhan Web) when no order is working.
     */
    private void maybeRenewToken(long now) throws IOException {
        long made = settings.tokenMadeMillis();
        if (made == 0) return;
        double ageH = (now - made) / 3_600_000.0;
        if (ageH < 20) return;
        if (ageH < 24 && settings.autoRenewToken() && pendingJob.isEmpty() && now - renewFailedMillis > 30 * 60_000L) {
            DhanApi.Renewed r;
            try {
                r = api.renewToken();
            } catch (IOException e) {
                renewFailedMillis = now;
                post(() -> listener.onAlert("Could not renew the Dhan token (" + e.getMessage() + "). Paste a new token in Settings within "
                        + String.format(Locale.US, "%.0f", Math.max(0, 24 - ageH)) + " hours."));
                return;
            }
            if (r.accessToken.length() < 20) {
                renewFailedMillis = now;
                post(() -> listener.onAlert("Dhan's token renewal answered without a token. Paste a new token in Settings now."));
                return;
            }
            settings.saveToken(r.accessToken, now);
            String id = settings.clientId();
            api = new DhanApi(http, id, r.accessToken);
            if (liveMode) liveRef.target = new DhanLiveBroker(api, cfg.holding == Config.Holding.WEEKLY);
            if (feed != null) feed.stop();
            feed = new DhanFeed(http, id, r.accessToken, feedCallback, vixId);
            feed.start();
            if (!lastWanted.isEmpty()) feed.setOptions(lastWanted);
            info("Dhan token renewed for 24 hours" + (r.expiryTime.isEmpty() ? "" : " (expires " + r.expiryTime + ")"));
            return;
        }
        if (now - lastTokenWarn > 60 * 60_000L) {
            lastTokenWarn = now;
            post(() -> listener.onAlert(String.format(Locale.US, "Dhan token is %.0f hours old and expires at 24 hours. Paste a new one in Settings.", ageH)));
        }
    }

    private volatile String pendingJob = "";

    /** Refresh one needed expiry per call (round robin), or all of them when {@code all}. */
    private void refreshChains(boolean all) throws Exception {
        lastChainMillis = System.currentTimeMillis();
        List<String> need = new ArrayList<>(engineNeededExpiries());
        if (need.isEmpty()) return;
        List<String> todo = new ArrayList<>();
        if (all) todo.addAll(need);
        else todo.add(need.get(chainTurn++ % need.size()));
        for (String exp : todo) {
            DhanApi.Chain c = api.optionChain(exp);
            long now = System.currentTimeMillis();
            post(() -> {
                for (DhanApi.ChainRow r : c.rows)
                    engine.upsertContract(r.securityId, r.call ? OptionQuote.Right.CE : OptionQuote.Right.PE, r.strike,
                            c.expiry, r.delta, r.iv, r.oi, r.ltp, r.bid, r.ask, now);
                if (engine.spot() <= 0 && c.spot > 0) engine.onSpot(c.spot, now);
                Set<String> want = engine.wantedContracts(900);
                lastWanted = want;
                if (feed != null) feed.setOptions(want);
                subscribed = want.size();
            });
        }
    }

    private volatile Set<String> lastNeeded = new java.util.LinkedHashSet<>();
    private int subscribed;

    /** Read from the io thread: the engine copies its needed expiries here each tick. */
    private Set<String> engineNeededExpiries() {
        Set<String> s = new java.util.LinkedHashSet<>(lastNeeded);
        if (!chosenExpiry.isEmpty()) s.add(chosenExpiry);
        return s;
    }

    private volatile String chosenExpiry = "";

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
            if (segment == FeedParser.SEG_IDX_I) {
                if (DhanApi.NIFTY_ID.equals(securityId)) { if (ltp > 0) post(() -> engine.onSpot(ltp, now)); }
                else if (securityId.equals(vixId)) { if (ltp > 0) { lastVixTickMillis = now; post(() -> engine.onVix(ltp)); } }
            } else if (segment == FeedParser.SEG_NSE_FNO) {
                post(() -> engine.onOptionTick(securityId, ltp, bid, ask, now));
            }
        }

        @Override public void onDisconnect(int reasonCode) {
            warn("Dhan feed disconnect code " + reasonCode + " (805 = too many connections; 806/807/808 = token/plan/auth problem)");
        }

        @Override public void onParseError(String message) { warn("Feed: " + message); }

        @Override public void onDepth(int segment, String securityId, double[] bidPx, int[] bidQty, double[] askPx, int[] askQty) {
            if (segment != FeedParser.SEG_NSE_FNO) return;
            long now = System.currentTimeMillis();
            post(() -> engine.onDepth(securityId, bidPx, bidQty, askPx, askQty, now));
        }
    };

    // ------------------------------------------------------------------ engine thread

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            try {
                engine.onTimer(now);
                engine.setAvailableFunds(liveMode && dhanFunds >= 0 ? dhanFunds : cfg.capital + engine.day().realizedPnl);
                lastNeeded = new java.util.LinkedHashSet<>(engine.neededExpiries());
                pendingBarStart = engine.pendingBarStart();
                pendingJob = engine.jobText();
                if (now - lastWakeRenew > 30 * 60_000L && wake != null) { wake.acquire(12 * 60 * 60 * 1000L); lastWakeRenew = now; }
                publish();
                if (now - lastNotifMillis > 5_000) { lastNotifMillis = now; updateStatusNotification(); }
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
            showAlert(message);
        }
    };

    private void restoreDayStats() {
        String s = settings.dayStatsJson();
        if (s.isEmpty()) return;
        try { engine.restoreDay(DayStats.fromMap(Json.obj(Json.parse(s)))); } catch (IllegalArgumentException ignored) {}
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
        BotHub.Snapshot s = new BotHub.Snapshot();
        if (engine != null) {
            s.state = engine.state();
            s.spot = engine.spot();
            s.spotMillis = engine.lastSpotMillis();
            s.day = DayStats.fromMap(engine.day().toMap());
            s.job = engine.jobText();
            s.expiry = engine.tradeExpiry();
            Engine.Decision d = engine.lastDecision();
            if (engine.lastReading() != null) {
                s.regime = String.valueOf(engine.lastReading().confirmed);
                s.regimeWhy = engine.lastReading().text();
            }
            if (d != null) {
                s.ivText = d.iv == null ? "-" : d.iv.text();
                s.vix = d.iv == null ? Double.NaN : d.iv.vix;
                s.pick = d.pick.title;
                s.lastAction = d.action;
            }
            Position p = engine.position();
            if (p != null) {
                BotHub.PositionView v = new BotHub.PositionView();
                v.title = p.type.title;
                v.expiry = p.expiry;
                v.holding = p.holding.name();
                v.qty = p.qty;
                v.lots = p.qty / Math.max(1, cfg.lotSize);
                v.netPremium = p.netPremium;
                v.openPnl = p.openPnl;
                v.target = p.targetRs;
                v.stop = p.stopRs;
                v.maxLoss = p.maxLossRs();
                v.maxProfit = p.maxProfitRs();
                v.entryMillis = p.entryMillis;
                v.stale = p.stale;
                for (Leg l : p.legs) {
                    BotHub.LegView lv = new BotHub.LegView();
                    lv.label = l.label();
                    lv.buy = l.buy;
                    lv.entry = l.price;
                    OptionQuote q = engine.quote(l.securityId);
                    lv.now = q == null ? 0 : (l.buy ? q.bid : q.ask);
                    v.legs.add(lv);
                }
                s.position = v;
            }
            int fresh = 0;
            for (OptionQuote q : engine.quotes()) if (System.currentTimeMillis() - q.updatedMillis < 15_000) fresh++;
            s.contracts = fresh;
        }
        s.live = liveMode;
        s.holding = cfg == null ? "-" : cfg.holding.name();
        s.feedConnected = feedConnected;
        s.feedNote = feedNote;
        s.dhanFunds = dhanFunds;
        s.lastError = lastError;
        s.startedMillis = startedMillis;
        long now = System.currentTimeMillis();
        long made = settings == null ? 0 : settings.tokenMadeMillis();
        s.tokenAgeHours = made == 0 ? -1 : (now - made) / 3_600_000.0;
        s.autoRenew = settings != null && settings.autoRenewToken();
        if (engine != null) { s.barsFixed = engine.barsFixed(); s.waitingForDhanBar = engine.pendingBarStart() > 0; }
        LocalDate today = LocalDate.now(MarketClock.IST);
        if (!MarketClock.tradingDay(today)) s.warnings.add("Market closed today (weekend or NSE holiday)");
        if (MarketClock.holidayListOutdated(today)) s.warnings.add("No NSE holidays known for " + today.getYear() + " — add them in Settings");
        if (engine != null && !engine.ivGauge().hasVixHistory())
            s.warnings.add("India VIX history not loaded (VIX ID " + vixId + "?) — IV level uses ATM IV, which is rougher");
        if (feedConnected && MarketClock.sessionOpen(now) && now - startedMillis > 120_000 && now - lastVixTickMillis > 120_000)
            s.warnings.add("No live India VIX ticks for 2+ min — check the VIX ID in Settings");
        if (s.tokenAgeHours >= 20) s.warnings.add(String.format(Locale.US, "Dhan token is %.0f h old (expires at 24 h)%s",
                s.tokenAgeHours, s.autoRenew && s.tokenAgeHours < 24 ? " — auto-renew will try" : " — paste a new one"));
        if (tokenExpired) s.warnings.add("Dhan token expired — paste a new one in Settings and tap Save");
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
                .setContentTitle(getString(R.string.app_name) + (liveMode ? " · LIVE" : " · PAPER"))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void updateStatusNotification() {
        if (engine == null) return;
        Position p = engine.position();
        String text = String.format(Locale.US, "%s · NIFTY %.1f · day ₹%s%s", engine.state(), engine.spot(),
                money(engine.day().realizedPnl), p != null ? " · " + p.type.title + " ₹" + money(p.openPnl) : "");
        getSystemService(NotificationManager.class).notify(NOTIF_STATUS, statusNotification(text));
    }

    private void showAlert(String text) {
        Notification n = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(alertId++, n);
        if (alertId > 150) alertId = 100;
    }

    // ------------------------------------------------------------------ helpers

    /** Run on the engine thread; an unexpected error is logged instead of crashing (exits keep running). */
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

    static String now() { return LocalTime.now(MarketClock.IST).format(HMS); }
    static String today() { return LocalDate.now(MarketClock.IST).toString(); }
    static String money(double v) {
        if (Double.isNaN(v)) return "-";
        if (Double.isInfinite(v)) return "unlimited";
        return String.format(Locale.US, "%,.0f", v);
    }
}
