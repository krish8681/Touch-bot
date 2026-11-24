package com.krish.niftyregimebot.core;

import java.util.Locale;

/**
 * Lots from max loss (guide section 13): lots = risk budget ÷ (max loss per lot + charges).
 * Also capped by max lots, the NSE freeze limit, and money available.
 */
public final class Sizer {
    public static final class Result {
        public int lots, qty;
        public double maxLossRs, maxProfitRs, chargesRs, capitalNeeded;
        public String why = "";

        public boolean ok() { return lots > 0; }
    }

    private final Config cfg;
    private final Charges charges;

    public Sizer(Config cfg, Charges charges) {
        this.cfg = cfg;
        this.charges = charges;
    }

    /** Estimated round-trip charges for all legs at the planned prices. */
    public double chargesFor(StrategyBuilder.Plan p, int qty) {
        double c = 0;
        for (Leg l : p.legs) c += charges.roundTrip(l.price, l.price, qty * l.ratio);
        return c;
    }

    public Result size(StrategyBuilder.Plan p, double riskBudget, double availableFunds) {
        Result r = new Result();
        double perLotLoss = p.payoff.maxLoss * cfg.lotSize;
        double perLotCharges = chargesFor(p, cfg.lotSize);
        if (perLotLoss <= 0) { r.why = "max loss is zero"; return r; }
        int lots = (int) Math.floor(riskBudget / (perLotLoss + perLotCharges));
        if (lots < 1) {
            r.why = String.format(Locale.US, "one lot risks ₹%.0f, above the ₹%.0f budget", perLotLoss + perLotCharges, riskBudget);
            return r;
        }
        lots = Math.min(lots, Math.min(cfg.maxLots, cfg.maxLotsByFreeze()));
        // Money check: a debit trade pays the debit; a credit spread blocks about its max loss as margin.
        double perLotCapital = p.payoff.netPremium < 0 ? -p.payoff.netPremium * cfg.lotSize : perLotLoss * 1.2;
        while (lots > 0 && perLotCapital * lots > availableFunds) lots--;
        if (lots < 1) { r.why = "not enough funds for one lot"; return r; }
        r.lots = lots;
        r.qty = lots * cfg.lotSize;
        r.maxLossRs = perLotLoss * lots;
        r.maxProfitRs = Double.isInfinite(p.payoff.maxProfit) ? Double.POSITIVE_INFINITY : p.payoff.maxProfit * r.qty;
        r.chargesRs = chargesFor(p, r.qty);
        r.capitalNeeded = perLotCapital * lots;
        return r;
    }
}
