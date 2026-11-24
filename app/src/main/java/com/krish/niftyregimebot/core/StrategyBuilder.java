package com.krish.niftyregimebot.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Turns a strategy type into real strikes from the option chain, checks the prices make
 * sense, and works out max profit / max loss before anything is sent.
 *
 * Strike rules (from the guide):
 *  Debit spreads   buy ATM, sell ATM ± width
 *  Credit spreads  sell the strike nearest {@code creditShortDelta}, buy width further out
 *  Iron condor     sell both sides at {@code condorShortDelta}, buy wings width further out
 *  Butterfly       buy ATM − wing, sell 2 × ATM, buy ATM + wing (calls)
 * Planning prices use what we would actually pay: ask for buys, bid for sells.
 */
public final class StrategyBuilder {
    public static final class Plan {
        public StrategyGrid.Type type = StrategyGrid.Type.NONE;
        public final List<Leg> legs = new ArrayList<>();
        public Payoff payoff;
        public String expiry = "";
        public final List<String> notes = new ArrayList<>();
        public String rejected;   // non-null = do not trade, with the reason

        public boolean ok() { return rejected == null && payoff != null; }

        public String summary() {
            StringBuilder b = new StringBuilder(type.title).append(" [");
            for (int i = 0; i < legs.size(); i++) { if (i > 0) b.append(", "); b.append(legs.get(i).label()); }
            b.append("]");
            return b.toString();
        }
    }

    private final Config cfg;

    public StrategyBuilder(Config cfg) { this.cfg = cfg; }

