package com.krish.niftydhanbot.core;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Picks the contract to buy: right side, live two-sided quote, tight spread, enough OI,
 * premium in range, and delta closest to the target (slightly in-the-money by default).
 */
public final class StrikeSelector {
    private final BotConfig cfg;

    public StrikeSelector(BotConfig cfg) { this.cfg = cfg; }

    /** Returns the best quote, or null with the reason added to {@code why}. */
    public OptionQuote pick(Signal.Side side, double spot, Collection<OptionQuote> chain, long now, List<String> why) {
        OptionQuote best = null;
        double bestScore = Double.MAX_VALUE;
        int seen = 0;
        String lastReject = "no contracts loaded";
        for (OptionQuote q : chain) {
            if (q.side != side) continue;
            if (Math.abs(q.strike - spot) > 300) continue;  // near-the-money only
            seen++;
            String bad = reject(q, now);
            if (bad != null) { lastReject = q.label() + ": " + bad; continue; }
            double d = Math.abs(q.delta);
            double score = d > 0 ? Math.abs(d - cfg.targetDelta) : 1 + Math.abs(q.strike - spot) / 1000.0;
            if (score < bestScore) { bestScore = score; best = q; }
        }
        if (best == null) why.add(seen == 0 ? "no " + side + " contracts near spot" : "no usable strike (" + lastReject + ")");
        else why.add(String.format(Locale.US, "picked %s ask %.2f delta %.2f", best.label(), best.ask, best.delta));
        return best;
    }

    String reject(OptionQuote q, long now) {
        if (q.bid <= 0 || q.ask <= 0) return "no two-sided quote";
        if (now - q.updatedMillis > cfg.maxQuoteAgeMs) return "quote is stale";
        if (q.ask < cfg.minPremium || q.ask > cfg.maxPremium) return String.format(Locale.US, "premium %.1f out of range", q.ask);
        double spread = q.spread();
        if (spread > cfg.maxSpreadAbs || spread / q.mid() * 100 > cfg.maxSpreadPct)
            return String.format(Locale.US, "spread %.2f too wide", spread);
        if (q.oi > 0 && q.oi < cfg.minOi) return "open interest too low";
        return null;
    }
}
