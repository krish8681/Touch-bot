package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An open multi-leg strategy. {@code qty} is per strategy unit (lots × lot size);
 * each leg trades qty × ratio. Saved to disk so weekly positions survive app restarts.
 */
public final class Position {
    public StrategyGrid.Type type;
    public final List<Leg> legs = new ArrayList<>();
    public int qty;
    public String expiry = "";
    public Config.Holding holding = Config.Holding.INTRADAY;
    public long entryMillis;
    public double netPremium, maxProfit, maxLoss;     // per unit, from actual fills
    public double targetRs, stopRs;                   // ₹ open-P&L levels that trigger exits
    public double entryCharges;
    public boolean live;

    // runtime, not saved
    public double openPnl;     // if closed now: longs sold at bid, shorts bought at ask (used for target)
    public double fairPnl;     // same, but a leg with a wide bid-ask gap is valued at mid (used for stop)
    public long lastMarkMillis;
    public boolean stale;

    public double maxProfitRs() { return Double.isInfinite(maxProfit) ? maxProfit : maxProfit * qty; }
    public double maxLossRs() { return maxLoss * qty; }

    public String summary() {
        StringBuilder b = new StringBuilder(type.title).append(" [");
        for (int i = 0; i < legs.size(); i++) { if (i > 0) b.append(", "); b.append(legs.get(i).label()); }
        return b.append("] ").append(expiry).toString();
    }

    /**
     * Open P&L in ₹ if closed now: long legs sold at bid, short legs bought at ask.
     * Returns NaN if any leg has no usable quote.
     */
    /**
     * Fair value P&L: like {@link #markToMarket(Map)} but any leg whose bid-ask gap is wider than
     * {@code wideGapPct} of its mid is valued at mid, so a wide quote alone cannot trigger a stop.
     */
    public double fairMark(Map<String, OptionQuote> quotes, double wideGapPct) {
        double pnl = 0;
        for (Leg l : legs) {
            OptionQuote q = quotes.get(l.securityId);
            if (q == null) return Double.NaN;
            double px = l.buy ? q.bid : q.ask;
            if (q.hasBook()) {
                double mid = q.mid();
                if (mid > 0 && q.spread() / mid > wideGapPct) px = mid;
            }
            if (px <= 0) return Double.NaN;
            pnl += (l.buy ? px - l.price : l.price - px) * l.ratio * qty;
        }
        return pnl;
    }

    public double markToMarket(Map<String, OptionQuote> quotes) {
        double pnl = 0;
        for (Leg l : legs) {
            OptionQuote q = quotes.get(l.securityId);
            if (q == null) return Double.NaN;
            double px = l.buy ? q.bid : q.ask;
            if (px <= 0) return Double.NaN;
            pnl += (l.buy ? px - l.price : l.price - px) * l.ratio * qty;
        }
        return pnl;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        List<Object> ls = new ArrayList<>();
        for (Leg l : legs) ls.add(l.toMap());
        m.put("legs", ls);
        m.put("qty", (double) qty);
        m.put("expiry", expiry);
        m.put("holding", holding.name());
        m.put("entryMillis", (double) entryMillis);
        m.put("netPremium", netPremium);
        m.put("maxProfit", Double.isInfinite(maxProfit) ? -1.0 : maxProfit);
        m.put("maxLoss", maxLoss);
        m.put("targetRs", targetRs);
        m.put("stopRs", stopRs);
        m.put("entryCharges", entryCharges);
        m.put("live", live);
        return m;
    }

    public static Position fromMap(Map<String, Object> m) {
        Position p = new Position();
        p.type = StrategyGrid.Type.valueOf(Json.str(m, "type", "NONE"));
        for (Object o : Json.arr(m.get("legs"))) p.legs.add(Leg.fromMap(Json.obj(o)));
        p.qty = (int) Json.num(m, "qty", 0);
        p.expiry = Json.str(m, "expiry", "");
        p.holding = Config.Holding.valueOf(Json.str(m, "holding", "INTRADAY"));
        p.entryMillis = (long) Json.num(m, "entryMillis", 0);
        p.netPremium = Json.num(m, "netPremium", 0);
        double mp = Json.num(m, "maxProfit", 0);
        p.maxProfit = mp < 0 ? Double.POSITIVE_INFINITY : mp;
        p.maxLoss = Json.num(m, "maxLoss", 0);
        p.targetRs = Json.num(m, "targetRs", 0);
        p.stopRs = Json.num(m, "stopRs", 0);
        p.entryCharges = Json.num(m, "entryCharges", 0);
        p.live = Boolean.TRUE.equals(m.get("live"));
        return p;
    }
}
