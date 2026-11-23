package com.krish.niftydhanbot.core;

/**
 * Estimated cost of an index-option BUY-then-SELL round trip.
 * Rates are estimates for NSE options as configured here; check Dhan's current
 * charge sheet and update these fields if they differ.
 */
public final class Charges {
    public double brokeragePerOrder = 20.0;
    public double exchangeTxnRate = 0.0003503;  // on premium turnover, both sides
    public double sebiRate = 0.000001;          // ₹10 per crore
    public double sttSellRate = 0.001;          // 0.1% on sell-side premium
    public double stampBuyRate = 0.00003;       // 0.003% on buy-side premium
    public double gstRate = 0.18;               // on brokerage + exchange + SEBI

    public double roundTrip(double buyPrice, double sellPrice, int qty) {
        double buyVal = buyPrice * qty;
        double sellVal = sellPrice * qty;
        double turnover = buyVal + sellVal;
        double brokerage = brokeragePerOrder * 2;
        double exch = turnover * exchangeTxnRate;
        double sebi = turnover * sebiRate;
        double stt = sellVal * sttSellRate;
        double stamp = buyVal * stampBuyRate;
        double gst = (brokerage + exch + sebi) * gstRate;
        return brokerage + exch + sebi + stt + stamp + gst;
    }
}