    public Plan build(StrategyGrid.Type type, double spot, String expiry, Collection<OptionQuote> chain) {
        Plan p = new Plan();
        p.type = type;
        p.expiry = expiry;
        if (type == StrategyGrid.Type.NONE) return reject(p, "no strategy for this regime/IV");
        if (spot <= 0) return reject(p, "no NIFTY price");
        List<OptionQuote> ce = new ArrayList<>(), pe = new ArrayList<>();
        for (OptionQuote q : chain) {
            if (!expiry.equals(q.expiry)) continue;
            (q.right == OptionQuote.Right.CE ? ce : pe).add(q);
        }
        if (ce.isEmpty() || pe.isEmpty()) return reject(p, "option chain not loaded for " + expiry);
        double atm = Math.round(spot / cfg.strikeStep) * cfg.strikeStep;
        double w = cfg.spreadWidth;

        switch (type) {
            case BULL_CALL_SPREAD: {
                OptionQuote buy = at(ce, atm), sell = at(ce, atm + w);
                if (missing(p, buy, sell)) return p;
                p.legs.add(Leg.of(buy, true, 1, buy.ask));
                p.legs.add(Leg.of(sell, false, 1, sell.bid));
                break;
            }
            case BEAR_PUT_SPREAD: {
                OptionQuote buy = at(pe, atm), sell = at(pe, atm - w);
                if (missing(p, buy, sell)) return p;
                p.legs.add(Leg.of(buy, true, 1, buy.ask));
                p.legs.add(Leg.of(sell, false, 1, sell.bid));
                break;
            }
            case BULL_PUT_SPREAD: {
                OptionQuote sell = byDelta(pe, cfg.creditShortDelta, atm - w, true, atm);
                if (sell == null) return reject(p, "no put strike near delta " + cfg.creditShortDelta);
                OptionQuote buy = at(pe, sell.strike - w);
                if (missing(p, buy, sell)) return p;
                p.legs.add(Leg.of(buy, true, 1, buy.ask));
                p.legs.add(Leg.of(sell, false, 1, sell.bid));
                break;
            }
            case BEAR_CALL_SPREAD: {
                OptionQuote sell = byDelta(ce, cfg.creditShortDelta, atm + w, false, atm);
                if (sell == null) return reject(p, "no call strike near delta " + cfg.creditShortDelta);
                OptionQuote buy = at(ce, sell.strike + w);
                if (missing(p, buy, sell)) return p;
                p.legs.add(Leg.of(buy, true, 1, buy.ask));
                p.legs.add(Leg.of(sell, false, 1, sell.bid));
                break;
            }
            case IRON_CONDOR: {
                OptionQuote sp = byDelta(pe, cfg.condorShortDelta, atm - 1.5 * w, true, atm);
                OptionQuote sc = byDelta(ce, cfg.condorShortDelta, atm + 1.5 * w, false, atm);
                if (sp == null || sc == null) return reject(p, "no condor short strikes near delta " + cfg.condorShortDelta);
                OptionQuote bp = at(pe, sp.strike - w), bc = at(ce, sc.strike + w);
                if (missing(p, bp, sp) || missing(p, bc, sc)) return p;
                // Buy the wings first (lower margin), then sell.
                p.legs.add(Leg.of(bp, true, 1, bp.ask));
                p.legs.add(Leg.of(bc, true, 1, bc.ask));
                p.legs.add(Leg.of(sp, false, 1, sp.bid));
                p.legs.add(Leg.of(sc, false, 1, sc.bid));
                break;
            }
            case LONG_CALL_BUTTERFLY: {
                double wing = cfg.butterflyWing;
                OptionQuote lo = at(ce, atm - wing), mid = at(ce, atm), hi = at(ce, atm + wing);
                if (missing(p, lo, mid) || missing(p, hi, mid)) return p;
                p.legs.add(Leg.of(lo, true, 1, lo.ask));
                p.legs.add(Leg.of(hi, true, 1, hi.ask));
                p.legs.add(Leg.of(mid, false, 2, mid.bid));
                break;
            }
            default:
                return reject(p, "unsupported strategy");
        }

        for (Leg l : p.legs) {
            OptionQuote q = find(chain, l.securityId);
            if (q == null || !q.hasBook()) return reject(p, "no live bid/ask for " + l.label());
            double sp = q.spread(), mid = q.mid();
            if (sp > 2.0 && sp > cfg.maxLegSpreadPct * mid)
                return reject(p, String.format(Locale.US, "%s bid-ask too wide (%.2f on %.2f)", l.label(), sp, mid));
        }

        p.payoff = Payoff.of(p.legs);
        if (p.payoff.unlimitedLoss()) return reject(p, "unlimited loss — not allowed");
        if (p.payoff.maxLoss <= 0) return reject(p, "prices look wrong (no possible loss)");
        if (!(p.payoff.maxProfit > 0)) return reject(p, "no possible profit at these prices");

        double width = type == StrategyGrid.Type.LONG_CALL_BUTTERFLY ? cfg.butterflyWing : cfg.spreadWidth;
        if (type.credit) {
            double credit = p.payoff.netPremium;
            if (credit < cfg.minCreditPctOfWidth * width)
                return reject(p, String.format(Locale.US, "credit %.2f is below %.0f%% of the %.0f-point width",
                        credit, cfg.minCreditPctOfWidth * 100, width));
        } else {
            double debit = -p.payoff.netPremium;
            if (debit > cfg.maxDebitPctOfWidth * width)
                return reject(p, String.format(Locale.US, "debit %.2f is above %.0f%% of the %.0f-point width",
                        debit, cfg.maxDebitPctOfWidth * 100, width));
        }
        p.notes.add(String.format(Locale.US, "%s %.2f, max profit %.2f, max loss %.2f per unit",
                p.payoff.netPremium >= 0 ? "credit" : "debit", Math.abs(p.payoff.netPremium),
                p.payoff.maxProfit, p.payoff.maxLoss));
        return p;
    }

    // ------------------------------------------------------------------ helpers

    private static Plan reject(Plan p, String why) { p.rejected = why; return p; }

    private static boolean missing(Plan p, OptionQuote a, OptionQuote b) {
        if (a == null || b == null) { reject(p, "strike not in the option chain"); return true; }
        return false;
    }

    static OptionQuote at(List<OptionQuote> side, double strike) {
        for (OptionQuote q : side) if (Math.abs(q.strike - strike) < 0.01) return q;
        return null;
    }

    static OptionQuote find(Collection<OptionQuote> all, String id) {
        for (OptionQuote q : all) if (q.securityId.equals(id)) return q;
        return null;
    }

    /**
     * OTM strike whose |delta| is closest to {@code target}. If the chain has no deltas,
     * falls back to {@code fallbackStrike}. Puts must be below ATM, calls above.
     */
    static OptionQuote byDelta(List<OptionQuote> side, double target, double fallbackStrike, boolean put, double atm) {
        OptionQuote best = null;
        double bestGap = Double.MAX_VALUE;
        for (OptionQuote q : side) {
            if (put ? q.strike >= atm : q.strike <= atm) continue;
            double d = Math.abs(q.delta);
            if (d <= 0) continue;
            double gap = Math.abs(d - target);
            if (gap < bestGap) { bestGap = gap; best = q; }
        }
        if (best != null && bestGap <= 0.10) return best;
        double snap = Math.round(fallbackStrike / 50.0) * 50.0;
        return at(side, snap);
    }
}
