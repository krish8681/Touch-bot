package com.krish.niftydhanbot.core;

/**
 * Turns live ticks into 1-minute candles. A candle is "closed" when the first tick of
 * a later minute arrives, or when {@link #closeIfDue(long)} is called after the minute ends.
 * Uses the phone's receive time (not the exchange timestamp) for bucketing.
 */
public final class CandleBuilder {
    public interface Sink { void onCandleClosed(Candle c); }

    private static final long MIN = 60_000L;
    private final Sink sink;
    private Candle current;

    public CandleBuilder(Sink sink) { this.sink = sink; }

    public void onTick(double price, long nowMillis) {
        if (price <= 0) return;
        long bucket = nowMillis - Math.floorMod(nowMillis, MIN);
        if (current != null && bucket > current.startMillis) {
            Candle done = current;
            current = null;
            sink.onCandleClosed(done);
        }
        if (current == null) {
            current = new Candle(bucket, price, price, price, price);
        } else if (bucket == current.startMillis) {
            current.high = Math.max(current.high, price);
            current.low = Math.min(current.low, price);
            current.close = price;
        }
        // ticks older than the current bucket are ignored
    }

    /** Close the running candle if its minute has fully passed (call ~every second). */
    public void closeIfDue(long nowMillis) {
        if (current != null && nowMillis >= current.startMillis + MIN + 1500) {
            Candle done = current;
            current = null;
            sink.onCandleClosed(done);
        }
    }

    public Candle current() { return current; }
}
