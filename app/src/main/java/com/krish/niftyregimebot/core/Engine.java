package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The trading brain. Single-threaded: every public method must be called from the same
 * engine thread (the Android service and the backtester guarantee this).
 *
 * On each closed bar ({@link BarBook}): read trend + IV → pick a strategy ({@link StrategyGrid})
 * → entry checks ({@link EntryGate}) → strikes ({@link StrategyBuilder}) → lots ({@link Sizer})
 * → place legs ({@link OrderRunner}). Every second: manage the open trade ({@link ExitRules}).
 * Money is booked by {@link Ledger}. The strategy itself is written up in
 * "Nifty Regime Bot — Strategy and Rules (v2.0)".
 */
public final class Engine implements BarBook.Sink, OrderRunner.Host {

    public enum ExitReason { TARGET, STOP, REGIME_FLIP, TIME, EXPIRY_NEAR, EMERGENCY, UNWIND, EVENT, GAP }

    public interface Listener {
        void onLog(String level, String message);
        void onDecision(Decision d);
        void onStateChanged(BotState s, String why);
        void onPositionChanged(Position p);      // null when flat
        void onPositionSaved(String json);       // "" when flat — store it so restarts keep it
        void onTradeClosed(TradeRecord t);
        void onStatsChanged(DayStats d);
        void onAlert(String message);
    }

    /** What the bot concluded on a bar, for the screen and journal. */
    public static final class Decision {
        public long timeMillis;
        public RegimeDetector.Reading regime;
        public IvGauge.Reading iv;
        public StrategyGrid.Type pick = StrategyGrid.Type.NONE;
        public String action = "";
    }

    // ------------------------------------------------------------------ parts

    private final Config cfg;
    private final Charges charges;
    private final Broker broker;
    private final Listener out;
    private final IvGauge ivGauge;
    private final StrategyBuilder builder;
    private final Sizer sizer;
    private final BarBook book;
    private final EntryGate gate;
    private final ExitRules exits;
    private final OrderRunner orders;
    private final Map<String, OptionQuote> quotes = new LinkedHashMap<>();

    // ------------------------------------------------------------------ state

    private BotState state = BotState.OFF;
    private DayStats day = new DayStats();
    private Position position;
    private Decision lastDecision;
    private String tradeExpiry = "";
    private double spot, availableFunds;
    private long lastSpotMillis, nowMillis, lastStaleWarn;
    private boolean emergencyRequested;
    private String entryRegimeText = "", entryIvText = "";
    private String lastExitKind = "";          // WIN, LOSS or FLIP (sets the next cool-down)
    private final Set<String> acknowledgedIds = new HashSet<>();
    private final Set<String> lastStrangerIds = new HashSet<>();

    public Engine(Config cfg, Charges charges, Broker broker, Listener out) {
        this.cfg = cfg;
        this.charges = charges;
        this.broker = broker;
        this.out = out;
        this.ivGauge = new IvGauge(cfg);
        this.builder = new StrategyBuilder(cfg);
        this.sizer = new Sizer(cfg, charges);
        this.book = new BarBook(cfg, this);
        this.gate = new EntryGate(cfg);
        this.exits = new ExitRules(cfg, out);
        this.orders = new OrderRunner(cfg, broker, quotes, this);
        this.availableFunds = cfg.capital;
    }

    // ------------------------------------------------------------------ read-only views

    public BotState state() { return state; }
    public Position position() { return position; }
    public DayStats day() { return day; }
    public Decision lastDecision() { return lastDecision; }
    public RegimeDetector.Reading lastReading() { return book.reading(); }
    public double spot() { return spot; }
    public long lastSpotMillis() { return lastSpotMillis; }
    public Collection<OptionQuote> quotes() { return quotes.values(); }
    public OptionQuote quote(String id) { return quotes.get(id); }
    public boolean busy() { return orders.busy(); }
    public String jobText() { return orders.text(); }
    public String tradeExpiry() { return tradeExpiry; }
    public List<Candle> bars() { return book.bars(); }
    public IvGauge ivGauge() { return ivGauge; }
    public Config config() { return cfg; }
    /** Start time of the closed bar we are waiting to confirm with Dhan's data, or 0. */
    public long pendingBarStart() { return book.pendingStart(); }
    public int barsFixed() { return book.fixedCount(); }
    /** Put OI ÷ call OI near NIFTY for the trading expiry (0 = no OI data). */
    public double pcr() { return gate.pcr(quotes.values(), tradeExpiry, spot); }

