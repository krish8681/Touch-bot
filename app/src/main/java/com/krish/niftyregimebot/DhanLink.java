package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Broker;
import com.krish.niftyregimebot.core.BrokerPosition;
import com.krish.niftyregimebot.core.Candle;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.Engine;
import com.krish.niftyregimebot.core.FeedParser;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.OptionQuote;
import com.krish.niftyregimebot.core.Position;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * Everything that talks to Dhan: connect, warm-up data, the live feed, option chains, Dhan's
 * own bars, account checks and token renewal. Runs on its own "io" thread; every change to the
 * engine is posted to the engine thread through {@link Host#onEngine}.
 */
final class DhanLink {

    /** What the link needs from the service. */
    interface Host {
        /** Run on the engine thread with the engine. */
        void onEngine(EngineTask task);
        void info(String msg);
        void warn(String msg);
        void alert(String msg);
        void fail(String msg);
    }

    interface EngineTask { void run(Engine e); }

    /** Lets the engine be built before Dhan is connected; live orders go through once it is. */
    static final class LiveRef implements Broker {
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

    private final OkHttpClient http;
    private final Config cfg;
    private final boolean live;
    private final String vixId;
    private final Host host;
    private final LiveRef liveRef = new LiveRef();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Settings settings;
    private volatile boolean running = true;
    private volatile int gen;                  // bumps to stop the current io loop (reconnect)

    private DhanApi api;
    private DhanFeed feed;

    // read by the screen / self-test
    volatile double funds = -1;
    volatile boolean feedConnected;
    volatile String feedNote = "not connected";
    volatile String lastError = "";
    volatile boolean tokenExpired;
    volatile long lastVixTick;

    // copied from the engine every second (see mirror)
    private volatile Set<String> needed = new LinkedHashSet<>();
    private volatile Set<String> wanted = new LinkedHashSet<>();
    private volatile long pendingBar;
    private volatile String job = "";
    private volatile String chosenExpiry = "";

    private long lastReconcile, lastChain, lastExpiryList, lastBarSync, lastFullBarSync, lastTokenWarn, renewFailed;
    private int chainTurn;

    DhanLink(OkHttpClient http, Settings settings, Config cfg, boolean live, Host host) {
        this.http = http;
        this.settings = settings;
        this.cfg = cfg;
        this.live = live;
        this.vixId = settings.vixId();
        this.host = host;
    }

    Broker liveBroker() { return liveRef; }
    String vixId() { return vixId; }

    void start() { io.execute(this::connect); }

    void stop() {
        running = false;
        gen++;
        if (feed != null) feed.stop();
        io.shutdownNow();
    }

    /** Called on the engine thread every second. */
    void mirror(Engine e) {
        needed = new LinkedHashSet<>(e.neededExpiries());
        pendingBar = e.pendingBarStart();
        job = e.jobText();
    }

    /** Drop the Dhan connection and connect again (new token or settings). The open position stays managed. */
    void reconnect(Settings fresh, String why) {
        gen++;
        if (feed != null) feed.stop();
        feedConnected = false;
        feedNote = "reconnecting";
        settings = fresh;
        String err = lastError;
        boolean expired = tokenExpired;
        host.onEngine(e -> {
            if (e.state() == BotState.ERROR && (expired || err.contains("token") || err.contains("Dhan")))
                e.setState(BotState.STARTING, "reconnecting: " + why);
        });
        host.info("Reconnecting to Dhan (" + why + ")");
        lastError = "";
        io.execute(this::connect);
    }

    // ------------------------------------------------------------------ connect

    private void connect() {
        String id = settings.clientId(), token = settings.token();
        if (id.isEmpty() || token.isEmpty()) { failWith("Add your Dhan client ID and access token in Settings"); return; }
        if (settings.tokenMadeMillis() == 0) settings.saveToken(token, System.currentTimeMillis());  // age unknown: count from now
        double ageH = (System.currentTimeMillis() - settings.tokenMadeMillis()) / 3_600_000.0;
        if (ageH >= 24) host.warn(String.format(Locale.US, "Dhan token is %.0f hours old — it has probably expired. Paste a new one if connecting fails.", ageH));
        useToken(id, token, false);
        tokenExpired = false;
        try {
            DhanApi.Funds f = api.funds();
            funds = f.available;
            host.info("Connected to Dhan. Available funds ₹" + BotService.money(f.available)
                    + (live ? "" : " (paper mode uses your capital setting)"));
            List<BrokerPosition> pos = api.foPositions();
            lastReconcile = System.currentTimeMillis();
            host.onEngine(e -> e.reconcile(pos));
            refreshExpiries();
            loadHistory();
            refreshChains(true);
            LocalDate today = LocalDate.now(MarketClock.IST);
            if (!MarketClock.tradingDay(today)) host.info("Market is closed today (weekend or NSE holiday). The bot will wait.");
            if (MarketClock.holidayListOutdated(today)) host.warn("NSE holiday list has no dates for " + today.getYear() + ". Add them in Settings.");
        } catch (DhanApi.DhanException e) {
            failWith(e.code == 401 || e.code == 403 ? "Dhan rejected the token (expired or wrong). Paste a fresh token in Settings." : e.getMessage());
            return;
        } catch (Exception e) {
            failWith("Could not reach Dhan: " + e.getMessage());
            return;
        }
        startFeed(id, token);
        host.onEngine(e -> { if (e.state() == BotState.STARTING) e.setState(BotState.READY, "connected; tap Start trading"); });
        loop(gen);
    }

    private void useToken(String id, String token, boolean restartFeed) {
        api = new DhanApi(http, id, token);
        if (live) liveRef.target = new DhanLiveBroker(api, cfg.holding == Config.Holding.WEEKLY);
        if (restartFeed) {
            if (feed != null) feed.stop();
            startFeed(id, token);
        }
    }

    private void startFeed(String id, String token) {
        feed = new DhanFeed(http, id, token, feedCallback, vixId);
        feed.start();
        if (!wanted.isEmpty()) feed.setOptions(wanted);
    }

    /** Warm-up bars and India VIX history. Failures only warn: the bot can still form a trend live. */
    private void loadHistory() {
        LocalDate today = LocalDate.now(MarketClock.IST);
        int backDays = cfg.holding == Config.Holding.WEEKLY ? 45 : 7;
        try {
            List<Candle> hist = api.niftyBars(cfg.barMinutes(), today.minusDays(backDays), today);
            host.onEngine(e -> e.seedBars(hist));
        } catch (Exception ex) {
            host.warn("Could not load earlier NIFTY bars (" + ex.getMessage() + "). The regime will form from live data (slow).");
        }
        try {
            List<Double> vix = api.indexDailyCloses(vixId, today.minusDays(400), today.plusDays(1));
            host.onEngine(e -> e.setVixHistory(vix));
        } catch (Exception ex) {
            host.warn("Could not load India VIX history (id " + vixId + "): " + ex.getMessage() + ". IV level will use ATM IV.");
        }
    }

    private void refreshExpiries() throws IOException {
        List<String> list = api.expiries();
        lastExpiryList = System.currentTimeMillis();
        int minDays = cfg.holding == Config.Holding.WEEKLY ? cfg.minDaysToExpiryWeekly : cfg.minDaysToExpiryIntraday;
        String e = MarketClock.chooseExpiry(list, LocalDate.now(MarketClock.IST), minDays);
        if (e.isEmpty()) { host.warn("No suitable NIFTY expiry found"); return; }
        chosenExpiry = e;
        host.onEngine(en -> en.setTradeExpiry(e));
    }

    // ------------------------------------------------------------------ the io loop

    private void loop(int myGen) {
        io.execute(() -> {
            while (running && myGen == gen && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                    long now = System.currentTimeMillis();
                    boolean open = MarketClock.sessionOpen(now);
                    if (now - lastExpiryList > 30 * 60_000L) refreshExpiries();
                    if (now - lastChain > (open ? 12_000 : 120_000)) refreshChains(false);
                    syncBars(now, open);
                    // Paper mode does not need Dhan's positions or funds every minute (checked once at start).
                    if (live && now - lastReconcile > 60_000) {
                        lastReconcile = now;
                        List<BrokerPosition> pos = api.foPositions();
                        host.onEngine(e -> e.reconcile(pos));
                        funds = api.funds().available;
                    }
                    maybeRenewToken(now);
                } catch (InterruptedException e) {
                    return;
                } catch (DhanApi.DhanException e) {
                    if (e.code == 401 || e.code == 403) { tokenDied(); return; }
                    host.warn("Dhan: " + e.getMessage());
                } catch (Exception e) {
                    host.warn("Refresh failed: " + e.getMessage());
                }
            }
        });
    }

    private void tokenDied() {
        tokenExpired = true;
        lastError = "Dhan token expired";
        host.onEngine(e -> {
            e.pause("token expired");
            e.setState(BotState.ERROR, "Dhan token expired — paste a new one in Settings and tap Save");
            Position p = e.position();
            host.alert("Dhan token expired" + (p != null ? " while holding " + p.type.title
                    + ". The bot cannot see prices or exit until you paste a new token in Settings and tap Save." : ". Paste a new one in Settings."));
        });
    }

    /** Refresh one needed expiry per call (round robin), or all of them when {@code all}. */
    private void refreshChains(boolean all) throws Exception {
        lastChain = System.currentTimeMillis();
        Set<String> needSet = new LinkedHashSet<>(needed);
        if (!chosenExpiry.isEmpty()) needSet.add(chosenExpiry);
        List<String> need = new ArrayList<>(needSet);
        if (need.isEmpty()) return;
        List<String> todo = new ArrayList<>();
        if (all) todo.addAll(need);
        else todo.add(need.get(chainTurn++ % need.size()));
        for (String exp : todo) {
            DhanApi.Chain c = api.optionChain(exp);
            long now = System.currentTimeMillis();
            host.onEngine(e -> {
                for (DhanApi.ChainRow r : c.rows)
                    e.upsertContract(r.securityId, r.call ? OptionQuote.Right.CE : OptionQuote.Right.PE, r.strike,
                            c.expiry, r.delta, r.iv, r.oi, r.ltp, r.bid, r.ask, now);
                if (e.spot() <= 0 && c.spot > 0) e.onSpot(c.spot, now);
                Set<String> want = e.wantedContracts(900);
                wanted = want;
                if (feed != null) feed.setOptions(want);
            });
        }
    }

    /**
     * Ask Dhan for its own copy of recent bars: quickly after each bar closes (the engine waits
     * up to a minute for it), and every 10 minutes in market hours to repair any gaps.
     */
    private void syncBars(long now, boolean open) throws IOException {
        boolean wantedNow = pendingBar > 0 && now - lastBarSync >= 5_000;
        boolean periodic = open && now - lastFullBarSync >= 10 * 60_000L;
        if (!wantedNow && !periodic) return;
        lastBarSync = now;
        if (periodic) lastFullBarSync = now;
        LocalDate today = LocalDate.now(MarketClock.IST);
        try {
            List<Candle> bars = api.niftyBars(cfg.barMinutes(), MarketClock.previousTradingDay(today), today);
            host.onEngine(e -> e.onDhanBars(bars));
        } catch (DhanApi.DhanException e) {
            if (e.code == 401 || e.code == 403) throw e;
            if (periodic) host.warn("Could not fetch Dhan's bars: " + e.getMessage());
        } catch (IOException e) {
            if (periodic) host.warn("Could not fetch Dhan's bars: " + e.getMessage());
        }
    }

    /**
     * Dhan tokens last 24 hours. Between 20 and 24 hours old the bot swaps it for a fresh one
     * (Dhan's RenewToken; works for tokens made on Dhan Web) when no order is working.
     */
    private void maybeRenewToken(long now) {
        long made = settings.tokenMadeMillis();
        if (made == 0) return;
        double ageH = (now - made) / 3_600_000.0;
        if (ageH < 20) return;
        if (ageH < 24 && settings.autoRenewToken() && job.isEmpty() && now - renewFailed > 30 * 60_000L) {
            DhanApi.Renewed r;
            try {
                r = api.renewToken();
            } catch (IOException e) {
                renewFailed = now;
                host.alert("Could not renew the Dhan token (" + e.getMessage() + "). Paste a new token in Settings within "
                        + String.format(Locale.US, "%.0f", Math.max(0, 24 - ageH)) + " hours.");
                return;
            }
            if (r.accessToken.length() < 20) {
                renewFailed = now;
                host.alert("Dhan's token renewal answered without a token. Paste a new token in Settings now.");
                return;
            }
            settings.saveToken(r.accessToken, now);
            settings.markRenewed(now);
            useToken(settings.clientId(), r.accessToken, true);
            host.info("Dhan token renewed for 24 hours" + (r.expiryTime.isEmpty() ? "" : " (expires " + r.expiryTime + ")"));
            return;
        }
        if (now - lastTokenWarn > 60 * 60_000L) {
            lastTokenWarn = now;
            host.alert(String.format(Locale.US, "Dhan token is %.0f hours old and expires at 24 hours. Paste a new one in Settings.", ageH));
        }
    }

    private void failWith(String msg) {
        lastError = msg;
        host.fail(msg);
    }

    // ------------------------------------------------------------------ live feed

    private final DhanFeed.Callback feedCallback = new DhanFeed.Callback() {
        @Override public void onConnected() {
            feedConnected = true;
            feedNote = "live";
            host.info("Live feed connected");
        }

        @Override public void onClosed(String why) {
            feedConnected = false;
            feedNote = why;
            host.warn("Live feed lost: " + why + " — reconnecting");
        }

        @Override public void onPrice(int segment, String securityId, double ltp, double bid, double ask, double oi) {
            long now = System.currentTimeMillis();
            if (segment == FeedParser.SEG_IDX_I) {
                if (ltp <= 0) return;
                if (DhanApi.NIFTY_ID.equals(securityId)) host.onEngine(e -> e.onSpot(ltp, now));
                else if (securityId.equals(vixId)) { lastVixTick = now; host.onEngine(e -> e.onVix(ltp)); }
            } else if (segment == FeedParser.SEG_NSE_FNO) {
                host.onEngine(e -> e.onOptionTick(securityId, ltp, bid, ask, now));
            }
        }

        @Override public void onDepth(int segment, String securityId, double[] bidPx, int[] bidQty, double[] askPx, int[] askQty) {
            if (segment != FeedParser.SEG_NSE_FNO) return;
            long now = System.currentTimeMillis();
            host.onEngine(e -> e.onDepth(securityId, bidPx, bidQty, askPx, askQty, now));
        }

        @Override public void onDisconnect(int reasonCode) {
            host.warn("Dhan feed disconnect code " + reasonCode + " (805 = too many connections; 806/807/808 = token/plan/auth problem)");
        }

        @Override public void onParseError(String message) { host.warn("Feed: " + message); }
    };
}
