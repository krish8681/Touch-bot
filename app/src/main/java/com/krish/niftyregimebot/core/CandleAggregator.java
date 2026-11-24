package com.krish.niftyregimebot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Builds N-minute candles from live ticks, aligned to the NSE session start (09:15 IST),
 * so 5-min bars start 09:15, 09:20… and 60-min bars start 09:15, 10:15…
 * A bar closes when a tick arrives in a later bucket or {@link #closeIfDue} passes its end.
 */
public final class CandleAggregator {
    public interface Sink { void onBarClosed(Candle c); }

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final int minutes;
    private final Sink sink;
    private Candle current;

    public CandleAggregator(int minutes, Sink sink) {
        this.minutes = Math.max(1, minutes);
        this.sink = sink;
    }

    public int minutes() { return minutes; }

    /** Start of the bar that contains {@code millis}, aligned to 09:15 IST. */
    public long bucketStart(long millis) {
        ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(IST);
        ZonedDateTime open = z.withHour(9).withMinute(15).withSecond(0).withNano(0);
        long sinceOpen = Math.floorDiv(millis - open.toInstant().toEpochMilli(), 60_000L);
        long idx = Math.floorDiv(sinceOpen, minutes);
        return open.toInstant().toEpochMilli() + idx * minutes * 60_000L;
    }

    public void onTick(double price, long now) {
        if (price <= 0) return;
        long b = bucketStart(now);
        if (current != null && b > current.startMillis) {
            Candle done = current;
            current = null;
            sink.onBarClosed(done);
        }
        if (current == null) current = new Candle(b, price, price, price, price);
        else if (b == current.startMillis) {
            current.high = Math.max(current.high, price);
            current.low = Math.min(current.low, price);
            current.close = price;
        }
    }

    public void closeIfDue(long now) {
        if (current != null && now >= current.startMillis + minutes * 60_000L + 1_500) {
            Candle done = current;
            current = null;
            sink.onBarClosed(done);
        }
    }

    public Candle current() { return current; }
}
