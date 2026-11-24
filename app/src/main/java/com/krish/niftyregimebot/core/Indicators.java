package com.krish.niftyregimebot.core;

import java.util.List;

/** Plain indicator maths over closed candles (oldest first). */
public final class Indicators {
    private Indicators() {}

    /** EMA of closes at the last bar. Seeds with the SMA of the first {@code period} bars. NaN if too few bars. */
    public static double ema(List<Candle> c, int period) {
        int n = c.size();
        if (n < period) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < period; k++) sum += c.get(k).close;
        double e = sum / period;
        double a = 2.0 / (period + 1);
        for (int k = period; k < n; k++) e = a * c.get(k).close + (1 - a) * e;
        return e;
    }

    static double trueRange(List<Candle> c, int k) {
        Candle x = c.get(k);
        double pc = c.get(k - 1).close;
        return Math.max(x.high - x.low, Math.max(Math.abs(x.high - pc), Math.abs(x.low - pc)));
    }

    /** Wilder ATR at the last bar. */
    public static double atr(List<Candle> c, int period) {
        int n = c.size();
        if (n <= period) return Double.NaN;
        double sum = 0;
        for (int k = 1; k <= period; k++) sum += trueRange(c, k);
        double a = sum / period;
        for (int k = period + 1; k < n; k++) a = (a * (period - 1) + trueRange(c, k)) / period;
        return a;
    }

    /**
     * Wilder ADX at the last bar, with +DI and −DI. Returns {adx, plusDi, minusDi}, or NaNs
     * when there are fewer than 2×period+1 bars.
     */
    public static double[] adx(List<Candle> c, int period) {
        int n = c.size();
        if (n < 2 * period + 1) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double tr = 0, pdm = 0, mdm = 0;
        for (int k = 1; k <= period; k++) {
            tr += trueRange(c, k);
            double[] dm = dm(c, k);
            pdm += dm[0];
            mdm += dm[1];
        }
        double dxSum = 0;
        int dxCount = 0;
        double adx = Double.NaN, pdi = 0, mdi = 0;
        for (int k = period; k < n; k++) {
            if (k > period) {
                tr = tr - tr / period + trueRange(c, k);
                double[] dm = dm(c, k);
                pdm = pdm - pdm / period + dm[0];
                mdm = mdm - mdm / period + dm[1];
            }
            pdi = tr == 0 ? 0 : 100 * pdm / tr;
            mdi = tr == 0 ? 0 : 100 * mdm / tr;
            double dx = (pdi + mdi) == 0 ? 0 : 100 * Math.abs(pdi - mdi) / (pdi + mdi);
            if (dxCount < period) {
                dxSum += dx;
                dxCount++;
                if (dxCount == period) adx = dxSum / period;
            } else {
                adx = (adx * (period - 1) + dx) / period;
            }
        }
        return new double[]{adx, pdi, mdi};
    }

    private static double[] dm(List<Candle> c, int k) {
        double up = c.get(k).high - c.get(k - 1).high;
        double down = c.get(k - 1).low - c.get(k).low;
        double p = (up > down && up > 0) ? up : 0;
        double m = (down > up && down > 0) ? down : 0;
        return new double[]{p, m};
    }

    /** Percentile rank (0–100) of {@code value} within {@code history}: share of values ≤ value. */
    public static double percentileRank(List<Double> history, double value) {
        if (history.isEmpty()) return Double.NaN;
        int le = 0;
        for (double h : history) if (h <= value) le++;
        return 100.0 * le / history.size();
    }
}
