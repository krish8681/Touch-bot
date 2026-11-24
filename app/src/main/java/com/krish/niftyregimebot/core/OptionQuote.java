package com.krish.niftyregimebot.core;

import java.util.Locale;

/** Latest known numbers for one option contract (merged from option chain + live feed). */
public final class OptionQuote {
    public enum Right { CE, PE }

    public final String securityId;
    public final Right right;
    public final double strike;
    public final String expiry;
    public double ltp, bid, ask, delta, iv, oi;
    public long updatedMillis;

    public OptionQuote(String securityId, Right right, double strike, String expiry) {
        this.securityId = securityId;
        this.right = right;
        this.strike = strike;
        this.expiry = expiry == null ? "" : expiry;
    }

    public boolean hasBook() { return bid > 0 && ask > 0 && ask >= bid; }
    public double mid() { return hasBook() ? (bid + ask) / 2 : ltp; }
    public double spread() { return hasBook() ? ask - bid : Double.NaN; }

    public String label() { return String.format(Locale.US, "NIFTY %.0f %s", strike, right); }
}
