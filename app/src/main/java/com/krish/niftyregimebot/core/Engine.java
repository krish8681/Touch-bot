package com.krish.niftyregimebot.core;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The trading brain. Single-threaded: every public method must be called from the same
 * engine thread (the Android service guarantees this).
 *
 * Each closed bar: read regime (trend) + IV level → pick a strategy from the grid → build
 * strikes → size by max loss → risk checks → place legs one by one (buys first) →
 * manage exits (target, stop, regime flip, time). If any entry leg fails, the legs already
 * filled are closed straight away, so the bot never sits on a half-built, unhedged position.
 */
public final class Engine implements CandleAggregator.Sink {

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

    // ------------------------------------------------------------------ order jobs

    private enum JobKind { ENTRY, EXIT, UNWIND }

    private static final class LegOrder {
        final Leg leg;          // the strategy leg
        final boolean buy;      // direction of THIS order
        final int targetQty;
        int doneQty;            // filled in earlier attempts
        double doneValue;       // Σ price × qty of earlier fills
        Broker.OrderRequest req;
        Broker.OrderUpdate last;
        long sentMillis, firstMillis, retryAfter;
        int rejects;
        boolean outcomeUnknown, cancelRequested;
        int attempts;

        LegOrder(Leg leg, boolean buy, int qty) { this.leg = leg; this.buy = buy; this.targetQty = qty; }

        int filledNow() { return doneQty + (last == null ? 0 : last.filledQty); }
        double avgPrice() {
            int q = filledNow();
            double v = doneValue + (last == null ? 0 : last.filledQty * last.avgPrice);
            return q == 0 ? 0 : v / q;
        }
    }

    private static final class Job {
        JobKind kind;
        final List<LegOrder> orders = new ArrayList<>();
        int idx;
        StrategyBuilder.Plan plan;
        Sizer.Result size;
        ExitReason reason;
        long startMillis;
        double startSpot;
        String regimeText = "", ivText = "";
    }

    // ------------------------------------------------------------------ fields

    private final Config cfg;
    private final Charges charges;
    private final Broker broker;
    private final Listener out;
    private final RegimeDetector regime;
    private final IvGauge ivGauge;
    private final StrategyBuilder builder;
    private final Sizer sizer;
    private final CandleAggregator agg;
    private final List<Candle> bars = new ArrayList<>();
    private final Map<String, OptionQuote> quotes = new LinkedHashMap<>();

    private BotState state = BotState.OFF;
    private DayStats day = new DayStats();
    private Position position;
    private Job job;
    private Decision lastDecision;
    private RegimeDetector.Reading lastReading = new RegimeDetector.Reading();
    private String tradeExpiry = "";
    private double spot, availableFunds;
    private long lastSpotMillis, nowMillis, lastStaleWarn;
    private int corrSeq;
    private boolean emergencyRequested;
    private final Set<String> acknowledgedIds = new java.util.HashSet<>();
    private final Set<String> lastStrangerIds = new java.util.HashSet<>();
    private String entryRegimeText = "", entryIvText = "";
    private final RegimeDetector regime15;     // 15-min trend check (INTRADAY)
    private String lastExitKind = "";          // WIN, LOSS or FLIP (sets the next cool-down)
    private double prevDayClose;               // last NIFTY price of the previous day (gap check)
    private boolean gapPending;
    private double dayOpenPcr;                 // PCR seen first after 09:30 today
    private String pcrDay = "";
    private Candle pendingBar;                 // closed from our own ticks, waiting for Dhan's copy
    private long pendingSince;
    private int barsFixed;                     // bars corrected or filled in from Dhan's data

    public Engine(Config cfg, Charges charges, Broker broker, Listener out) {
        this.cfg = cfg;
        this.charges = charges;
        this.broker = broker;
        this.out = out;
        this.regime = new RegimeDetector(cfg);
        this.regime15 = new RegimeDetector(cfg);
        this.ivGauge = new IvGauge(cfg);
        this.builder = new StrategyBuilder(cfg);
        this.sizer = new Sizer(cfg, charges);
        this.agg = new CandleAggregator(cfg.barMinutes(), this);
        this.availableFunds = cfg.capital;
    }

    // ------------------------------------------------------------------ read-only views

    public BotState state() { return state; }
    public Position position() { return position; }
    public DayStats day() { return day; }
    public Decision lastDecision() { return lastDecision; }
    public RegimeDetector.Reading lastReading() { return lastReading; }
    public double spot() { return spot; }
    public long lastSpotMillis() { return lastSpotMillis; }
    public Collection<OptionQuote> quotes() { return quotes.values(); }
    public OptionQuote quote(String id) { return quotes.get(id); }
    public boolean busy() { return job != null; }
    public String jobText() {
        if (job == null) return "";
        return job.kind + " leg " + Math.min(job.idx + 1, job.orders.size()) + "/" + job.orders.size();
    }
    public String tradeExpiry() { return tradeExpiry; }
    public List<Candle> bars() { return bars; }
    public IvGauge ivGauge() { return ivGauge; }
    public Config config() { return cfg; }
    /** Start time of the closed bar we are waiting to confirm with Dhan's data, or 0. */
    public long pendingBarStart() { return pendingBar == null ? 0 : pendingBar.startMillis; }
    public int barsFixed() { return barsFixed; }