    /** Expiries whose option chain must be kept fresh (trading expiry + open position's). */
    public Set<String> neededExpiries() {
        Set<String> s = new LinkedHashSet<>();
        if (!tradeExpiry.isEmpty()) s.add(tradeExpiry);
        if (position != null && !position.expiry.isEmpty()) s.add(position.expiry);
        return s;
    }

    /** Contracts to stream live: near-the-money for trading plus every leg we hold or are placing. */
    public Set<String> wantedContracts(double band) {
        Set<String> ids = new LinkedHashSet<>();
        for (OptionQuote q : quotes.values())
            if (q.expiry.equals(tradeExpiry) && spot > 0 && Math.abs(q.strike - spot) <= band) ids.add(q.securityId);
        if (position != null) for (Leg l : position.legs) ids.add(l.securityId);
        if (orders.job() != null) for (OrderRunner.LegOrder o : orders.job().orders) ids.add(o.leg.securityId);
        return ids;
    }

    // ------------------------------------------------------------------ control

    @Override public void setState(BotState s, String why) {
        if (s == state) return;
        state = s;
        out.onStateChanged(s, why);
        log("INFO", "State → " + s + (why == null || why.isEmpty() ? "" : " (" + why + ")"));
    }

    public boolean startTrading() {
        if (state == BotState.READY || state == BotState.PAUSED) { setState(BotState.TRADING, "started by you"); return true; }
        log("WARN", "Cannot start trading while " + state);
        return false;
    }

    @Override public void pause(String why) { if (state == BotState.TRADING) setState(BotState.PAUSED, why); }

    public void acknowledgeRecovery() {
        acknowledgedIds.addAll(lastStrangerIds);
        if (state == BotState.RECOVERY || state == BotState.ERROR) setState(BotState.READY, "you confirmed the account is OK");
    }

    public void restoreDay(DayStats saved) { if (saved != null) day = saved; }
    public void setAvailableFunds(double f) { availableFunds = f; }

    public void setTradeExpiry(String e) {
        if (e != null && !e.equals(tradeExpiry)) {
            tradeExpiry = e;
            log("INFO", "Trading expiry " + e);
        }
    }

    /** Bring back a position saved before the app stopped (weekly holding). */
    public void restorePosition(Position p) {
        if (p == null || p.legs.isEmpty() || p.qty <= 0) return;
        position = p;
        log("INFO", "Restored open position: " + p.summary() + " qty " + p.qty);
        out.onPositionChanged(position);
    }

    public void emergencyExit(long now) {
        nowMillis = now;
        pause("emergency exit");
        if (position != null) {
            emergencyRequested = true;
            if (!orders.busy()) startExit(ExitReason.EMERGENCY);
        } else if (orders.entering()) {
            emergencyRequested = true;
            log("INFO", "Emergency: entry in progress will be unwound");
            orders.abortEntry("emergency exit");
        } else {
            log("INFO", "Emergency exit: nothing open");
        }
    }

    // ------------------------------------------------------------------ data in

    /** Warm-up bars (same timeframe as live bars), oldest first. */
    public void seedBars(List<Candle> history) {
        book.seed(history);
        log("INFO", "Loaded " + history.size() + " earlier " + book.minutes() + "-min bars. Regime now " + book.reading().confirmed);
    }

    public void setVixHistory(List<Double> closes) {
        ivGauge.setVixHistory(closes);
        log("INFO", ivGauge.hasVixHistory() ? "India VIX history loaded (" + closes.size() + " days)"
                : "No usable VIX history — IV level will use the option chain's ATM IV");
    }

    public void onVix(double v) { ivGauge.onVix(v); }

