package com.krish.niftydhanbot.core;

/**
 * Decides when to sell. Runs on every quote of the held option and every second,
 * independent of signals and news. Rules, in order:
 * square-off time → stop hit → target/trailing → max hold time.
 * Let winners run: after +1R the stop moves to break-even (plus costs), then trails
 * the best price, giving back at most trailGivebackR.
 */
public final class ExitManager {
    public enum Reason { NONE, STOP, TRAIL_STOP, TIME, SQUARE_OFF, EMERGENCY }

    private final BotConfig cfg;

    public ExitManager(BotConfig cfg) { this.cfg = cfg; }

    /** Update the trailing stop from the latest bid. Call before {@link #check}. */
    public void onQuote(Position p, double bid, long now, double costPerUnit) {
        if (bid <= 0) return;
        p.lastBid = bid;
        p.lastQuoteMillis = now;
        if (bid > p.best) p.best = bid;
        double r = p.riskPerUnit;
        if (r <= 0) return;
        double gainR = (p.best - p.entryPrice) / r;
        if (gainR >= cfg.breakevenAtR) {
            double be = p.entryPrice + costPerUnit;
            if (be > p.stop) { p.stop = be; p.stopReason = "break-even"; }
        }
        // Past the target, don't sell — keep trailing so the winner can run.
        if (gainR >= cfg.breakevenAtR + cfg.trailGivebackR) {
            double trail = p.best - cfg.trailGivebackR * r;
            if (trail > p.stop) { p.stop = trail; p.stopReason = "trailing"; }
        }
    }

    public Reason check(Position p, long now, int minuteOfDayIst) {
        if (minuteOfDayIst >= cfg.squareOffMin) return Reason.SQUARE_OFF;
        if (p.lastBid <= p.stop) {
            return "initial stop".equals(p.stopReason) ? Reason.STOP : Reason.TRAIL_STOP;
        }
        long held = now - p.entryMillis;
        if (held >= cfg.maxHoldMinutes * 60_000L) {
            // A trade that is trailing in good profit may run up to 2× the hold time.
            boolean trailingWinner = "trailing".equals(p.stopReason);
            if (!trailingWinner || held >= cfg.maxHoldMinutes * 2 * 60_000L) return Reason.TIME;
        }
        return Reason.NONE;
    }
}
