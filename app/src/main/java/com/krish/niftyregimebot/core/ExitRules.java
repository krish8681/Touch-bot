package com.krish.niftyregimebot.core;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * When to close the open trade. Checked about once a second; the first rule that fires wins:
 * target → stop (fair value) → hard stop (close-now value) → time / expiry → event → opening gap.
 * The trend-flip exit is decided on each closed bar by the engine.
 */
final class ExitRules {
    private final Config cfg;
    private final Engine.Listener out;

    // opening-gap watch (WEEKLY)
    private double prevDayClose;
    private boolean gapPending;

    ExitRules(Config cfg, Engine.Listener out) {
        this.cfg = cfg;
        this.out = out;
    }

    /** Called before each new NIFTY price is stored: notices the first price of a new day. */
    void onSpot(double oldSpot, long oldMillis, long now) {
        if (oldSpot > 0 && oldMillis > 0 && !MarketClock.date(now).equals(MarketClock.date(oldMillis))) {
            prevDayClose = oldSpot;
            gapPending = true;
        }
    }

    /** Refresh both values of the trade from the latest quotes. False if a leg has no price. */
    static boolean mark(Position p, Map<String, OptionQuote> quotes, double wideGapPct, long now) {
        double pnl = p.markToMarket(quotes);
        if (Double.isNaN(pnl)) return false;
        p.openPnl = pnl;
        double fair = p.fairMark(quotes, wideGapPct);
        p.fairPnl = Double.isNaN(fair) ? pnl : fair;
        p.lastMarkMillis = now;
        p.stale = false;
        return true;
    }

    Engine.ExitReason check(Position p, long now, double spot) {
        if (p.openPnl >= p.targetRs && p.targetRs > 0) return Engine.ExitReason.TARGET;
        if (p.fairPnl <= -p.stopRs && p.stopRs > 0) return Engine.ExitReason.STOP;
        if (p.openPnl <= -cfg.hardStopPctOfMaxLoss * p.maxLossRs() && p.stopRs > 0) return Engine.ExitReason.STOP;
        int m = MarketClock.minuteOfDay(now);
        LocalDate today = MarketClock.date(now);
        if (p.holding == Config.Holding.INTRADAY) {
            if (m >= cfg.squareOff && MarketClock.tradingDay(today)) return Engine.ExitReason.TIME;
            if (today.isAfter(MarketClock.date(p.entryMillis))) return Engine.ExitReason.TIME;
            return null;
        }
        if (MarketClock.weeklyExitDue(p.expiry, now, cfg.squareOff)) return Engine.ExitReason.EXPIRY_NEAR;
        if (cfg.eventFilter && MarketClock.tradingDay(today)) {
            LocalDate next = MarketClock.nextTradingDay(today.plusDays(1));
            boolean eventNext = MarketClock.eventDay(next) || MarketClock.eventBetween(today, next) != null;
            if ((eventNext && m >= cfg.squareOff) || MarketClock.eventDay(today)) return Engine.ExitReason.EVENT;
        }
        if (gapPending && m >= 9 * 60 + 20) gapPending = false;   // only the opening minutes count
        if (cfg.gapCheck && gapPending && prevDayClose > 0 && m >= 9 * 60 + 15
                && MarketClock.date(p.entryMillis).isBefore(today)) {
            gapPending = false;
            double gap = (spot - prevDayClose) / prevDayClose;
            boolean against = p.type.bias == StrategyGrid.Bias.UP ? gap <= -cfg.gapExitPct
                    : p.type.bias == StrategyGrid.Bias.DOWN ? gap >= cfg.gapExitPct
                    : Math.abs(gap) >= cfg.gapExitPct;
            if (against) {
                out.onLog("WARN", String.format(Locale.US, "NIFTY opened %.2f%% from yesterday's close — against the trade", gap * 100));
                return Engine.ExitReason.GAP;
            }
        }
        return null;
    }
}