    /** Expiries whose option chain must be kept fresh (trading expiry + open position's). */
    public Set<String> neededExpiries() {
        Set<String> s = new LinkedHashSet<>();
        if (!tradeExpiry.isEmpty()) s.add(tradeExpiry);
        if (position != null && !position.expiry.isEmpty()) s.add(position.expiry);
        return s;
    }

    /** Contracts to stream live: near-the-money for trading plus every leg we hold. */
    public Set<String> wantedContracts(double band) {
        Set<String> ids = new LinkedHashSet<>();
        double s = spot;
        for (OptionQuote q : quotes.values())
            if (q.expiry.equals(tradeExpiry) && s > 0 && Math.abs(q.strike - s) <= band) ids.add(q.securityId);
        if (position != null) for (Leg l : position.legs) ids.add(l.securityId);
        if (job != null) for (LegOrder o : job.orders) ids.add(o.leg.securityId);
        return ids;
    }

    // ------------------------------------------------------------------ control

    public void setState(BotState s, String why) {
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

    public void pause(String why) { if (state == BotState.TRADING) setState(BotState.PAUSED, why); }

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
            if (job == null) startExit(ExitReason.EMERGENCY);
        } else if (job != null && job.kind == JobKind.ENTRY) {
            emergencyRequested = true;
            log("INFO", "Emergency: entry in progress will be unwound");
            abortEntry("emergency exit");
        } else {
            log("INFO", "Emergency exit: nothing open");
        }
    }

    // ------------------------------------------------------------------ data in

    /** Warm-up bars (same timeframe as live bars), oldest first. */
    public void seedBars(List<Candle> history) {
        bars.clear();
        bars.addAll(history);
        lastReading = regime.replay(bars);
        log("INFO", "Loaded " + history.size() + " earlier " + agg.minutes() + "-min bars. Regime now " + lastReading.confirmed);
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
        if (spot > 0 && lastSpotMillis > 0 && !MarketClock.date(now).equals(MarketClock.date(lastSpotMillis))) {
            prevDayClose = spot;
            gapPending = true;
        }
        spot = price;
        lastSpotMillis = now;
        agg.onTick(price, now);
    }

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
        if (q == null) return;
        q.setDepth(bidPx, bidQty, askPx, askQty, now);
    }

    /** Compare with the real Dhan account (live mode blocks entries on surprises). */
    public void reconcile(List<BrokerPosition> brokerPositions) {
        Set<String> ours = new java.util.HashSet<>();
        if (position != null) for (Leg l : position.legs) ours.add(l.securityId);
        if (job != null) for (LegOrder o : job.orders) ours.add(o.leg.securityId);
        List<String> strangers = new ArrayList<>();
        lastStrangerIds.clear();
        for (BrokerPosition b : brokerPositions) {
            if (b.netQty == 0) continue;
            if ((broker.isLive() && ours.contains(b.securityId)) || acknowledgedIds.contains(b.securityId)) continue;
            strangers.add(b.symbol + " qty " + b.netQty);
            lastStrangerIds.add(b.securityId);
        }
        if (broker.isLive() && position != null && job == null && state != BotState.RECOVERY) {
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
        agg.closeIfDue(now);
        trackPcr();
        if (pendingBar != null && now - pendingSince >= cfg.dhanBarWaitMillis) {
            Candle c = pendingBar;
            pendingBar = null;
            decide(c, false);
        }
        stepJob(now);
        if (job == null && emergencyRequested) {
            if (position != null) startExit(ExitReason.EMERGENCY);
            else emergencyRequested = false;
        }
        if (position != null) manage(now);
    }

    // ------------------------------------------------------------------ bars → decisions

    /** A bar built from our own ticks just closed. Wait briefly for Dhan's official copy. */
    @Override public void onBarClosed(Candle c) {
        if (pendingBar != null) { Candle old = pendingBar; pendingBar = null; decide(old, false); }
        if (cfg.dhanBarWaitMillis <= 0) { decide(c, false); return; }
        pendingBar = c;
        pendingSince = nowMillis;
    }

    /** Our own bar is incomplete if we missed its start (bot started late, feed gap). */
    private boolean incomplete(Candle c) {
        return c.firstTickMillis > 0 && c.firstTickMillis - c.startMillis > cfg.partialBarMillis;
    }

    /**
     * Recent bars from Dhan's chart API (same timeframe, oldest first, still-forming bar excluded).
     * Corrects bars we built from ticks, fills bars we missed, and completes the pending decision.
     */
    public void onDhanBars(List<Candle> official) {
        if (official == null || official.isEmpty()) return;
        long currentBucket = agg.current() == null ? Long.MAX_VALUE : agg.current().startMillis;
        int fixed = 0;
        Candle pendingOfficial = null;
        boolean misaligned = false;
        for (Candle o : official) {
            if (agg.bucketStart(o.startMillis) != o.startMillis) { misaligned = true; continue; }
            if (o.startMillis >= currentBucket) continue;
            if (pendingBar != null && o.startMillis == pendingBar.startMillis) { pendingOfficial = o; continue; }
            if (!bars.isEmpty() && o.startMillis < bars.get(0).startMillis) continue;
            int i = indexOfBar(o.startMillis);
            if (i >= 0) {
                Candle b = bars.get(i);
                if (differs(b, o)) { bars.set(i, o); fixed++; }
            } else {
                int at = bars.size();
                while (at > 0 && bars.get(at - 1).startMillis > o.startMillis) at--;
                bars.add(at, o);
                fixed++;
            }
        }
        if (misaligned && barsFixed == 0 && fixed == 0) log("WARN", "Dhan's bars are not aligned to 09:15 — using the bot's own bars");
        if (fixed > 0) {
            barsFixed += fixed;
            log("INFO", "Corrected " + fixed + " bar(s) using Dhan's chart data");
            lastReading = regime.replay(bars);
        }
        if (pendingOfficial != null) {
            pendingBar = null;
            decide(pendingOfficial, true);
        }
    }

    private int indexOfBar(long start) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            long s = bars.get(i).startMillis;
            if (s == start) return i;
            if (s < start) return -1;
        }
        return -1;
    }

    private static boolean differs(Candle a, Candle b) {
        return Math.abs(a.open - b.open) > 0.01 || Math.abs(a.high - b.high) > 0.01
                || Math.abs(a.low - b.low) > 0.01 || Math.abs(a.close - b.close) > 0.01;
    }

    /** Add the closed bar and make the decision. {@code official} = Dhan's copy, else our own ticks. */
    private void decide(Candle c, boolean official) {
        boolean partial = !official && incomplete(c);
        int existing = indexOfBar(c.startMillis);
        if (existing >= 0) bars.set(existing, c); else bars.add(c);
        if (bars.size() > 600) bars.remove(0);
        lastReading = existing >= 0 ? regime.replay(bars) : regime.evaluate(bars);
        Decision d = new Decision();
        d.timeMillis = nowMillis == 0 ? c.startMillis : nowMillis;
        d.regime = lastReading;
        d.iv = ivGauge.read(atmIv());
        d.pick = StrategyGrid.pick(lastReading.confirmed, d.iv.level, cfg);
        lastDecision = d;

        if (partial) {
            d.action = "bar data incomplete (missed its start, Dhan's copy did not arrive) — no new entry or trend exit on this bar"
                    + (position != null ? "; target/stop/time exits still active" : "");
            out.onDecision(d);
            return;
        }
        if (position != null) {
            d.action = "holding " + position.type.title;
            if (StrategyGrid.regimeAgainst(position.type, lastReading.confirmed, lastReading.adx, cfg) && job == null) {
                d.action = "regime turned " + lastReading.confirmed + " → closing " + position.type.title;
                out.onDecision(d);
                startExit(ExitReason.REGIME_FLIP);
                return;
            }
            out.onDecision(d);
            return;
        }
        d.action = tryEnter(d);
        out.onDecision(d);
    }

    /** Returns a one-line description of what happened. */
    private String tryEnter(Decision d) {
        if (state != BotState.TRADING) return "no entry: bot is " + state;
        if (job != null) return "no entry: order in progress";
        String block = entryBlock();
        if (block != null) return "no entry: " + block;
        if (d.pick == StrategyGrid.Type.NONE) {
            if (lastReading.confirmed == RegimeDetector.Regime.UNKNOWN) return "no entry: regime not ready";
            if (d.iv.level == IvGauge.Level.UNKNOWN) return "no entry: IV level unknown";
            return "no entry: sideways market — INTRADAY makes no trade here (credit trades need WEEKLY; butterfly is off)";
        }
        if (tradeExpiry.isEmpty()) return "no entry: no expiry chosen";
        String filter = entryFilters(d.pick);
        if (filter != null) return "no entry: " + d.pick.title + " — " + filter;

        StrategyBuilder.Plan plan = builder.build(d.pick, spot, tradeExpiry, quotes.values());
        if (!plan.ok()) return "no entry: " + d.pick.title + " — " + plan.rejected;

        double remainingDaily = cfg.dailyLossLimit + Math.min(0, day.realizedPnl);
        double budget = Math.min(cfg.riskPerTrade, remainingDaily);
        Sizer.Result size = sizer.size(plan, budget, availableFunds);
        if (!size.ok()) return "no entry: " + d.pick.title + " — " + size.why;
        double tgtPct = d.pick == StrategyGrid.Type.LONG_CALL_BUTTERFLY ? cfg.targetPctButterfly
                : d.pick.credit ? cfg.targetPctCredit : cfg.targetPctDebit;
        double targetProfit = Double.isInfinite(size.maxProfitRs) ? cfg.riskPerTrade : tgtPct * size.maxProfitRs;
        if (size.chargesRs > 0 && targetProfit < cfg.minTargetToCharges * size.chargesRs)
            return String.format(Locale.US, "no entry: %s — target profit ₹%,.0f is less than %.0f× charges (₹%,.0f)",
                    d.pick.title, targetProfit, cfg.minTargetToCharges, size.chargesRs);

        Job j = new Job();
        j.kind = JobKind.ENTRY;
        j.plan = plan;
        j.size = size;
        j.startMillis = nowMillis;
        j.startSpot = spot;
        j.regimeText = String.valueOf(lastReading.confirmed);
        j.ivText = d.iv.level + (Double.isNaN(d.iv.vix) ? "" : String.format(Locale.US, " (VIX %.1f)", d.iv.vix));
        for (Leg l : plan.legs) j.orders.add(new LegOrder(l, l.buy, size.qty * l.ratio));
        job = j;
        String msg = String.format(Locale.US, "ENTER %s, %d lot(s), qty %d, expiry %s. Max loss ₹%,.0f, max profit %s. %s",
                plan.summary(), size.lots, size.qty, tradeExpiry, size.maxLossRs,
                Double.isInfinite(size.maxProfitRs) ? "unlimited" : String.format(Locale.US, "₹%,.0f", size.maxProfitRs),
                plan.notes.isEmpty() ? "" : plan.notes.get(0));
        log("INFO", msg);
        stepJob(nowMillis);
        return msg;
    }

    /** v1.3 filters on the picked strategy. Null = allowed. */
    private String entryFilters(StrategyGrid.Type pick) {
        boolean trend = pick.bias != StrategyGrid.Bias.RANGE;
        LocalDate today = MarketClock.date(nowMillis);
        if (cfg.eventFilter) {
            if (MarketClock.eventDay(today)) return "event day (RBI policy / Budget)";
            if (cfg.holding == Config.Holding.WEEKLY) {
                try {
                    LocalDate ev = MarketClock.eventBetween(today, LocalDate.parse(tradeExpiry));
                    if (ev != null) return "event on " + ev + " before expiry";
                } catch (RuntimeException ignored) {}
            }
        }
        if (trend && cfg.confirm15m && cfg.holding == Config.Holding.INTRADAY && agg.minutes() < 15) {
            RegimeDetector.Regime r15 = regime15.replay(resample(bars, 15)).confirmed;
            RegimeDetector.Regime want = pick.bias == StrategyGrid.Bias.UP ? RegimeDetector.Regime.UP : RegimeDetector.Regime.DOWN;
            if (r15 != RegimeDetector.Regime.UNKNOWN && r15 != want) return "15-min trend is " + r15;
        }
        if (trend && cfg.chopFilter) {
            int n = bars.size();
            if (n > cfg.adxFallBars + regime.minBars()) {
                double now = Indicators.adx(bars, cfg.adxPeriod)[0];
                double before = Indicators.adx(bars.subList(0, n - cfg.adxFallBars), cfg.adxPeriod)[0];
                if (now < before - 0.5) return String.format(Locale.US, "trend weakening (ADX %.1f, was %.1f)", now, before);
            }
            double hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE;
            for (int i = n - 1; i >= 0; i--) {
                Candle c = bars.get(i);
                if (!MarketClock.date(c.startMillis).equals(today)) break;
                hi = Math.max(hi, c.high); lo = Math.min(lo, c.low);
            }
            if (hi > lo && spot > 0 && (hi - lo) / spot < cfg.minDayRangePct)
                return String.format(Locale.US, "day's range only %.0f points (quiet market)", hi - lo);
        }
        if (trend && cfg.pcrFilter) {
            double pcr = pcr();
            if (pcr > 0 && dayOpenPcr > 0) {
                double ch = pcr / dayOpenPcr - 1;
                if (pick.bias == StrategyGrid.Bias.UP && ch < -cfg.pcrMaxChange)
                    return String.format(Locale.US, "put-call ratio fell %.0f%% today (calls being written)", -ch * 100);
                if (pick.bias == StrategyGrid.Bias.DOWN && ch > cfg.pcrMaxChange)
                    return String.format(Locale.US, "put-call ratio rose %.0f%% today (puts being written)", ch * 100);
            }
        }
        return null;
    }

    /** Put OI ÷ call OI for the trading expiry, strikes near NIFTY. 0 if no OI data. */
    public double pcr() {
        double ce = 0, pe = 0;
        for (OptionQuote q : quotes.values()) {
            if (!q.expiry.equals(tradeExpiry) || q.oi <= 0 || Math.abs(q.strike - spot) > cfg.pcrBandPts) continue;
            if (q.right == OptionQuote.Right.CE) ce += q.oi; else pe += q.oi;
        }
        return ce > 0 ? pe / ce : 0;
    }

    private void trackPcr() {
        String today = MarketClock.date(nowMillis).toString();
        if (today.equals(pcrDay) || MarketClock.minuteOfDay(nowMillis) < 9 * 60 + 30) return;
        double p = pcr();
        if (p > 0) { dayOpenPcr = p; pcrDay = today; }
    }

    /** Group bars into N-minute bars aligned to 09:15. */
    static List<Candle> resample(List<Candle> in, int minutes) {
        List<Candle> out = new ArrayList<>();
        Candle cur = null;
        long curKey = Long.MIN_VALUE;
        for (Candle c : in) {
            int m = MarketClock.minuteOfDay(c.startMillis) - (9 * 60 + 15);
            long dayStart = c.startMillis - (long) (MarketClock.minuteOfDay(c.startMillis)) * 60_000L
                    - (c.startMillis % 60_000L);
            long key = dayStart + (9 * 60 + 15 + Math.floorDiv(m, minutes) * minutes) * 60_000L;
            if (cur == null || key != curKey) {
                cur = new Candle(key, c.open, c.high, c.low, c.close);
                out.add(cur);
                curKey = key;
            } else {
                cur.high = Math.max(cur.high, c.high);
                cur.low = Math.min(cur.low, c.low);
                cur.close = c.close;
            }
        }
        return out;
    }

    /** Null if entries are allowed now, else the reason. */
    String entryBlock() {
        int m = MarketClock.minuteOfDay(nowMillis);
        if (!MarketClock.tradingDay(MarketClock.date(nowMillis))) return "market closed today (weekend or NSE holiday)";
        if (m < cfg.entryStart || m > cfg.entryEnd) return "outside entry window";
        if (nowMillis - lastSpotMillis > 10_000) return "NIFTY price is stale";
        if (day.entries >= cfg.maxTradesPerDay) return "max trades for today reached";
        if (day.consecutiveLosses >= cfg.maxConsecutiveLosses) return day.consecutiveLosses + " losses in a row today";
        if (day.realizedPnl <= -cfg.dailyLossLimit) return "daily loss limit reached";
        double cool = "FLIP".equals(lastExitKind) && cfg.reenterAfterFlip ? 0
                : "WIN".equals(lastExitKind) ? cfg.cooldownAfterWinMinutes : cfg.cooldownMinutes;
        if (day.lastExitMillis > 0 && nowMillis - day.lastExitMillis < cool * 60_000L) return "cooling down after last exit";
        return null;
    }

    private double atmIv() {
        if (spot <= 0) return 0;
        double atm = Math.round(spot / cfg.strikeStep) * cfg.strikeStep;
        double sum = 0;
        int n = 0;
        for (OptionQuote q : quotes.values())
            if (q.expiry.equals(tradeExpiry) && Math.abs(q.strike - atm) < 0.01 && q.iv > 0) { sum += q.iv; n++; }
        return n == 0 ? 0 : sum / n;
    }

    // ------------------------------------------------------------------ managing the open position

    private void manage(long now) {
        double pnl = position.markToMarket(quotes);
        if (!Double.isNaN(pnl)) {
            position.openPnl = pnl;
            double fair = position.fairMark(quotes, cfg.wideGapPct);
            position.fairPnl = Double.isNaN(fair) ? pnl : fair;
            position.lastMarkMillis = now;
            position.stale = false;
        } else if (now - position.lastMarkMillis > 30_000) {
            position.stale = true;
            if (now - lastStaleWarn > 60_000) {
                lastStaleWarn = now;
                log("WARN", "No complete prices for all legs of " + position.type.title + " — exits use the last known P&L");
            }
        }
        if (job != null) return;
        ExitReason why = exitReason(now);
        if (why != null) startExit(why);
    }

    private ExitReason exitReason(long now) {
        if (position.openPnl >= position.targetRs && position.targetRs > 0) return ExitReason.TARGET;
        // Stop uses the fair value (mid for wide quotes); a hard floor still applies on the close-now value.
        if (position.fairPnl <= -position.stopRs && position.stopRs > 0) return ExitReason.STOP;
        if (position.openPnl <= -cfg.hardStopPctOfMaxLoss * position.maxLossRs() && position.stopRs > 0) return ExitReason.STOP;
        int m = MarketClock.minuteOfDay(now);
        if (position.holding == Config.Holding.INTRADAY) {
            if (m >= cfg.squareOff && MarketClock.tradingDay(MarketClock.date(now))) return ExitReason.TIME;
            if (MarketClock.date(now).isAfter(MarketClock.date(position.entryMillis))) return ExitReason.TIME;
        } else {
            if (MarketClock.weeklyExitDue(position.expiry, now, cfg.squareOff)) return ExitReason.EXPIRY_NEAR;
            LocalDate today = MarketClock.date(now);
            if (cfg.eventFilter && MarketClock.tradingDay(today)) {
                LocalDate next = MarketClock.nextTradingDay(today.plusDays(1));
                boolean eventNext = MarketClock.eventDay(next) || MarketClock.eventBetween(today, next) != null;
                if ((eventNext && m >= cfg.squareOff) || MarketClock.eventDay(today)) return ExitReason.EVENT;
            }
            if (gapPending && m >= 9 * 60 + 20) gapPending = false;   // only the opening minutes count
            if (cfg.gapCheck && gapPending && prevDayClose > 0 && m >= 9 * 60 + 15
                    && MarketClock.date(position.entryMillis).isBefore(today)) {
                gapPending = false;
                double gap = (spot - prevDayClose) / prevDayClose;
                boolean against = position.type.bias == StrategyGrid.Bias.UP ? gap <= -cfg.gapExitPct
                        : position.type.bias == StrategyGrid.Bias.DOWN ? gap >= cfg.gapExitPct
                        : Math.abs(gap) >= cfg.gapExitPct;
                if (against) {
                    log("WARN", String.format(Locale.US, "NIFTY opened %.2f%% from yesterday's close — against the trade", gap * 100));
                    return ExitReason.GAP;
                }
            }
        }
        return null;
    }

    private void startExit(ExitReason why) {
        if (position == null || job != null) return;
        Job j = new Job();
        j.kind = JobKind.EXIT;
        j.reason = why;
        j.startMillis = nowMillis;
        // Close the short legs first (removes the open risk), then sell the long legs.
        for (Leg l : position.legs) if (!l.buy) j.orders.add(new LegOrder(l, true, position.qty * l.ratio));
        for (Leg l : position.legs) if (l.buy) j.orders.add(new LegOrder(l, false, position.qty * l.ratio));
        job = j;
        log("INFO", String.format(Locale.US, "EXIT %s — %s (open P&L ₹%,.0f)", position.type.title, why, position.openPnl));
        stepJob(nowMillis);
    }

    // ------------------------------------------------------------------ order execution

    private void stepJob(long now) {
        for (int guard = 0; guard < 10; guard++) {
            Job j = job;
            if (j == null) return;
            if (j.idx >= j.orders.size()) { finishJob(j); continue; }
            LegOrder o = j.orders.get(j.idx);
            int idxBefore = j.idx;
            if (o.req == null) {
                if (now < o.retryAfter) return;
                if (j.kind == JobKind.ENTRY && j.idx > 0 && cfg.entryMaxSpotMove > 0 && j.startSpot > 0
                        && Math.abs(spot - j.startSpot) > cfg.entryMaxSpotMove) {
                    abortEntry(String.format(Locale.US, "NIFTY moved %.0f points while placing legs", Math.abs(spot - j.startSpot)));
                    continue;
                }
                if (!submit(o, now)) {
                    if (j.kind == JobKind.ENTRY && now - j.startMillis > 30_000) abortEntry("no usable price for " + o.leg.label());
                    return;
                }
                if (o.last != null && o.last.isFinal()) handle(j, o, o.last, now);
                else return;
            } else {
                follow(j, o, now);
            }
            if (job != j) continue;          // replaced (entry aborted → unwind)
            if (j.idx == idxBefore) return;  // waiting on this leg
        }
    }

    private static boolean legDone(LegOrder o) { return o.filledNow() >= o.targetQty; }

    /** Send (or re-send) the remaining quantity of a leg. False if there is no price yet. */
    private boolean submit(LegOrder o, long now) {
        OptionQuote q = quotes.get(o.leg.securityId);
        if (q == null || !q.hasBook()) return false;
        int remaining = o.targetQty - o.doneQty;
        if (remaining <= 0) return true;
        double limit = o.buy ? q.ask + cfg.limitBuffer * (1 + o.attempts) : q.bid - cfg.limitBuffer * (1 + o.attempts);
        limit = Math.max(0.05, PaperBroker.round(limit));
        o.req = new Broker.OrderRequest(newCorrelationId(job.kind == JobKind.ENTRY ? "E" : "X"), o.leg.securityId,
                q.label(), o.buy, remaining, limit);
        o.last = null;
        o.sentMillis = now;
        if (o.firstMillis == 0) o.firstMillis = now;
        o.attempts++;
        o.cancelRequested = false;
        log("INFO", String.format(Locale.US, "%s %s qty %d limit %.2f (%s, try %d)", o.buy ? "BUY" : "SELL",
                q.label(), remaining, limit, job.kind, o.attempts));
        try {
            o.last = broker.submit(o.req);
            o.outcomeUnknown = false;
        } catch (IOException e) {
            o.outcomeUnknown = true;
            log("WARN", "Order outcome unknown (" + e.getMessage() + ") — checking by id before any retry");
        }
        return true;
    }

    private void follow(Job j, LegOrder o, long now) {
        if (o.last != null && o.last.status == Broker.Status.TRADED) { legFilled(j, o); return; }
        if (now - o.sentMillis < 1_000) return;
        Broker.OrderUpdate u;
        try {
            u = broker.status(o.req.correlationId);
        } catch (IOException e) {
            if ((now / 1000) % 10 == 0) log("WARN", "Cannot check order: " + e.getMessage());
            return;
        }
        if (u.status == Broker.Status.NOT_FOUND) {
            if (o.outcomeUnknown && now - o.sentMillis < 30_000) return;
            if (j.kind == JobKind.ENTRY && o.attempts >= 3) { legFailed(j, o, "broker never received the order"); return; }
            log("WARN", "Order " + o.req.correlationId + " never reached the broker — resending");
            o.req = null;
            return;
        }
        o.outcomeUnknown = false;
        handle(j, o, u, now);
    }

    private void handle(Job j, LegOrder o, Broker.OrderUpdate u, long now) {
        o.last = u;
        switch (u.status) {
            case TRADED:
                legFilled(j, o);
                return;
            case REJECTED:
                o.last = null;
                legFailed(j, o, "rejected: " + u.message);
                return;
            case CANCELLED:
                o.doneQty += u.filledQty;
                o.doneValue += u.filledQty * u.avgPrice;
                o.last = null;
                if (o.doneQty >= o.targetQty) { legFilled(j, o); return; }
                int maxTries = j.kind == JobKind.ENTRY ? 3 : 1_000_000;
                if (o.attempts >= maxTries) { legFailed(j, o, "not filled after " + o.attempts + " tries"); return; }
                o.req = null;   // re-price on the next step
                return;
            default:
                long age = now - o.sentMillis;
                long timeout = j.kind == JobKind.ENTRY ? 8_000 : 5_000;
                if (age > timeout && !o.cancelRequested && u.orderId != null && !u.orderId.isEmpty()) {
                    o.cancelRequested = true;
                    log("INFO", "Re-pricing " + o.req.label + " (not filled in " + age / 1000 + "s)");
                    try { broker.cancel(u.orderId); } catch (IOException e) { log("WARN", "Cancel failed: " + e.getMessage()); }
                }
                if (o.cancelRequested && age > 45_000 && state != BotState.ERROR) {
                    pause("order stuck");
                    setState(BotState.ERROR, "order " + o.req.label + " not confirmed after cancel");
                    out.onAlert("Order " + o.req.label + " is stuck (no fill, no cancel confirmation). Check Dhan now.");
                }
        }
    }

    private void legFilled(Job j, LegOrder o) {
        if (o.last != null && o.last.status == Broker.Status.TRADED) {
            o.doneQty += o.last.filledQty;
            o.doneValue += o.last.filledQty * o.last.avgPrice;
            o.last = null;
        }
        if (o.doneQty < o.targetQty) { o.req = null; return; } // partly filled: send the rest
        log("INFO", String.format(Locale.US, "Filled %s %s qty %d @ %.2f", o.buy ? "BUY" : "SELL",
                o.leg.label(), o.doneQty, o.doneQty == 0 ? 0 : o.doneValue / o.doneQty));
        j.idx++;
    }

    private void legFailed(Job j, LegOrder o, String why) {
        log("WARN", "Leg " + o.leg.label() + " failed: " + why);
        if (j.kind == JobKind.ENTRY) { abortEntry(o.leg.label() + " " + why); return; }
        // EXIT / UNWIND never gives up: alert and retry with a fresh price after a short wait.
        o.rejects++;
        o.req = null;
        o.last = null;
        o.retryAfter = nowMillis + 5_000;
        if (o.rejects == 1 || o.rejects % 10 == 0)
            out.onAlert("Exit order problem on " + o.leg.label() + ": " + why + ". Retrying every 5s. Check Dhan.");
        if (o.rejects >= 3 && state != BotState.ERROR) {
            pause("exit trouble");
            setState(BotState.ERROR, "exit order trouble on " + o.leg.label());
        }
    }

    /** Stop an entry part-way: close whatever legs already filled (unwind). */
    private void abortEntry(String why) {
        Job j = job;
        if (j == null || j.kind != JobKind.ENTRY) return;
        // Cancel the working order, counting any partial fill.
        LegOrder cur = j.idx < j.orders.size() ? j.orders.get(j.idx) : null;
        if (cur != null && cur.last != null) {
            Broker.OrderUpdate u = cur.last;
            if (!u.isFinal() && u.orderId != null && !u.orderId.isEmpty()) {
                try { broker.cancel(u.orderId); } catch (IOException e) { log("WARN", "Cancel failed: " + e.getMessage()); }
                try { u = broker.status(cur.req.correlationId); } catch (IOException ignored) { }
            }
            if (u.filledQty > 0) { cur.doneQty += u.filledQty; cur.doneValue += u.filledQty * u.avgPrice; }
            cur.last = null;
        }
        Job un = new Job();
        un.kind = JobKind.UNWIND;
        un.reason = ExitReason.UNWIND;
        un.startMillis = nowMillis;
        for (int k = j.orders.size() - 1; k >= 0; k--) {
            LegOrder o = j.orders.get(k);
            if (o.doneQty > 0) {
                Leg filled = new Leg(o.leg.securityId, o.leg.right, o.leg.strike, o.leg.expiry, o.leg.buy, 1, o.doneValue / o.doneQty);
                un.orders.add(new LegOrder(filled, !o.buy, o.doneQty));
            }
        }
        job = null;
        if (un.orders.isEmpty()) {
            log("INFO", "Entry cancelled (" + why + "); nothing was filled");
            return;
        }
        out.onAlert("Entry stopped part-way (" + why + "). Closing the " + un.orders.size() + " filled leg(s) now.");
        job = un;
    }

    private void finishJob(Job j) {
        job = null;
        switch (j.kind) {
            case ENTRY: openPosition(j); break;
            case EXIT: closePosition(j); break;
            case UNWIND: unwound(j); break;
        }
    }

    private void openPosition(Job j) {
        Position p = new Position();
        p.type = j.plan.type;
        p.qty = j.size.qty;
        p.expiry = j.plan.expiry;
        p.holding = cfg.holding;
        p.entryMillis = nowMillis;
        p.live = broker.isLive();
        for (LegOrder o : j.orders) {
            Leg l = o.leg;
            p.legs.add(new Leg(l.securityId, l.right, l.strike, l.expiry, l.buy, l.ratio, o.doneValue / Math.max(1, o.doneQty)));
        }
        Payoff po = Payoff.of(p.legs);
        p.netPremium = po.netPremium;
        p.maxProfit = po.maxProfit;
        p.maxLoss = po.maxLoss;
        double pct = p.type == StrategyGrid.Type.LONG_CALL_BUTTERFLY ? cfg.targetPctButterfly
                : p.type.credit ? cfg.targetPctCredit : cfg.targetPctDebit;
        p.targetRs = Double.isInfinite(p.maxProfit) ? cfg.riskPerTrade : pct * p.maxProfitRs();
        p.stopRs = cfg.stopPctOfMaxLoss * p.maxLossRs();
        p.entryCharges = sizer.chargesFor(j.plan, p.qty) / 2;
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

    private void closePosition(Job j) {
        Position p = position;
        if (p == null) return;
        double gross = 0, exitNet = 0, chargesRs = 0;
        for (LegOrder o : j.orders) {
            double px = o.doneValue / Math.max(1, o.doneQty);
            Leg l = o.leg;
            gross += (l.buy ? px - l.price : l.price - px) * o.doneQty;
            exitNet += (l.buy ? px : -px) * l.ratio;          // what closing gives back per unit
            double buyPx = l.buy ? l.price : px, sellPx = l.buy ? px : l.price;
            chargesRs += charges.roundTrip(buyPx, sellPx, o.doneQty);
        }
        TradeRecord t = new TradeRecord();
        t.strategy = p.type.title;
        t.legs = p.summary();
        t.regime = entryRegimeText;
        t.iv = entryIvText;
        t.mode = p.live ? "LIVE" : "PAPER";
        t.holding = p.holding.name();
        t.qty = p.qty;
        t.lots = p.qty / Math.max(1, cfg.lotSize);
        t.entryNet = p.netPremium;
        t.exitNet = exitNet;
        t.gross = gross;
        t.charges = chargesRs;
        t.net = gross - chargesRs;
        t.maxLossRs = p.maxLossRs();
        t.entryMillis = p.entryMillis;
        t.exitMillis = nowMillis;
        t.exitReason = j.reason == null ? "" : j.reason.name();

        day.realizedPnl += t.net;
        if (t.net >= 0) { day.wins++; day.consecutiveLosses = 0; } else { day.losses++; day.consecutiveLosses++; }
        day.lastExitMillis = nowMillis;
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

    private void unwound(Job j) {
        double pnl = 0;
        for (LegOrder o : j.orders) {
            double px = o.doneValue / Math.max(1, o.doneQty);
            Leg l = o.leg;   // l.buy = original direction, price = original fill
            pnl += (l.buy ? px - l.price : l.price - px) * o.doneQty;
            pnl -= charges.roundTrip(l.buy ? l.price : px, l.buy ? px : l.price, o.doneQty);
        }
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
            LocalDate prev = LocalDate.parse(day.date), cur = MarketClock.date(now);
            DayStats next = new DayStats();
            next.date = today;
            boolean sameWeek = prev.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .equals(cur.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            boolean sameMonth = prev.getYear() == cur.getYear() && prev.getMonth() == cur.getMonth();
            next.weekPnlBefore = sameWeek ? day.weekPnlBefore + day.realizedPnl : 0;
            next.monthPnlBefore = sameMonth ? day.monthPnlBefore + day.realizedPnl : 0;
            day = next;
            log("INFO", "New trading day " + today);
        } else {
            day.date = today;
        }
        out.onStatsChanged(day);
    }

    private String newCorrelationId(String kind) {
        return "NRB" + kind + (nowMillis / 1000 % 100_000_000) + (++corrSeq % 100);
    }

    private void log(String level, String msg) { out.onLog(level, msg); }
}
