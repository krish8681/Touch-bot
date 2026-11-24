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

    /** Market depth, best level first (from the live feed). Empty until the first full packet. */
    public double[] bidPx = new double[0], askPx = new double[0];
    public int[] bidQty = new int[0], askQty = new int[0];
    public long depthMillis;

    public void setDepth(double[] bp, int[] bq, double[] ap, int[] aq, long now) {
        bidPx = bp.clone(); bidQty = bq.clone(); askPx = ap.clone(); askQty = aq.clone();
        depthMillis = now;
    }

    public boolean hasDepth(long now, long maxAge) {
        return depthMillis > 0 && now - depthMillis <= maxAge && (bidPx.length > 0 || askPx.length > 0);
    }

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
