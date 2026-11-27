package com.krish.niftyregimebot.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays past NIFTY and option prices through the SAME {@link Engine} the live bot uses, with a
 * {@link PaperBroker} on a simulated clock, and reports the result after charges.
 *
 * Input is 5-minute data: NIFTY bars, plus Dhan "rolling" option bars (ATM-relative strikes,
 * near and next weekly expiry). Each 5-minute bar is played as four price steps
 * (open, high/low, low/high, close) with option prices at the bar's open then its close.
 *
 * Simplifications (reported in the result):
 * - bid/ask are made from the bar price with a fixed half-gap ({@link #halfGapPct});
 * - a held strike that drops out of Dhan's ATM±10 window is priced at intrinsic + ₹0.5;
 * - India VIX uses the previous day's close (no intraday VIX);
 * - delta is worked out from the option's IV (Black-Scholes, no rates).
 */
public final class Backtester {

    /** One option bar from Dhan's rolling data. */
    public static final class OptBar {
        public final long t;            // bar start, epoch ms
        public final int expiryCode;    // 1 = near weekly, 2 = next weekly
        public final OptionQuote.Right right;
        public final double strike, open, close, iv;

        public OptBar(long t, int expiryCode, OptionQuote.Right right, double strike, double open, double close, double iv) {
            this.t = t; this.expiryCode = expiryCode; this.right = right; this.strike = strike;
            this.open = open; this.close = close; this.iv = iv;
        }
    }

    /** Everything the test needs. Fill it from Dhan, then call {@link #run}. */
    public static final class Data {
        public final List<Candle> warmup = new ArrayList<>();     // bars in the bot's timeframe before the test start
        public final List<Candle> spot5 = new ArrayList<>();      // 5-minute NIFTY bars for the test days
        public final TreeMap<LocalDate, Double> vixCloses = new TreeMap<>();
        public final List<OptBar> options = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();      // data problems found while loading
    }

    public interface Progress { void update(String text); }

    public static final class StrategyStats { public int trades, wins; public double net; }

    public static final class Report {
        public LocalDate from, to;
        public String holding = "";
        public int days, tradingDaysWithData;
        public final List<TradeRecord> trades = new ArrayList<>();
        public int wins, losses, worstLosingStreak;
        public double gross, charges, net, avgWin, avgLoss, maxDrawdown, bestTrade, worstTrade;
        public final Map<String, StrategyStats> byStrategy = new LinkedHashMap<>();
        public final Map<String, Integer> byExit = new LinkedHashMap<>();
        public final Map<String, Integer> skipReasons = new LinkedHashMap<>();
        public final Map<LocalDate, Double> dailyNet = new TreeMap<>();
        public final List<String> notes = new ArrayList<>();
        public int decisions;
        public double missingQuotePct;
        public boolean expiryRollGuessed;

        public double winRate() { return trades.isEmpty() ? 0 : 100.0 * wins / trades.size(); }
        public double tradesPerDay() { return tradingDaysWithData == 0 ? 0 : (double) trades.size() / tradingDaysWithData; }

        /** Plain-text summary for the app screen. */
        public String text() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "%s to %s · %s · %d trading days\n", from, to, holding, tradingDaysWithData));
            b.append(String.format(Locale.US, "Trades %d (%.1f a day) · wins %d · losses %d · win rate %.0f%%\n",
                    trades.size(), tradesPerDay(), wins, losses, winRate()));
            b.append(String.format(Locale.US, "Net ₹%,.0f after charges ₹%,.0f (gross ₹%,.0f)\n", net, charges, gross));
            b.append(String.format(Locale.US, "Average win ₹%,.0f · average loss ₹%,.0f\n", avgWin, avgLoss));
            b.append(String.format(Locale.US, "Best ₹%,.0f · worst ₹%,.0f · worst losing streak %d · max drawdown ₹%,.0f\n",
                    bestTrade, worstTrade, worstLosingStreak, maxDrawdown));
            return b.toString();
        }
    }

    public double halfGapPct = 0.004;        // bid/ask = price ∓ 0.4% (at least ₹0.05)
    public double minHalfGap = 0.05;

    private final Map<String, OptionQuote.Right> idRight = new HashMap<>();

    /** Run the test. {@code cfg} is copied in spirit: callers pass a fresh Config. */
    public Report run(Config cfg, Data data, Progress progress) {
        cfg.dhanBarWaitMillis = 0;           // bars are complete in history
        Report rep = new Report();
        rep.holding = cfg.holding.name();
        rep.notes.addAll(data.notes);
        if (data.spot5.isEmpty()) { rep.notes.add("No NIFTY bars — nothing to test"); return rep; }

        // ---- index option bars by bar time
        TreeMap<Long, List<OptBar>> byTime = new TreeMap<>();
        for (OptBar o : data.options) byTime.computeIfAbsent(o.t, k -> new ArrayList<>()).add(o);
        boolean rollsOnExpiryDay = guessRoll(data, byTime);
        rep.expiryRollGuessed = true;

        // ---- engine with a simulated clock
        final long[] clock = {0};
        final Engine[] holder = new Engine[1];
        PaperBroker broker = new PaperBroker(id -> holder[0].quote(id), cfg.paperSlippage, cfg.depthMaxAgeMillis, () -> clock[0]);
        Engine.Listener l = new Engine.Listener() {
            @Override public void onLog(String level, String message) {}
            @Override public void onDecision(Engine.Decision d) {
                rep.decisions++;
                if (d.action != null && d.action.startsWith("no entry")) {
                    String key = shortReason(d.action);
                    rep.skipReasons.merge(key, 1, Integer::sum);
                }
            }
            @Override public void onStateChanged(BotState s, String why) {}
            @Override public void onPositionChanged(Position p) {}
            @Override public void onPositionSaved(String json) {}
            @Override public void onTradeClosed(TradeRecord t) { rep.trades.add(t); }
            @Override public void onStatsChanged(DayStats d) {}
            @Override public void onAlert(String message) {}
        };
        Engine e = new Engine(cfg, new Charges(), broker, l);
        holder[0] = e;
        e.seedBars(new ArrayList<>(data.warmup));
        e.setAvailableFunds(cfg.capital);
        e.setState(BotState.READY, "backtest");
        e.startTrading();

        // ---- group 5-min bars by day
        TreeMap<LocalDate, List<Candle>> days = new TreeMap<>();
        for (Candle c : data.spot5) days.computeIfAbsent(MarketClock.date(c.startMillis), k -> new ArrayList<>()).add(c);
        rep.from = days.firstKey();
        rep.to = days.lastKey();
        rep.days = days.size();

        long quoteSlots = 0, quoteMisses = 0;
        int dayNo = 0;
        double realizedBefore = 0;
        for (Map.Entry<LocalDate, List<Candle>> day : days.entrySet()) {
            LocalDate d = day.getKey();
            dayNo++;
            if (progress != null && dayNo % 5 == 1) progress.update("Testing " + d + " (" + dayNo + "/" + days.size() + ")");
            if (!MarketClock.tradingDay(d)) continue;
            rep.tradingDaysWithData++;

            // VIX: history up to yesterday, today's level = yesterday's close
            List<Double> hist = new ArrayList<>(data.vixCloses.headMap(d, false).values());
            if (hist.size() > 260) hist = hist.subList(hist.size() - 260, hist.size());
            if (!hist.isEmpty()) {
                e.setVixHistory(hist);
                e.onVix(hist.get(hist.size() - 1));
            }

            String near = expiryFor(d, 1, rollsOnExpiryDay), next = expiryFor(d, 2, rollsOnExpiryDay);
            int minDays = cfg.holding == Config.Holding.WEEKLY ? cfg.minDaysToExpiryWeekly : cfg.minDaysToExpiryIntraday;
            List<String> exps = new ArrayList<>();
            exps.add(near);
            exps.add(next);
            String trade = MarketClock.chooseExpiry(exps, d, minDays);
            e.setTradeExpiry(trade);

            List<Candle> bars = day.getValue();
            Collections.sort(bars, (a, b) -> Long.compare(a.startMillis, b.startMillis));
            for (Candle c : bars) {
                List<OptBar> opts = byTime.get(c.startMillis);
                long t0 = c.startMillis;
                boolean up = c.close >= c.open;
                double[] path = {c.open, up ? c.low : c.high, up ? c.high : c.low, c.close};
                long[] at = {t0 + 1_000, t0 + 75_000, t0 + 150_000, t0 + 5 * 60_000 - 15_000};
                for (int k = 0; k < 4; k++) {
                    clock[0] = at[k];
                    boolean useClose = k >= 2;
                    if (opts != null) {
                        for (OptBar o : opts) {
                            String exp = o.expiryCode == 1 ? near : next;
                            upsert(e, o, exp, useClose ? o.close : o.open, path[k], at[k]);
                        }
                    }
                    if (k == 0) {
                        quoteSlots++;
                        if (opts == null || opts.isEmpty()) quoteMisses++;
                    }
                    fillMissingHeld(e, path[k], at[k], opts, near, next);
                    e.onSpot(path[k], at[k]);
                    for (int s = 0; s < 12; s++) e.onTimer(at[k] + s * 1_000L);
                }
            }
            // let the last bar of the day close and any time exit finish
            long end = bars.get(bars.size() - 1).startMillis + 5 * 60_000 + 1_000;
            for (int s = 0; s < 30; s++) { clock[0] = end + s * 1_000L; e.onTimer(clock[0]); }

            double realized = 0;
            for (TradeRecord t : rep.trades) realized += t.net;
            rep.dailyNet.put(d, realized - realizedBefore);
            realizedBefore = realized;
        }
        rep.missingQuotePct = quoteSlots == 0 ? 0 : 100.0 * quoteMisses / quoteSlots;
        if (e.position() != null) rep.notes.add("A " + e.position().type.title + " was still open at the end and is not counted");
        summarize(rep);
        if (rep.missingQuotePct > 5) rep.notes.add(String.format(Locale.US, "Option data missing for %.0f%% of bars", rep.missingQuotePct));
        rep.notes.add("Prices are bar prices with a ±" + String.format(Locale.US, "%.1f", halfGapPct * 100)
                + "% bid/ask gap; real gaps on far strikes can be wider");
        rep.notes.add("India VIX uses the previous day's close");
        return rep;
    }

    private void upsert(Engine e, OptBar o, String expiry, double price, double spot, long now) {
        if (price <= 0) return;
        String id = id(expiry, o.right, o.strike);
        idRight.put(id, o.right);
        double half = Math.max(minHalfGap, price * halfGapPct);
        double bid = Math.max(0.05, price - half), ask = price + half;
        double iv = o.iv;
        double delta = delta(o.right, spot, o.strike, iv, yearsTo(expiry, now));
        e.upsertContract(id, o.right, o.strike, expiry, delta, iv, 0, price, bid, ask, now);
    }

    /** Held legs whose strike left Dhan's window still need a price: intrinsic + ₹0.5. */
    private void fillMissingHeld(Engine e, double spot, long now, List<OptBar> opts, String near, String next) {
        Position p = e.position();
        if (p == null) return;
        for (Leg l : p.legs) {
            OptionQuote q = e.quote(l.securityId);
            if (q != null && now - q.updatedMillis < 60_000) continue;
            double intrinsic = l.right == OptionQuote.Right.CE ? Math.max(0, spot - l.strike) : Math.max(0, l.strike - spot);
            double px = intrinsic + 0.5;
            double half = Math.max(minHalfGap, px * halfGapPct * 3);
            e.upsertContract(l.securityId, l.right, l.strike, l.expiry, intrinsic > 0 ? (l.right == OptionQuote.Right.CE ? 0.98 : -0.98) : 0.0,
                    0, 0, px, Math.max(0.05, px - half), px + half, now);
        }
    }

    static String id(String expiry, OptionQuote.Right r, double strike) {
        return "BT|" + expiry + "|" + r + "|" + (long) Math.round(strike);
    }

    // ------------------------------------------------------------------ expiries

    /**
     * NIFTY weekly expiry for {@code code} (1 near, 2 next) as seen on day {@code d}:
     * Tuesdays from 1 Sep 2025 (Thursdays before), moved to the previous trading day on a holiday.
     */
    public static String expiryFor(LocalDate d, int code, boolean rollsOnExpiryDay) {
        LocalDate e = firstExpiryOnOrAfter(rollsOnExpiryDay ? d.plusDays(1) : d);
        for (int i = 1; i < code; i++) e = firstExpiryOnOrAfter(nominal(e).plusDays(1));
        return e.toString();
    }

    private static LocalDate firstExpiryOnOrAfter(LocalDate d) {
        LocalDate x = d;
        for (int i = 0; i < 14; i++, x = x.plusDays(1)) {
            DayOfWeek want = x.isBefore(LocalDate.of(2025, 9, 1)) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY;
            if (x.getDayOfWeek() != want) continue;
            LocalDate actual = MarketClock.tradingDay(x) ? x : MarketClock.previousTradingDay(x);
            if (!actual.isBefore(d)) return actual;
        }
        return d;
    }

    /** The nominal weekday (Tue/Thu) of the week an actual expiry belongs to. */
    private static LocalDate nominal(LocalDate actual) {
        LocalDate x = actual;
        for (int i = 0; i < 7; i++, x = x.plusDays(1)) {
            DayOfWeek want = x.isBefore(LocalDate.of(2025, 9, 1)) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY;
            if (x.getDayOfWeek() == want) return x;
        }
        return actual;
    }

    /**
     * Does Dhan's "near" (code 1) series already show next week's contract on expiry day?
     * Check: in the last hour of an expiry day, today's ATM options are nearly worthless
     * (straddle well under ₹60); next week's are not.
     */
    private static boolean guessRoll(Data data, TreeMap<Long, List<OptBar>> byTime) {
        int votesToday = 0, votesNext = 0;
        for (Map.Entry<Long, List<OptBar>> en : byTime.entrySet()) {
            LocalDate d = MarketClock.date(en.getKey());
            if (!expiryFor(d, 1, false).equals(d.toString())) continue;
            if (MarketClock.minuteOfDay(en.getKey()) < 14 * 60 + 45) continue;
            double atmCe = Double.NaN, atmPe = Double.NaN, best = Double.MAX_VALUE;
            double spot = Double.NaN;
            for (Candle c : data.spot5) if (c.startMillis == en.getKey()) { spot = c.close; break; }
            if (Double.isNaN(spot)) continue;
            for (OptBar o : en.getValue()) {
                if (o.expiryCode != 1) continue;
                double dist = Math.abs(o.strike - spot);
                if (dist <= best + 0.01) {
                    if (dist < best - 0.01) { atmCe = Double.NaN; atmPe = Double.NaN; best = dist; }
                    if (o.right == OptionQuote.Right.CE) atmCe = o.close; else atmPe = o.close;
                }
            }
            if (Double.isNaN(atmCe) || Double.isNaN(atmPe)) continue;
            if (atmCe + atmPe < 60) votesToday++; else votesNext++;
        }
        return votesNext > votesToday;
    }

    // ------------------------------------------------------------------ maths

    private static double yearsTo(String expiry, long now) {
        try {
            long exp = LocalDate.parse(expiry).atTime(15, 30).atZone(MarketClock.IST).toInstant().toEpochMilli();
            return Math.max(1.0 / (365 * 24 * 4), (exp - now) / (365.0 * 24 * 3600 * 1000));
        } catch (RuntimeException ex) { return 7 / 365.0; }
    }

    /** Black-Scholes delta, no interest. {@code ivPct} in percent. */
    static double delta(OptionQuote.Right r, double s, double k, double ivPct, double years) {
        if (s <= 0 || k <= 0) return 0;
        double sig = ivPct > 0 ? ivPct / 100.0 : 0.13;
        double d1 = (Math.log(s / k) + 0.5 * sig * sig * years) / (sig * Math.sqrt(years));
        double nd = cnd(d1);
        return r == OptionQuote.Right.CE ? nd : nd - 1;
    }

    private static double cnd(double x) {
        // Abramowitz-Stegun 26.2.17
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double y = 1 - Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI)
                * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? y : 1 - y;
    }

    private static String shortReason(String action) {
        String s = action.replaceFirst("^no entry: ", "");
        s = s.replaceAll("₹[0-9,]+", "₹…").replaceAll("[0-9]+(\\.[0-9]+)?", "#");
        int dash = s.indexOf(" — ");
        if (dash > 0 && s.length() > dash + 3) s = s.substring(dash + 3);
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    private static void summarize(Report r) {
        double peak = 0, eq = 0, sumW = 0, sumL = 0;
        int streak = 0;
        r.bestTrade = r.trades.isEmpty() ? 0 : -Double.MAX_VALUE;
        r.worstTrade = r.trades.isEmpty() ? 0 : Double.MAX_VALUE;
        for (TradeRecord t : r.trades) {
            r.gross += t.gross;
            r.charges += t.charges;
            r.net += t.net;
            if (t.net >= 0) { r.wins++; sumW += t.net; streak = 0; }
            else { r.losses++; sumL += t.net; streak++; r.worstLosingStreak = Math.max(r.worstLosingStreak, streak); }
            r.bestTrade = Math.max(r.bestTrade, t.net);
            r.worstTrade = Math.min(r.worstTrade, t.net);
            eq += t.net;
            peak = Math.max(peak, eq);
            r.maxDrawdown = Math.max(r.maxDrawdown, peak - eq);
            StrategyStats s = r.byStrategy.computeIfAbsent(t.strategy, k -> new StrategyStats());
            s.trades++;
            s.net += t.net;
            if (t.net >= 0) s.wins++;
            r.byExit.merge(t.exitReason, 1, Integer::sum);
        }
        r.avgWin = r.wins == 0 ? 0 : sumW / r.wins;
        r.avgLoss = r.losses == 0 ? 0 : sumL / r.losses;
    }
}
