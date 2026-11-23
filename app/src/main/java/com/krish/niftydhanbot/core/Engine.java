package com.krish.niftydhanbot.core;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The trading brain. Single-threaded: every public method must be called from the
 * same engine thread (the Android service guarantees this).
 *
 * Flow: ticks → 1-min candles → signal → strike → size → risk gate → order →
 * confirm fill → manage exits. Exits never depend on signals, news, or the app screen.
 */
public final class Engine implements CandleBuilder.Sink {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public interface Listener {
        void onLog(String level, String message);
        void onSignal(Signal s);
        void onStateChanged(BotState s, String why);
        void onPositionChanged(Position p);   // null when flat
        void onTradeClosed(TradeRecord t);
        void onStatsChanged(DayStats d);
        void onAlert(String message);         // important: also sent to phone notification/Telegram
    }

    /** One open Dhan F&O position, used when checking what is really in the account. */
    public static final class BrokerPosition {
        public final String securityId, symbol;
        public final int netQty;
        public BrokerPosition(String securityId, String symbol, int netQty) {
            this.securityId = securityId; this.symbol = symbol; this.netQty = netQty;
        }
    }

    private enum Purpose { ENTRY, EXIT }

    private static final class Pending {
        Purpose purpose;
        Broker.OrderRequest req;
        Broker.OrderUpdate last;
        long sentMillis;
        boolean outcomeUnknown;          // submit timed out — must query before anything else
        ExitManager.Reason exitReason;
        double stopDistance;
        Signal.Side side;
        int attempts;
        boolean cancelRequested;
    }

    private final BotConfig cfg;
    private final Charges charges;
    private final Broker broker;
    private final Listener out;
    private final SignalEngine signals;
    private final StrikeSelector selector;
    private final PositionSizer sizer;
    private final RiskGate gate;
    private final ExitManager exits;
    private final CandleBuilder builder = new CandleBuilder(this);
    private final List<Candle> bars = new ArrayList<>();
    private final Map<String, OptionQuote> quotes = new LinkedHashMap<>();

    private BotState state = BotState.OFF;
    private DayStats day = new DayStats();
    private Position position;
    private Pending pending;
    private Signal lastSignal;
    private double spot;
    private long lastSpotMillis;
    private double availableFunds;
    private long nowMillis;
    private int corrSeq;
    private boolean emergencyRequested;
    private final java.util.Set<String> acknowledgedIds = new java.util.HashSet<>();
    private final java.util.Set<String> lastStrangerIds = new java.util.HashSet<>();

    public Engine(BotConfig cfg, Charges charges, Broker broker, Listener out) {
        this.cfg = cfg;
        this.charges = charges;
        this.broker = broker;
        this.out = out;
        this.signals = new SignalEngine(cfg);
        this.selector = new StrikeSelector(cfg);
        this.sizer = new PositionSizer(cfg, charges);
        this.gate = new RiskGate(cfg);
        this.exits = new ExitManager(cfg);
        this.availableFunds = cfg.capital;
    }

    // ------------------------------------------------------------------ state control

    public BotState state() { return state; }
    public Position position() { return position; }
    public DayStats day() { return day; }
    public Signal lastSignal() { return lastSignal; }
    public double spot() { return spot; }
    public long lastSpotMillis() { return lastSpotMillis; }
    public Collection<OptionQuote> quotes() { return quotes.values(); }
    public OptionQuote quote(String securityId) { return quotes.get(securityId); }
    public boolean hasPendingOrder() { return pending != null; }
    public List<Candle> bars() { return bars; }

    public void setState(BotState s, String why) {
        if (s == state) return;
        state = s;
        out.onStateChanged(s, why);
        log("INFO", "State → " + s + (why == null || why.isEmpty() ? "" : " (" + why + ")"));
    }

    /** User pressed Start trading. Only allowed from READY or PAUSED. */
    public boolean startTrading() {
        if (state == BotState.READY || state == BotState.PAUSED) { setState(BotState.TRADING, "started by you"); return true; }
        log("WARN", "Cannot start trading while " + state);
        return false;
    }

    /** No new entries. Exits keep working. */
    public void pause(String why) {
        if (state == BotState.TRADING) setState(BotState.PAUSED, why);
    }

