package com.krish.niftyregimebot.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared helpers: IST times, synthetic candles, and a synthetic option chain. */
final class TestUtil {
    private TestUtil() {}

    /** Config for engine-flow tests: decide on own bars at once (no wait for Dhan's copy). */
    static Config cfg() {
        Config c = new Config();
        c.dhanBarWaitMillis = 0;
        return c;
    }

    static long ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(MarketClock.IST).toInstant().toEpochMilli();
    }

    /** n bars of {@code minutes} each, ending just before {@code endMillis}, drifting by {@code step} per bar with a wiggle. */
    static List<Candle> bars(int n, int minutes, long endMillis, double start, double step, double wiggle) {
        List<Candle> out = new ArrayList<>();
        double px = start;
        long t = endMillis - (long) n * minutes * 60_000L;
        for (int i = 0; i < n; i++) {
            double w = (i % 2 == 0 ? wiggle : -wiggle);
            double o = px, c = px + step + w;
            double h = Math.max(o, c) + Math.abs(wiggle) * 0.5 + 1, l = Math.min(o, c) - Math.abs(wiggle) * 0.5 - 1;
            out.add(new Candle(t, o, h, l, c));
            px = c;
            t += minutes * 60_000L;
        }
        return out;
    }

    /** Fair-ish option price: intrinsic + time value falling off with distance. */
    static double price(OptionQuote.Right r, double strike, double spot) {
        double intrinsic = r == OptionQuote.Right.CE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        return Math.round((intrinsic + 140 * Math.exp(-Math.abs(strike - spot) / 260.0)) * 20) / 20.0;
    }

    static double callDelta(double strike, double spot) { return 1 / (1 + Math.exp((strike - spot) / 140.0)); }

    /** Load a chain around {@code spot} into the engine. */
    static void loadChain(Engine e, String expiry, double spot, long now) {
        for (double k = spot - 1500; k <= spot + 1500; k += 50) {
            double strike = Math.round(k / 50) * 50.0;
            for (OptionQuote.Right r : OptionQuote.Right.values()) {
                double px = price(r, strike, spot);
                double d = r == OptionQuote.Right.CE ? callDelta(strike, spot) : callDelta(strike, spot) - 1;
                e.upsertContract(id(r, strike), r, strike, expiry, d, 14, 1000, px, px - 0.5, px + 0.5, now);
            }
        }
    }

    /** Move every quote to a new spot (simulates the market moving). */
    static void reprice(Engine e, double spot, long now) {
        for (OptionQuote q : snapshot(e.quotes())) {
            double px = price(q.right, q.strike, spot);
            e.onOptionTick(q.securityId, px, px - 0.5, px + 0.5, now);
        }
    }

    static List<OptionQuote> snapshot(Collection<OptionQuote> qs) { return new ArrayList<>(qs); }

    static String id(OptionQuote.Right r, double strike) { return r.name() + (long) strike; }
}
