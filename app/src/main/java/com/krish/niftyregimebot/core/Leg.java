package com.krish.niftyregimebot.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One option leg of a strategy. {@code ratio} is units per 1 unit of the strategy
 * (a butterfly sells 2 of the middle strike). {@code price} is the planned price before
 * entry and the actual average fill after entry.
 */
public final class Leg {
    public final String securityId;
    public final OptionQuote.Right right;
    public final double strike;
    public final String expiry;
    public final boolean buy;
    public final int ratio;
    public double price;

    public Leg(String securityId, OptionQuote.Right right, double strike, String expiry, boolean buy, int ratio, double price) {
        this.securityId = securityId;
        this.right = right;
        this.strike = strike;
        this.expiry = expiry == null ? "" : expiry;
        this.buy = buy;
        this.ratio = Math.max(1, ratio);
        this.price = price;
    }

    public static Leg of(OptionQuote q, boolean buy, int ratio, double price) {
        return new Leg(q.securityId, q.right, q.strike, q.expiry, buy, ratio, price);
    }

    /** Value at expiry of one long unit of this contract. */
    public double intrinsic(double spot) {
        return right == OptionQuote.Right.CE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
    }

    /** P&L at expiry per strategy unit, given this leg's price. */
    public double expiryPnl(double spot) {
        double v = intrinsic(spot) - price;
        return (buy ? v : -v) * ratio;
    }

    public String label() {
        return String.format(Locale.US, "%s%s %.0f %s", buy ? "+" : "−", ratio > 1 ? ratio + "×" : "", strike, right);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("securityId", securityId);
        m.put("right", right.name());
        m.put("strike", strike);
        m.put("expiry", expiry);
        m.put("buy", buy);
        m.put("ratio", (double) ratio);
        m.put("price", price);
        return m;
    }

    public static Leg fromMap(Map<String, Object> m) {
        Object b = m.get("buy");
        return new Leg(Json.str(m, "securityId", ""), OptionQuote.Right.valueOf(Json.str(m, "right", "CE")),
                Json.num(m, "strike", 0), Json.str(m, "expiry", ""), Boolean.TRUE.equals(b),
                (int) Json.num(m, "ratio", 1), Json.num(m, "price", 0));
    }
}