    /** User checked the account after a RECOVERY warning. */
    public void acknowledgeRecovery() {
        acknowledgedIds.addAll(lastStrangerIds);
        if (state == BotState.RECOVERY || state == BotState.ERROR) setState(BotState.READY, "you confirmed the account is OK");
    }

    public void restoreDay(DayStats saved) { if (saved != null) day = saved; }

    public void setAvailableFunds(double f) { availableFunds = f; }

    /** Close the open position now at the best available price. */
    public void emergencyExit(long now) {
        nowMillis = now;
        pause("emergency exit");
        if (position != null || pending != null) {
            emergencyRequested = true;
            if (position != null) sendExit(ExitManager.Reason.EMERGENCY);
            else cancelPending("emergency exit");
        } else {
            log("INFO", "Emergency exit: nothing open");
        }
    }

    // ------------------------------------------------------------------ data in

    /** Warm up with today's earlier candles (from Dhan intraday chart API). */
    public void seedCandles(List<Candle> history) {
        bars.clear();
        bars.addAll(history);
        log("INFO", "Loaded " + history.size() + " earlier 1-min candles");
    }

    public void onSpot(double price, long now) {
        nowMillis = now;
        if (price <= 0) return;
        spot = price;
        lastSpotMillis = now;
        builder.onTick(price, now);
    }

    /** Register/refresh a contract from the option chain (delta, OI, and a snapshot quote). */
    public OptionQuote upsertContract(String securityId, Signal.Side side, double strike,
                                      double delta, double iv, double oi, double ltp, double bid, double ask, long now) {
        OptionQuote q = quotes.get(securityId);
        if (q == null) { q = new OptionQuote(securityId, side, strike); quotes.put(securityId, q); }
        q.delta = delta; q.iv = iv; q.oi = oi;
        // Chain snapshot only fills in prices if the live feed has not updated more recently.
        if (now - q.updatedMillis > 2_000) {
            q.ltp = ltp; q.bid = bid; q.ask = ask; q.updatedMillis = now;
        }
        return q;
    }

    /** Live feed update for an option (best bid/ask from market depth). */
    public void onOptionTick(String securityId, double ltp, double bid, double ask, long now) {
        nowMillis = now;
        OptionQuote q = quotes.get(securityId);
        if (q == null) return;
        if (ltp > 0) q.ltp = ltp;
        if (bid > 0) q.bid = bid;
        if (ask > 0) q.ask = ask;
        q.updatedMillis = now;
        if (position != null && position.securityId.equals(securityId)) manageExit(q.bid, now);
    }

    /** Compare with the real Dhan account. An F&O position the bot does not own blocks entries. */
    public void reconcile(List<BrokerPosition> brokerPositions) {
        List<String> strangers = new ArrayList<>();
        lastStrangerIds.clear();
        for (BrokerPosition b : brokerPositions) {
            if (b.netQty == 0) continue;
            boolean ours = broker.isLive() && position != null && position.securityId.equals(b.securityId);
            if (ours || acknowledgedIds.contains(b.securityId)) continue;
            strangers.add(b.symbol + " qty " + b.netQty);
            lastStrangerIds.add(b.securityId);
        }
        if (!strangers.isEmpty() && state != BotState.RECOVERY) {
            String msg = "Open F&O position at Dhan that the bot did not open: " + String.join(", ", strangers)
                    + ". New entries blocked until you check it.";
            pause("account check");
            setState(BotState.RECOVERY, "unexpected position");
            out.onAlert(msg);
        }
        if (broker.isLive() && position != null) {
            boolean found = false;
            for (BrokerPosition b : brokerPositions) if (b.securityId.equals(position.securityId) && b.netQty > 0) found = true;
            if (!found && pending == null) {
                out.onAlert("Bot thinks it holds " + position.label + " but Dhan shows none. Check the app at Dhan.");
                setState(BotState.RECOVERY, "position mismatch");
            }
        }
    }

    // ------------------------------------------------------------------ clock

