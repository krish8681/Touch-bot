package com.krish.niftydhanbot.core;

import java.util.List;

/** Plain indicator maths over a list of closed candles (oldest first). */
public final class Indicators {
    private Indicators() {}

    /** EMA of closes, value at index {@code upTo} (inclusive). Seeds with SMA of the first {@code period} bars. */
    public static double ema(List<Candle> c, int period, int upTo) {
        if (upTo + 1 < period) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < period; k++) sum += c.get(k).close;
        double e = sum / period;
        double a = 2.0 / (period + 1);
        for (int k = period; k <= upTo; k++) e = a * c.get(k).close + (1 - a) * e;
        return e;
    }

    /** Wilder ATR at index {@code upTo}. */
    public static double atr(List<Candle> c, int period, int upTo) {
        if (upTo < period) return Double.NaN;
        double sum = 0;
        for (int k = 1; k <= period; k++) sum += trueRange(c, k);
        double a = sum / period;
        for (int k = period + 1; k <= upTo; k++) a = (a * (period - 1) + trueRange(c, k)) / period;
        return a;
    }

    static double trueRange(List<Candle> c, int k) {
        Candle x = c.get(k);
        double pc = c.get(k - 1).close;
        return Math.max(x.high - x.low, Math.max(Math.abs(x.high - pc), Math.abs(x.low - pc)));
    }

    public static double highest(List<Candle> c, int from, int toInclusive) {
        double h = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, from); k <= toInclusive; k++) h = Math.max(h, c.get(k).high);
        return h;
    }

    public static double lowest(List<Candle> c, int from, int toInclusive) {
        double l = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, from); k <= toInclusive; k++) l = Math.min(l, c.get(k).low);
        return l;
    }
}
