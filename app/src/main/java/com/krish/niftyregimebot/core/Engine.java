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

    public enum ExitReason { TARGET, STOP, REGIME_FLIP, TIME, EXPIRY_NEAR, EMERGENCY, UNWIND }

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

    public Engine(Config cfg, Charges charges, Broker broker, Listener out) {
        this.cfg = cfg;
        this.charges = charges;
        this.broker = broker;
        this.out = out;
        this.regime = new RegimeDetector(cfg);
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
        stepJob(now);
        if (job == null && emergencyRequested) {
            if (position != null) startExit(ExitReason.EMERGENCY);
            else emergencyRequested = false;
        }
        if (position != null) manage(now);
    }

    // ------------------------------------------------------------------ bars → decisions

    @Override public void onBarClosed(Candle c) {
        bars.add(c);
        if (bars.size() > 600) bars.remove(0);
        lastReading = regime.evaluate(bars);
        Decision d = new Decision();
        d.timeMillis = nowMillis == 0 ? c.startMillis : nowMillis;
        d.regime = lastReading;
        d.iv = ivGauge.read(atmIv());
        d.pick = StrategyGrid.pick(lastReading.confirmed, d.iv.level);
        lastDecision = d;

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
            return "no entry: " + (lastReading.confirmed == RegimeDetector.Regime.UNKNOWN ? "regime not ready" : "IV level unknown");
        }
        if (tradeExpiry.isEmpty()) return "no entry: no expiry chosen";

        StrategyBuilder.Plan plan = builder.build(d.pick, spot, tradeExpiry, quotes.values());
        if (!plan.ok()) return "no entry: " + d.pick.title + " — " + plan.rejected;

        double remainingDaily = cfg.dailyLossLimit + Math.min(0, day.realizedPnl);
        double budget = Math.min(cfg.riskPerTrade, remainingDaily);
        Sizer.Result size = sizer.size(plan, budget, availableFunds);
        if (!size.ok()) return "no entry: " + d.pick.title + " — " + size.why;

        Job j = new Job();
        j.kind = JobKind.ENTRY;
        j.plan = plan;
        j.size = size;
        j.startMillis = nowMillis;
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

    /** Null if entries are allowed now, else the reason. */
    String entryBlock() {
        int m = MarketClock.minuteOfDay(nowMillis);
        if (!MarketClock.weekday(MarketClock.date(nowMillis))) return "weekend";
        if (m < cfg.entryStart || m > cfg.entryEnd) return "outside entry window";
        if (nowMillis - lastSpotMillis > 10_000) return "NIFTY price is stale";
        if (day.entries >= cfg.maxTradesPerDay) return "max trades for today reached";
        if (day.consecutiveLosses >= cfg.maxConsecutiveLosses) return day.consecutiveLosses + " losses in a row today";
        if (day.realizedPnl <= -cfg.dailyLossLimit) return "daily loss limit reached";
        if (day.lastExitMillis > 0 && nowMillis - day.lastExitMillis < cfg.cooldownMinutes * 60_000L) return "cooling down after last exit";
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
        if (position.openPnl <= -position.stopRs && position.stopRs > 0) return ExitReason.STOP;
        int m = MarketClock.minuteOfDay(now);
        if (position.holding == Config.Holding.INTRADAY) {
            if (m >= cfg.squareOff && MarketClock.weekday(MarketClock.date(now))) return ExitReason.TIME;
            if (MarketClock.date(now).isAfter(MarketClock.date(position.entryMillis))) return ExitReason.TIME;
        } else if (MarketClock.weeklyExitDue(position.expiry, now, cfg.squareOff)) {
            return ExitReason.EXPIRY_NEAR;
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