    public void onSpot(double price, long now) {
        nowMillis = now;
        if (price <= 0) return;
        exits.onSpot(spot, lastSpotMillis, now);
        spot = price;
        lastSpotMillis = now;
        book.onTick(price, now);
    }

    /** Recent bars from Dhan's chart (see {@link BarBook#onDhanBars}). */
    public void onDhanBars(List<Candle> official) { book.onDhanBars(official); }

    public OptionQuote upsertContract(String securityId, OptionQuote.Right right, double strike, String expiry,
                                      double delta, double iv, double oi, double ltp, double bid, double ask, long now) {
        OptionQuote q = quotes.get(securityId);
        if (q == null) { q = new OptionQuote(securityId, right, strike, expiry); quotes.put(securityId, q); }
        q.delta = delta; q.iv = iv; q.oi = oi;
        if (now - q.updatedMillis > 2_000) { q.ltp = ltp; q.bid = bid; q.ask = ask; q.updatedMillis = now; }
        return q;
    }

    public void onOptionTick(String securityId, double ltp, double bid, double ask, long now) {
        nowMillis = now;
        OptionQuote q = quotes.get(securityId);
        if (q == null) return;
        if (ltp > 0) q.ltp = ltp;
        if (bid > 0) q.bid = bid;
        if (ask > 0) q.ask = ask;
        q.updatedMillis = now;
    }

    /** Market depth (5 levels) for an option, from the live feed's full packet. */
    public void onDepth(String securityId, double[] bidPx, int[] bidQty, double[] askPx, int[] askQty, long now) {
        OptionQuote q = quotes.get(securityId);
        if (q != null) q.setDepth(bidPx, bidQty, askPx, askQty, now);
    }

    /** Compare with the real Dhan account (live mode blocks entries on surprises). */
    public void reconcile(List<BrokerPosition> brokerPositions) {
        Set<String> ours = new HashSet<>();
        if (position != null) for (Leg l : position.legs) ours.add(l.securityId);
        if (orders.job() != null) for (OrderRunner.LegOrder o : orders.job().orders) ours.add(o.leg.securityId);
        List<String> strangers = new ArrayList<>();
        lastStrangerIds.clear();
        for (BrokerPosition b : brokerPositions) {
            if (b.netQty == 0) continue;
            if ((broker.isLive() && ours.contains(b.securityId)) || acknowledgedIds.contains(b.securityId)) continue;
            strangers.add(b.symbol + " qty " + b.netQty);
            lastStrangerIds.add(b.securityId);
        }
        if (broker.isLive() && position != null && !orders.busy() && state != BotState.RECOVERY) {
            for (Leg l : position.legs) {
                boolean found = false;
                for (BrokerPosition b : brokerPositions) if (b.securityId.equals(l.securityId) && b.netQty != 0) found = true;
                if (!found) {
                    setState(BotState.RECOVERY, "position mismatch");
                    out.onAlert("Bot holds " + l.label() + " but Dhan shows no such position. Check Dhan now.");
                    break;
                }
            }
        }
        if (strangers.isEmpty()) return;
        if (!broker.isLive()) {
            if (acknowledgedIds.addAll(lastStrangerIds))
                log("INFO", "Your Dhan account has F&O positions the paper bot ignores: " + String.join(", ", strangers));
            return;
        }
        if (state != BotState.RECOVERY) {
            pause("account check");
            setState(BotState.RECOVERY, "unexpected position");
            out.onAlert("Open F&O position at Dhan that the bot did not open: " + String.join(", ", strangers)
                    + ". New entries blocked until you check it.");
        }
    }

    // ------------------------------------------------------------------ clock

    /** Call about once a second. */
    public void onTimer(long now) {
        nowMillis = now;
        rollDayIfNeeded(now);
        book.onTimer(now);
        gate.trackPcr(now, quotes.values(), tradeExpiry, spot);
        orders.step(now);
        if (!orders.busy() && emergencyRequested) {
            if (position != null) startExit(ExitReason.EMERGENCY);
            else emergencyRequested = false;
        }
        if (position != null) manage(now);
    }

    // ------------------------------------------------------------------ bars → decisions

