package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the current market regime from closed candles. It does not predict: it only labels
 * what the trend already is (Raja's rule: up → call side, down → put side, sideways → range).
 *
 * UP       = EMA fast > EMA slow, close above EMA slow, ADX ≥ adxTrend and +DI > −DI
 * DOWN     = the mirror image
 * SIDEWAYS = anything else
 * A change only counts after it repeats for {@code confirmBars} bars in a row.
 */
public final class RegimeDetector {
    public enum Regime { UP, DOWN, SIDEWAYS, UNKNOWN }

    public static final class Reading {
        public Regime raw = Regime.UNKNOWN;        // this bar alone
        public Regime confirmed = Regime.UNKNOWN;  // after the confirm rule
        public double emaFast = Double.NaN, emaSlow = Double.NaN, adx = Double.NaN, plusDi, minusDi, close;
        public int bars;
        public final List<String> reasons = new ArrayList<>();

        public String text() { return String.join("; ", reasons); }
    }

    private final Config cfg;
    private Regime confirmed = Regime.UNKNOWN;
    private Regime candidate = Regime.UNKNOWN;
    private int candidateCount;

    public RegimeDetector(Config cfg) { this.cfg = cfg; }

    public Regime confirmed() { return confirmed; }

    public int minBars() { return Math.max(cfg.emaSlow, 2 * cfg.adxPeriod + 1); }

    /** Call once per closed bar with all bars so far (oldest first). */
    public Reading evaluate(List<Candle> bars) {
        Reading r = new Reading();
        r.bars = bars.size();
        if (bars.size() < minBars()) {
            r.reasons.add("warming up: " + bars.size() + "/" + minBars() + " bars");
            r.confirmed = confirmed;
            return r;
        }
        r.close = bars.get(bars.size() - 1).close;
        r.emaFast = Indicators.ema(bars, cfg.emaFast);
        r.emaSlow = Indicators.ema(bars, cfg.emaSlow);
        double[] a = Indicators.adx(bars, cfg.adxPeriod);
        r.adx = a[0];
        r.plusDi = a[1];
        r.minusDi = a[2];

        boolean trending = r.adx >= cfg.adxTrend;
        if (trending && r.emaFast > r.emaSlow && r.close > r.emaSlow && r.plusDi > r.minusDi) r.raw = Regime.UP;
        else if (trending && r.emaFast < r.emaSlow && r.close < r.emaSlow && r.minusDi > r.plusDi) r.raw = Regime.DOWN;
        else r.raw = Regime.SIDEWAYS;

        r.reasons.add(String.format(Locale.US, "EMA%d %.1f %s EMA%d %.1f", cfg.emaFast, r.emaFast,
                r.emaFast >= r.emaSlow ? ">" : "<", cfg.emaSlow, r.emaSlow));
        r.reasons.add(String.format(Locale.US, "ADX %.1f (%s)", r.adx, trending ? "trending" : "no trend"));
        r.reasons.add(String.format(Locale.US, "+DI %.1f / −DI %.1f", r.plusDi, r.minusDi));

        if (r.raw == candidate) candidateCount++;
        else { candidate = r.raw; candidateCount = 1; }
        if (confirmed == Regime.UNKNOWN || candidateCount >= cfg.confirmBars) confirmed = candidate;
        if (r.raw != confirmed) r.reasons.add("new reading " + r.raw + " not yet confirmed (" + candidateCount + "/" + cfg.confirmBars + ")");
        r.confirmed = confirmed;
        return r;
    }

    /** Re-run over history (e.g. after loading earlier candles) so the confirmed state is right. */
    public Reading replay(List<Candle> bars) {
        confirmed = Regime.UNKNOWN;
        candidate = Regime.UNKNOWN;
        candidateCount = 0;
        Reading last = new Reading();
        List<Candle> prefix = new ArrayList<>();
        for (Candle c : bars) {
            prefix.add(c);
            if (prefix.size() >= minBars()) last = evaluate(prefix);
        }
        if (prefix.size() < minBars()) last = evaluate(prefix);
        return last;
    }
}