    /** Call about once a second. */
    public void onTimer(long now) {
        nowMillis = now;
        rollDayIfNeeded(now);
        builder.closeIfDue(now);
        followPending(now);
        if (emergencyRequested) {
            if (position == null && pending == null) emergencyRequested = false;
            else if (position != null && pending == null) sendExit(ExitManager.Reason.EMERGENCY);
        }
        if (position != null) {
            // Use the newest price from ANY source (live feed or option-chain refresh),
            // so exits keep working even if the feed's depth packets stop.
            OptionQuote q = quotes.get(position.securityId);
            if (q != null && q.bid > 0 && q.updatedMillis > position.lastQuoteMillis) {
                manageExit(q.bid, q.updatedMillis);
            } else {
                if (now - position.lastQuoteMillis > 10_000 && (now / 1000) % 15 == 0)
                    log("WARN", "No fresh price for " + position.label + " for " + (now - position.lastQuoteMillis) / 1000 + "s");
                manageExit(position.lastBid, now);
            }
        }
    }

    // ------------------------------------------------------------------ signals and entries

    @Override public void onCandleClosed(Candle c) {
        if (!sameIstDay(c.startMillis, nowMillis == 0 ? c.startMillis : nowMillis)) return;
        bars.add(c);
        if (bars.size() > 600) bars.remove(0);
        Signal s = signals.evaluate(bars, nowMillis);
        lastSignal = s;
        out.onSignal(s);
        if (s.side != Signal.Side.NO_TRADE) tryEnter(s);
    }

    private void tryEnter(Signal s) {
        List<String> why = new ArrayList<>(s.reasons);
        if (state != BotState.TRADING) {
            log("INFO", s.side + " signal ignored — bot is " + state);
            return;
        }
        if (position != null || pending != null) return;
        OptionQuote q = selector.pick(s.side, s.spot, quotes.values(), nowMillis, why);
        PositionSizer.Result size = null;
        double limit = 0, stop = 0, stopPts = 0;
        if (q != null) {
            limit = PaperBroker.round(q.ask + cfg.entryBufferPts);
            double d = Math.abs(q.delta) > 0 ? Math.abs(q.delta) : 0.5;
            stopPts = PaperBroker.round(Math.max(cfg.minStopPoints, d * cfg.stopAtrMultiple * s.atr));
            stop = q.ask - stopPts;
            if (stop > 0) size = sizer.size(q.ask, stop, availableFunds);
        }
        RiskGate.Request r = new RiskGate.Request();
        r.state = state;
        r.day = day;
        r.openPositions = position == null ? 0 : 1;
        r.pendingOrder = pending != null;
        r.openPnl = position == null ? 0 : position.openPnl();
        r.minuteOfDayIst = minuteOfDay(nowMillis);
        r.now = nowMillis;
        r.lastSpotMillis = lastSpotMillis;
        r.quote = q;
        r.size = size;
        r.availableFunds = availableFunds;
        r.limitPrice = limit;
        RiskGate.Decision d = gate.check(r);
        if (!d.allowed) {
            log("INFO", s.side + " signal blocked: " + d.text());
            return;
        }
        Pending p = new Pending();
        p.purpose = Purpose.ENTRY;
        p.side = s.side;
        p.stopDistance = stopPts;
        p.req = new Broker.OrderRequest(newCorrelationId("E"), q.securityId, q.label(), true, size.qty, limit);
        log("INFO", String.format(Locale.US, "BUY %s qty %d (%d lots) limit %.2f, stop %.2f, worst case ₹%.0f — %s",
                q.label(), size.qty, size.lots, limit, stop, size.worstCaseLoss, String.join("; ", why)));
        send(p);
    }

    // ------------------------------------------------------------------ exits

    private void manageExit(double bid, long now) {
        if (position == null) return;
        if (pending != null && pending.purpose == Purpose.EXIT) return;
        double costPerUnit = charges.roundTrip(position.entryPrice, position.entryPrice, position.qty) / position.qty;
        double oldStop = position.stop;
        exits.onQuote(position, bid, now, costPerUnit);
        if (position.stop != oldStop) {
            log("INFO", String.format(Locale.US, "Stop moved %.2f → %.2f (%s)", oldStop, position.stop, position.stopReason));
            out.onPositionChanged(position);
        }
        ExitManager.Reason why = exits.check(position, now, minuteOfDay(now));
        if (why != ExitManager.Reason.NONE) sendExit(why);
    }

