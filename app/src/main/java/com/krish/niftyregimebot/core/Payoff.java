package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Expiry payoff of a set of legs, per 1 unit of the strategy (multiply by quantity for ₹).
 * Option payoffs are straight lines between strikes, so checking every strike plus the two
 * ends gives the exact max profit, max loss and breakevens.
 */
public final class Payoff {
    public double netPremium;         // + credit received, − debit paid
    public double maxProfit;          // Double.POSITIVE_INFINITY if unlimited
    public double maxLoss;            // positive number; POSITIVE_INFINITY if unlimited
    public final List<Double> breakevens = new ArrayList<>();

    public boolean unlimitedLoss() { return Double.isInfinite(maxLoss); }

    public static Payoff of(List<Leg> legs) {
        Payoff p = new Payoff();
        for (Leg l : legs) p.netPremium += (l.buy ? -l.price : l.price) * l.ratio;

        TreeSet<Double> pts = new TreeSet<>();
        pts.add(0.0);
        double hi = 0;
        for (Leg l : legs) { pts.add(l.strike); hi = Math.max(hi, l.strike); }
        double top = hi * 2 + 1000;
        pts.add(top);

        // slope above the highest strike = net long calls
        double slope = 0;
        for (Leg l : legs) if (l.right == OptionQuote.Right.CE) slope += (l.buy ? 1 : -1) * l.ratio;

        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        Double prevX = null, prevY = null;
        for (double x : pts) {
            double y = value(legs, x);
            max = Math.max(max, y);
            min = Math.min(min, y);
            if (prevX != null) {
                if (prevY == 0 && !p.breakevens.contains(prevX) && prevX > 0) p.breakevens.add(prevX);
                else if ((prevY < 0 && y > 0) || (prevY > 0 && y < 0)) {
                    double be = prevX + (0 - prevY) * (x - prevX) / (y - prevY);
                    p.breakevens.add(Math.round(be * 100) / 100.0);
                }
            }
            prevX = x;
            prevY = y;
        }
        p.maxProfit = slope > 0 ? Double.POSITIVE_INFINITY : round(max);
        p.maxLoss = slope < 0 ? Double.POSITIVE_INFINITY : round(Math.max(0, -min));
        return p;
    }

    public static double value(List<Leg> legs, double spot) {
        double v = 0;
        for (Leg l : legs) v += l.expiryPnl(spot);
        return v;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
