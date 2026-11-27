package com.krish.niftyregimebot.core;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;

/**
 * Every "may we open a trade now?" rule, in one place. Each check returns null when the trade
 * may go ahead, or a short plain-English reason when it may not (shown in Decisions).
 *
 *  1. {@link #limits}  — market day, entry window, fresh price, daily limits, cool-down
 *  2. {@link #filters} — events, 15-min trend, chop, open interest (each can be switched off)
 *  3. {@link #charges} — the profit target must be worth the charges
 */
final class EntryGate {
    private final Config cfg;

    // put-call ratio seen first after 09:30 today
    private double dayOpenPcr;
    private String pcrDay = "";

    EntryGate(Config cfg) { this.cfg = cfg; }

    /** Time, day limits and cool-down. {@code lastExitKind} = WIN, LOSS, FLIP or "". */
    String limits(long now, long lastSpotMillis, DayStats day, String lastExitKind) {
        int m = MarketClock.minuteOfDay(now);
        if (!MarketClock.tradingDay(MarketClock.date(now))) return "market closed today (weekend or NSE holiday)";
        if (m < cfg.entryStart || m > cfg.entryEnd) return "outside entry window";
        if (now - lastSpotMillis > 10_000) return "NIFTY price is stale";
        if (day.entries >= cfg.maxTradesPerDay) return "max trades for today reached";
        if (day.consecutiveLosses >= cfg.maxConsecutiveLosses) return day.consecutiveLosses + " losses in a row today";
        if (day.realizedPnl <= -cfg.dailyLossLimit) return "daily loss limit reached";
        double cool = "FLIP".equals(lastExitKind) && cfg.reenterAfterFlip ? 0
                : "WIN".equals(lastExitKind) ? cfg.cooldownAfterWinMinutes : cfg.cooldownMinutes;
        if (day.lastExitMillis > 0 && now - day.lastExitMillis < cool * 60_000L) return "cooling down after last exit";
        return null;
    }

    /** Filters on the picked strategy. */
    String filters(StrategyGrid.Type pick, long now, double spot, String tradeExpiry, BarBook book,
                   Collection<OptionQuote> quotes) {
        boolean trend = pick.bias != StrategyGrid.Bias.RANGE;
        LocalDate today = MarketClock.date(now);
        if (cfg.eventFilter) {
            if (MarketClock.eventDay(today)) return "event day (RBI policy / Budget)";
            if (cfg.holding == Config.Holding.WEEKLY) {
                try {
                    LocalDate ev = MarketClock.eventBetween(today, LocalDate.parse(tradeExpiry));
                    if (ev != null) return "event on " + ev + " before expiry";
                } catch (RuntimeException ignored) { }
            }
        }
        if (!trend) return null;
        if (cfg.confirm15m && cfg.holding == Config.Holding.INTRADAY && book.minutes() < 15) {
            RegimeDetector.Regime r15 = book.trend15();
            RegimeDetector.Regime want = pick.bias == StrategyGrid.Bias.UP ? RegimeDetector.Regime.UP : RegimeDetector.Regime.DOWN;
            if (r15 != RegimeDetector.Regime.UNKNOWN && r15 != want) return "15-min trend is " + r15;
        }
        if (cfg.chopFilter) {
            java.util.List<Candle> bars = book.bars();
            int n = bars.size();
            if (n > cfg.adxFallBars + book.minBars()) {
                double adxNow = Indicators.adx(bars, cfg.adxPeriod)[0];
                double adxBefore = Indicators.adx(bars.subList(0, n - cfg.adxFallBars), cfg.adxPeriod)[0];
                if (adxNow < adxBefore - 0.5) return String.format(Locale.US, "trend weakening (ADX %.1f, was %.1f)", adxNow, adxBefore);
            }
            double range = book.dayRange(today);
            if (range > 0 && spot > 0 && range / spot < cfg.minDayRangePct)
                return String.format(Locale.US, "day's range only %.0f points (quiet market)", range);
        }
        if (cfg.pcrFilter) {
            double pcr = pcr(quotes, tradeExpiry, spot);
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

    /** Skip a trade whose profit target is too small next to its charges. */
    String charges(StrategyGrid.Type pick, Sizer.Result size) {
        double target = Double.isInfinite(size.maxProfitRs) ? cfg.riskPerTrade : targetPct(pick, cfg) * size.maxProfitRs;
        if (size.chargesRs > 0 && target < cfg.minTargetToCharges * size.chargesRs)
            return String.format(Locale.US, "target profit ₹%,.0f is less than %.0f× charges (₹%,.0f)",
                    target, cfg.minTargetToCharges, size.chargesRs);
        return null;
    }

    static double targetPct(StrategyGrid.Type t, Config cfg) {
        return t == StrategyGrid.Type.LONG_CALL_BUTTERFLY ? cfg.targetPctButterfly
                : t.credit ? cfg.targetPctCredit : cfg.targetPctDebit;
    }

    // ------------------------------------------------------------------ open interest

    /** Put OI ÷ call OI for the trading expiry, strikes near NIFTY. 0 if no OI data. */
    double pcr(Collection<OptionQuote> quotes, String expiry, double spot) {
        double ce = 0, pe = 0;
        for (OptionQuote q : quotes) {
            if (!q.expiry.equals(expiry) || q.oi <= 0 || Math.abs(q.strike - spot) > cfg.pcrBandPts) continue;
            if (q.right == OptionQuote.Right.CE) ce += q.oi; else pe += q.oi;
        }
        return ce > 0 ? pe / ce : 0;
    }

    /** Remember the first PCR after 09:30 each day. */
    void trackPcr(long now, Collection<OptionQuote> quotes, String expiry, double spot) {
        String today = MarketClock.date(now).toString();
        if (today.equals(pcrDay) || MarketClock.minuteOfDay(now) < 9 * 60 + 30) return;
        double p = pcr(quotes, expiry, spot);
        if (p > 0) { dayOpenPcr = p; pcrDay = today; }
    }
}