    private void sendExit(ExitManager.Reason why) {
        if (position == null) return;
        if (pending != null) {
            if (pending.purpose == Purpose.EXIT) return;
            // An entry order is still working — cancel it first; exit retried on next timer.
            cancelPending("exit needed");
            return;
        }
        OptionQuote q = quotes.get(position.securityId);
        double bid = q != null && q.bid > 0 ? q.bid : position.lastBid;
        double limit = Math.max(0.05, PaperBroker.round(bid - 1.0)); // marketable limit
        Pending p = new Pending();
        p.purpose = Purpose.EXIT;
        p.exitReason = why;
        p.req = new Broker.OrderRequest(newCorrelationId("X"), position.securityId, position.label, false, position.qty, limit);
        log("INFO", String.format(Locale.US, "SELL %s qty %d limit %.2f — %s", position.label, position.qty, limit, why));
        send(p);
    }

    // ------------------------------------------------------------------ order lifecycle

    private void send(Pending p) {
        pending = p;
        p.sentMillis = nowMillis;
        p.attempts++;
        try {
            p.last = broker.submit(p.req);
            p.outcomeUnknown = false;
            handleUpdate(p.last);
        } catch (IOException e) {
            p.outcomeUnknown = true;
            log("WARN", "Order outcome unknown (" + e.getMessage() + ") — will check by id " + p.req.correlationId + " before any retry");
        }
    }

    private void followPending(long now) {
        Pending p = pending;
        if (p == null) return;
        if (now - p.sentMillis < 1_000) return;
        try {
            Broker.OrderUpdate u = broker.status(p.req.correlationId);
            if (u.status == Broker.Status.NOT_FOUND) {
                if (p.outcomeUnknown && now - p.sentMillis < 30_000) return; // may still be in transit
                log("WARN", "Order " + p.req.correlationId + " was never received by broker");
                Purpose pp = p.purpose;
                pending = null;
                if (pp == Purpose.EXIT && position != null) sendExit(p.exitReason);
                return;
            }
            p.outcomeUnknown = false;
            p.last = u;
            handleUpdate(u);
            if (pending != p) return; // resolved
            long age = now - p.sentMillis;
            if (p.purpose == Purpose.ENTRY && age > 10_000 && !p.cancelRequested) cancelPending("entry not filled in 10s");
            if (p.purpose == Purpose.EXIT && age > 5_000 && !p.cancelRequested) cancelPending("exit not filled in 5s, re-pricing");
            if (p.cancelRequested && age > 40_000 && state != BotState.ERROR) {
                pause("order stuck");
                setState(BotState.ERROR, "order " + p.req.correlationId + " not confirmed after cancel");
                out.onAlert("Order " + p.req.label + " is stuck (no fill, no cancel confirmation). Check Dhan now.");
            }
        } catch (IOException e) {
            if ((now / 1000) % 10 == 0) log("WARN", "Cannot check order status: " + e.getMessage());
        }
    }

    private void cancelPending(String why) {
        Pending p = pending;
        if (p == null || p.last == null || p.last.orderId.isEmpty()) return;
        p.cancelRequested = true;
        log("INFO", "Cancelling " + p.req.correlationId + " — " + why);
        try { broker.cancel(p.last.orderId); } catch (IOException e) { log("WARN", "Cancel failed: " + e.getMessage()); }
    }

    private void handleUpdate(Broker.OrderUpdate u) {
        Pending p = pending;
        if (p == null) return;
        switch (u.status) {
            case TRADED:
                pending = null;
                if (p.purpose == Purpose.ENTRY) openPosition(p, u.filledQty, u.avgPrice);
                else closePosition(p, u.filledQty, u.avgPrice);
                break;
            case REJECTED:
                pending = null;
                log("WARN", "Order rejected: " + u.message);
                out.onAlert("Order rejected: " + p.req.label + " — " + u.message);
                if (p.purpose == Purpose.EXIT) {
                    pause("exit order rejected");
                    setState(BotState.ERROR, "exit rejected — check Dhan now");
                }
                break;
            case CANCELLED:
                pending = null;
                if (u.filledQty > 0) { // partly filled then cancelled
                    if (p.purpose == Purpose.ENTRY) openPosition(p, u.filledQty, u.avgPrice);
                    else closePosition(p, u.filledQty, u.avgPrice);
                } else {
                    log("INFO", "Order cancelled with nothing filled");
                }
                if (p.purpose == Purpose.EXIT && position != null) sendExit(p.exitReason); // re-price and retry
                break;
            default:
                break; // PENDING / PART_TRADED: keep following
        }
    }