    @Override public void onBarReady(Candle c, boolean incomplete) {
        RegimeDetector.Reading reading = book.reading();
        Decision d = new Decision();
        d.timeMillis = nowMillis == 0 ? c.startMillis : nowMillis;
        d.regime = reading;
        d.iv = ivGauge.read(atmIv());
        d.pick = StrategyGrid.pick(reading.confirmed, d.iv.level, cfg);
        lastDecision = d;

        if (incomplete) {
            d.action = "bar data incomplete (missed its start, Dhan's copy did not arrive) — no new entry or trend exit on this bar"
                    + (position != null ? "; target/stop/time exits still active" : "");
        } else if (position != null) {
            d.action = "holding " + position.type.title;
            if (StrategyGrid.regimeAgainst(position.type, reading.confirmed, reading.adx, cfg) && !orders.busy()) {
                d.action = "regime turned " + reading.confirmed + " → closing " + position.type.title;
                out.onDecision(d);
                startExit(ExitReason.REGIME_FLIP);
                return;
            }
        } else {
            d.action = tryEnter(d);
        }
        out.onDecision(d);
    }

    /** Returns a one-line description of what happened. */
    private String tryEnter(Decision d) {
        if (state != BotState.TRADING) return "no entry: bot is " + state;
        if (orders.busy()) return "no entry: order in progress";
        String block = entryBlock();
        if (block != null) return "no entry: " + block;
        if (d.pick == StrategyGrid.Type.NONE) {
            if (book.reading().confirmed == RegimeDetector.Regime.UNKNOWN) return "no entry: regime not ready";
            if (d.iv.level == IvGauge.Level.UNKNOWN) return "no entry: IV level unknown";
            return "no entry: sideways market — INTRADAY makes no trade here (credit trades need WEEKLY; butterfly is off)";
        }
        if (tradeExpiry.isEmpty()) return "no entry: no expiry chosen";
        String why = gate.filters(d.pick, nowMillis, spot, tradeExpiry, book, quotes.values());
        if (why != null) return "no entry: " + d.pick.title + " — " + why;

        StrategyBuilder.Plan plan = builder.build(d.pick, spot, tradeExpiry, quotes.values());
        if (!plan.ok()) return "no entry: " + d.pick.title + " — " + plan.rejected;

        double remainingDaily = cfg.dailyLossLimit + Math.min(0, day.realizedPnl);
        Sizer.Result size = sizer.size(plan, Math.min(cfg.riskPerTrade, remainingDaily), availableFunds);
        if (!size.ok()) return "no entry: " + d.pick.title + " — " + size.why;
        why = gate.charges(d.pick, size);
        if (why != null) return "no entry: " + d.pick.title + " — " + why;

        String msg = String.format(Locale.US, "ENTER %s, %d lot(s), qty %d, expiry %s. Max loss ₹%,.0f, max profit %s. %s",
                plan.summary(), size.lots, size.qty, tradeExpiry, size.maxLossRs,
                Double.isInfinite(size.maxProfitRs) ? "unlimited" : String.format(Locale.US, "₹%,.0f", size.maxProfitRs),
                plan.notes.isEmpty() ? "" : plan.notes.get(0));
        log("INFO", msg);
        String ivText = d.iv.level + (Double.isNaN(d.iv.vix) ? "" : String.format(Locale.US, " (VIX %.1f)", d.iv.vix));
        orders.enter(plan, size, String.valueOf(book.reading().confirmed), ivText);
        return msg;
    }

    /** Null if entries are allowed now (time, limits, cool-down), else the reason. */
    String entryBlock() { return gate.limits(nowMillis, lastSpotMillis, day, lastExitKind); }

    /** Group bars into N-minute bars aligned to 09:15. */
    static List<Candle> resample(List<Candle> in, int minutes) { return BarBook.resample(in, minutes); }

    private double atmIv() {
        if (spot <= 0) return 0;
        double atm = Math.round(spot / cfg.strikeStep) * cfg.strikeStep;
        double sum = 0;
        int n = 0;
        for (OptionQuote q : quotes.values())
            if (q.expiry.equals(tradeExpiry) && Math.abs(q.strike - atm) < 0.01 && q.iv > 0) { sum += q.iv; n++; }
        return n == 0 ? 0 : sum / n;
    }

