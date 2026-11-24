package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Says whether options are cheap or expensive right now.
 * Main method: India VIX percentile rank over its last ~1 year of daily closes.
 * Fallback (no VIX history): the at-the-money IV from the option chain against fixed thresholds.
 */
public final class IvGauge {
    public enum Level { LOW, NORMAL, HIGH, UNKNOWN }

    public static final class Reading {
        public Level level = Level.UNKNOWN;
        public double vix = Double.NaN, percentile = Double.NaN, atmIv = Double.NaN;
        public String source = "none";

        public String text() {
            if ("vix".equals(source))
                return String.format(Locale.US, "India VIX %.2f, %.0fth percentile of 1 year → %s", vix, percentile, level);
            if ("atm-iv".equals(source))
                return String.format(Locale.US, "ATM IV %.1f%% (no VIX history) → %s", atmIv, level);
            return "IV unknown";
        }
    }

    private final Config cfg;
    private final List<Double> vixHistory = new ArrayList<>();
    private double liveVix = Double.NaN;

    public IvGauge(Config cfg) { this.cfg = cfg; }

    public void setVixHistory(List<Double> dailyCloses) {
        vixHistory.clear();
        for (Double d : dailyCloses) if (d != null && d > 0) vixHistory.add(d);
    }

    public boolean hasVixHistory() { return vixHistory.size() >= 60; }

    public void onVix(double v) { if (v > 0) liveVix = v; }

    public Reading read(double atmIv) {
        Reading r = new Reading();
        double vix = !Double.isNaN(liveVix) ? liveVix : (vixHistory.isEmpty() ? Double.NaN : vixHistory.get(vixHistory.size() - 1));
        if (hasVixHistory() && !Double.isNaN(vix)) {
            r.source = "vix";
            r.vix = vix;
            r.percentile = Indicators.percentileRank(vixHistory, vix);
            r.level = r.percentile >= cfg.ivHighPct ? Level.HIGH : r.percentile <= cfg.ivLowPct ? Level.LOW : Level.NORMAL;
            return r;
        }
        if (atmIv > 0) {
            r.source = "atm-iv";
            r.atmIv = atmIv;
            r.level = atmIv >= cfg.atmIvHigh ? Level.HIGH : atmIv <= cfg.atmIvLow ? Level.LOW : Level.NORMAL;
        }
        return r;
    }
}