    private void openPosition(Pending p, int qty, double price) {
        if (qty <= 0) return;
        // Keep the planned stop DISTANCE, measured from the actual fill price.
        double stop = PaperBroker.round(price - p.stopDistance);
        double target = PaperBroker.round(price + cfg.targetR * p.stopDistance);
        position = new Position(p.req.securityId, p.req.label, p.side, qty, price, nowMillis, stop, target);
        day.entries++;
        out.onStatsChanged(day);
        out.onPositionChanged(position);
        out.onAlert(String.format(Locale.US, "Bought %s qty %d @ %.2f (stop %.2f)", position.label, qty, price, position.stop));
    }

    private void closePosition(Pending p, int qty, double price) {
        Position pos = position;
        if (pos == null || qty <= 0) return;
        TradeRecord t = new TradeRecord();
        t.label = pos.label;
        t.side = pos.side.name();
        t.mode = broker.isLive() ? "LIVE" : "PAPER";
        t.qty = qty;
        t.entry = pos.entryPrice;
        t.exit = price;
        t.entryMillis = pos.entryMillis;
        t.exitMillis = nowMillis;
        t.exitReason = p.exitReason == null ? "" : p.exitReason.name();
        t.gross = (price - pos.entryPrice) * qty;
        t.charges = charges.roundTrip(pos.entryPrice, price, qty);
        t.net = t.gross - t.charges;

        day.realizedPnl += t.net;
        if (qty >= pos.qty) {
            if (t.net >= 0) { day.wins++; day.consecutiveLosses = 0; } else { day.losses++; day.consecutiveLosses++; }
            day.lastExitMillis = nowMillis;
            position = null;
        } else {
            pos.qty -= qty; // partial exit: keep managing the rest
        }
        out.onTradeClosed(t);
        out.onStatsChanged(day);
        out.onPositionChanged(position);
        out.onAlert(String.format(Locale.US, "Sold %s qty %d @ %.2f — %s, net ₹%.0f (day ₹%.0f)",
                t.label, qty, price, t.exitReason, t.net, day.realizedPnl));

        if (position == null) {
            if (day.consecutiveLosses >= cfg.maxConsecutiveLosses) out.onAlert("Stopped for today: " + day.consecutiveLosses + " losses in a row");
            if (day.realizedPnl <= -cfg.dailyLossLimit) out.onAlert(String.format(Locale.US, "Daily loss limit reached (₹%.0f). No more entries today.", day.realizedPnl));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void rollDayIfNeeded(long now) {
        String today = istDate(now).toString();
        if (today.equals(day.date)) return;
        if (!day.date.isEmpty()) {
            LocalDate prev = LocalDate.parse(day.date);
            LocalDate cur = istDate(now);
            DayStats next = new DayStats();
            next.date = today;
            boolean sameWeek = prev.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .equals(cur.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            boolean sameMonth = prev.getYear() == cur.getYear() && prev.getMonth() == cur.getMonth();
            next.weekPnlBefore = sameWeek ? day.weekPnlBefore + day.realizedPnl : 0;
            next.monthPnlBefore = sameMonth ? day.monthPnlBefore + day.realizedPnl : 0;
            day = next;
            bars.clear();
            log("INFO", "New trading day " + today);
        } else {
            day.date = today;
        }
        out.onStatsChanged(day);
    }

    private String newCorrelationId(String kind) {
        // Dhan allows up to 30 chars; unique per day and per run.
        return "NDB" + kind + (nowMillis / 1000 % 100_000_000) + (++corrSeq % 100);
    }

    public static int minuteOfDay(long millis) {
        ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(IST);
        return z.getHour() * 60 + z.getMinute();
    }

    public static LocalDate istDate(long millis) { return Instant.ofEpochMilli(millis).atZone(IST).toLocalDate(); }

    static boolean sameIstDay(long a, long b) { return istDate(a).equals(istDate(b)); }

    private void log(String level, String msg) { out.onLog(level, msg); }
}
