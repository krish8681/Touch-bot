package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the bars and the trend reading built from them.
 *
 * Live ticks are grouped into bars ({@link CandleAggregator}). When a bar closes, the book
 * waits up to {@link Config#dhanBarWaitMillis} for Dhan's own copy of it, then hands the bar
 * to the {@link Sink} for a decision. Dhan's bars also repair older bars that were wrong or
 * missing (feed gaps). A bar the bot only partly saw (started late) is marked incomplete.
 */
final class BarBook implements CandleAggregator.Sink {

    /** Receives each closed bar once, ready for a decision. */
    interface Sink {
        void onBarReady(Candle bar, boolean incomplete);
        void log(String level, String message);
    }

    private static final int MAX_BARS = 600;

    private final Config cfg;
    private final Sink sink;
    private final CandleAggregator agg;
    private final RegimeDetector regime, regime15;
    private final List<Candle> bars = new ArrayList<>();
    private RegimeDetector.Reading reading = new RegimeDetector.Reading();
    private Candle pending;                 // closed from our own ticks, waiting for Dhan's copy
    private long pendingSince, now;
    private int fixed;

    BarBook(Config cfg, Sink sink) {
        this.cfg = cfg;
        this.sink = sink;
        this.agg = new CandleAggregator(cfg.barMinutes(), this);
        this.regime = new RegimeDetector(cfg);
        this.regime15 = new RegimeDetector(cfg);
    }

    // ------------------------------------------------------------------ views

    List<Candle> bars() { return bars; }
    RegimeDetector.Reading reading() { return reading; }
    int minutes() { return agg.minutes(); }
    int minBars() { return regime.minBars(); }
    long pendingStart() { return pending == null ? 0 : pending.startMillis; }
    int fixedCount() { return fixed; }

    /** Trend on 15-minute bars made from these bars. */
    RegimeDetector.Regime trend15() { return regime15.replay(resample(bars, 15)).confirmed; }

    /** High − low of today's bars so far, or 0. */
    double dayRange(java.time.LocalDate today) {
        double hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE;
        for (int i = bars.size() - 1; i >= 0; i--) {
            Candle c = bars.get(i);
            if (!MarketClock.date(c.startMillis).equals(today)) break;
            hi = Math.max(hi, c.high);
            lo = Math.min(lo, c.low);
        }
        return hi > lo ? hi - lo : 0;
    }

    // ------------------------------------------------------------------ input

    void seed(List<Candle> history) {
        bars.clear();
        bars.addAll(history);
        reading = regime.replay(bars);
    }

    void onTick(double price, long t) {
        now = t;
        agg.onTick(price, t);
    }

    void onTimer(long t) {
        now = t;
        agg.closeIfDue(t);
        if (pending != null && t - pendingSince >= cfg.dhanBarWaitMillis) {
            Candle c = pending;
            pending = null;
            decide(c, false);
        }
    }

    /** A bar built from our own ticks just closed. Wait briefly for Dhan's official copy. */
    @Override public void onBarClosed(Candle c) {
        if (pending != null) { Candle old = pending; pending = null; decide(old, false); }
        if (cfg.dhanBarWaitMillis <= 0) { decide(c, false); return; }
        pending = c;
        pendingSince = now;
    }

    /**
     * Recent bars from Dhan's chart API (same timeframe, oldest first, still-forming bar excluded).
     * Corrects bars built from ticks, fills bars that were missed, and completes the pending decision.
     */
    void onDhanBars(List<Candle> official) {
        if (official == null || official.isEmpty()) return;
        long currentBucket = agg.current() == null ? Long.MAX_VALUE : agg.current().startMillis;
        int changed = 0;
        Candle pendingOfficial = null;
        boolean misaligned = false;
        for (Candle o : official) {
            if (agg.bucketStart(o.startMillis) != o.startMillis) { misaligned = true; continue; }
            if (o.startMillis >= currentBucket) continue;
            if (pending != null && o.startMillis == pending.startMillis) { pendingOfficial = o; continue; }
            if (!bars.isEmpty() && o.startMillis < bars.get(0).startMillis) continue;
            int i = indexOf(o.startMillis);
            if (i >= 0) {
                if (differs(bars.get(i), o)) { bars.set(i, o); changed++; }
            } else {
                int at = bars.size();
                while (at > 0 && bars.get(at - 1).startMillis > o.startMillis) at--;
                bars.add(at, o);
                changed++;
            }
        }
        if (misaligned && fixed == 0 && changed == 0) sink.log("WARN", "Dhan's bars are not aligned to 09:15 — using the bot's own bars");
        if (changed > 0) {
            fixed += changed;
            sink.log("INFO", "Corrected " + changed + " bar(s) using Dhan's chart data");
            reading = regime.replay(bars);
        }
        if (pendingOfficial != null) {
            pending = null;
            decide(pendingOfficial, true);
        }
    }

    // ------------------------------------------------------------------ internals

    private void decide(Candle c, boolean official) {
        boolean incomplete = !official && c.firstTickMillis > 0 && c.firstTickMillis - c.startMillis > cfg.partialBarMillis;
        int existing = indexOf(c.startMillis);
        if (existing >= 0) bars.set(existing, c); else bars.add(c);
        if (bars.size() > MAX_BARS) bars.remove(0);
        reading = existing >= 0 ? regime.replay(bars) : regime.evaluate(bars);
        sink.onBarReady(c, incomplete);
    }

    private int indexOf(long start) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            long s = bars.get(i).startMillis;
            if (s == start) return i;
            if (s < start) return -1;
        }
        return -1;
    }

    private static boolean differs(Candle a, Candle b) {
        return Math.abs(a.open - b.open) > 0.01 || Math.abs(a.high - b.high) > 0.01
                || Math.abs(a.low - b.low) > 0.01 || Math.abs(a.close - b.close) > 0.01;
    }

    /** Group bars into N-minute bars aligned to 09:15. */
    static List<Candle> resample(List<Candle> in, int minutes) {
        List<Candle> out = new ArrayList<>();
        Candle cur = null;
        long curKey = Long.MIN_VALUE;
        for (Candle c : in) {
            int mod = MarketClock.minuteOfDay(c.startMillis);
            long dayStart = c.startMillis - (long) mod * 60_000L - (c.startMillis % 60_000L);
            long key = dayStart + (9 * 60 + 15 + Math.floorDiv(mod - (9 * 60 + 15), minutes) * minutes) * 60_000L;
            if (cur == null || key != curKey) {
                cur = new Candle(key, c.open, c.high, c.low, c.close);
                out.add(cur);
                curKey = key;
            } else {
                cur.high = Math.max(cur.high, c.high);
                cur.low = Math.min(cur.low, c.low);
                cur.close = c.close;
            }
        }
        return out;
    }
}
