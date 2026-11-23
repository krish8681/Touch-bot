package com.krish.niftydhanbot.core;

/**
 * Quantity from the risk budget: loss per unit = (entry − stop) + slippage + charges per unit.
 * Rounds DOWN to whole lots. Zero means "even one lot is too risky" → NO TRADE.
 */
public final class PositionSizer {
    private final BotConfig cfg;
    private final Charges charges;

    public PositionSizer(BotConfig cfg, Charges charges) {
        this.cfg = cfg;
        this.charges = charges;
    }

    public static final class Result {
        public final int lots, qty;
        public final double lossPerUnit, worstCaseLoss;
        Result(int lots, int qty, double lossPerUnit, double worstCaseLoss) {
            this.lots = lots; this.qty = qty; this.lossPerUnit = lossPerUnit; this.worstCaseLoss = worstCaseLoss;
        }
    }

    public Result size(double entry, double stop, double availableFunds) {
        if (!(entry > 0) || !(stop > 0) || stop >= entry || cfg.lotSize <= 0) return new Result(0, 0, Double.NaN, 0);
        int lot = cfg.lotSize;
        double chargesPerUnit = charges.roundTrip(entry, stop, lot) / lot;
        double lossPerUnit = (entry - stop) + cfg.paperSlippagePts * 2 + chargesPerUnit;
        int lots = (int) Math.floor(cfg.riskPerTrade / (lossPerUnit * lot));
        int byFunds = (int) Math.floor(availableFunds / ((entry + cfg.entryBufferPts) * lot + 100));
        lots = Math.max(0, Math.min(lots, Math.min(byFunds, cfg.maxLots)));
        int qty = lots * lot;
        double worst = lots == 0 ? 0 : (entry - stop + cfg.paperSlippagePts * 2) * qty + charges.roundTrip(entry, stop, qty);
        return new Result(lots, qty, lossPerUnit, worst);
    }
}
