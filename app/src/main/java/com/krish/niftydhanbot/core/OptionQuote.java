package com.krish.niftydhanbot.core;

/** Latest known numbers for one option contract (merged from option chain + live feed). */
public final class OptionQuote {
    public final String securityId;
    public final Signal.Side side;   // CE or PE
    public final double strike;
    public double ltp, bid, ask, delta, iv, oi;
    public long updatedMillis;       // when bid/ask were last refreshed

    public OptionQuote(String securityId, Signal.Side side, double strike) {
        this.securityId = securityId;
        this.side = side;
        this.strike = strike;
    }

    public double mid() { return (bid > 0 && ask > 0) ? (bid + ask) / 2 : ltp; }
    public double spread() { return (bid > 0 && ask > 0) ? ask - bid : Double.NaN; }

    public String label() { return String.format(java.util.Locale.US, "NIFTY %.0f %s", strike, side); }
}