    // ------------------------------------------------------------------ the open trade

    private void manage(long now) {
        if (!ExitRules.mark(position, quotes, cfg.wideGapPct, now) && now - position.lastMarkMillis > 30_000) {
            position.stale = true;
            if (now - lastStaleWarn > 60_000) {
                lastStaleWarn = now;
                log("WARN", "No complete prices for all legs of " + position.type.title + " — exits use the last known P&L");
            }
        }
        if (orders.busy()) return;
        ExitReason why = exits.check(position, now, spot);
        if (why != null) startExit(why);
    }

    private void startExit(ExitReason why) {
        if (position == null || orders.busy()) return;
        log("INFO", String.format(Locale.US, "EXIT %s — %s (open P&L ₹%,.0f)", position.type.title, why, position.openPnl));
        orders.exit(position, why);
    }

    /** All legs of a job filled. */
    @Override public void finished(OrderRunner.Job j) {
        switch (j.kind) {
            case ENTRY: opened(j); break;
            case EXIT: closed(j); break;
            case UNWIND: unwound(j); break;
        }
    }

    private void opened(OrderRunner.Job j) {
        Position p = Ledger.open(j, cfg, sizer, broker.isLive(), nowMillis);
        position = p;
        entryRegimeText = j.regimeText;
        entryIvText = j.ivText;
        day.entries++;
        out.onStatsChanged(day);
        out.onPositionChanged(p);
        out.onPositionSaved(Json.write(p.toMap()));
        out.onAlert(String.format(Locale.US, "Opened %s qty %d. Net %s %.2f. Target +₹%,.0f, stop −₹%,.0f, max loss ₹%,.0f",
                p.summary(), p.qty, p.netPremium >= 0 ? "credit" : "debit", Math.abs(p.netPremium),
                p.targetRs, p.stopRs, p.maxLossRs()));
    }

    private void closed(OrderRunner.Job j) {
        Position p = position;
        if (p == null) return;
        TradeRecord t = Ledger.close(p, j, charges, cfg, entryRegimeText, entryIvText, nowMillis);
        Ledger.record(day, t.net, nowMillis);
        position = null;
        emergencyRequested = false;
        out.onTradeClosed(t);
        out.onStatsChanged(day);
        out.onPositionChanged(null);
        out.onPositionSaved("");
        out.onAlert(String.format(Locale.US, "Closed %s — %s. Net ₹%,.0f after ₹%,.0f charges (day ₹%,.0f)",
                t.strategy, t.exitReason, t.net, t.charges, day.realizedPnl));
        lastExitKind = j.reason == ExitReason.REGIME_FLIP ? "FLIP" : t.net >= 0 ? "WIN" : "LOSS";
        if (day.realizedPnl <= -cfg.dailyLossLimit) out.onAlert("Daily loss limit reached. No more entries today.");
        else if (day.consecutiveLosses >= cfg.maxConsecutiveLosses) out.onAlert(day.consecutiveLosses + " losses in a row — no more entries today.");
    }

    private void unwound(OrderRunner.Job j) {
        double pnl = Ledger.unwindCost(j, charges);
        day.realizedPnl += pnl;
        day.lastExitMillis = nowMillis;
        lastExitKind = "LOSS";
        out.onStatsChanged(day);
        out.onAlert(String.format(Locale.US, "Half-built entry closed. Cost ₹%,.0f.", pnl));
    }

    // ------------------------------------------------------------------ helpers

    private void rollDayIfNeeded(long now) {
        String today = MarketClock.date(now).toString();
        if (today.equals(day.date)) return;
        if (!day.date.isEmpty()) {
            day = Ledger.nextDay(day, today, MarketClock.date(now));
            log("INFO", "New trading day " + today);
        } else {
            day.date = today;
        }
        out.onStatsChanged(day);
    }

    // OrderRunner.Host / BarBook.Sink
    @Override public long now() { return nowMillis; }
    @Override public void log(String level, String msg) { out.onLog(level, msg); }
    @Override public void alert(String message) { out.onAlert(message); }
}
