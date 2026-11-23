package com.krish.niftydhanbot.core;

/** The one open trade the bot is managing (long option). */
public final class Position {
    public final String securityId;
    public final String label;
    public final Signal.Side side;
    public int qty;            // shrinks if an exit fills partly
    public final double entryPrice;
    public final long entryMillis;
    public final double initialStop;
    public final double riskPerUnit;   // entry − initial stop
    public final double target;
    public double stop;                // moves up only
    public double best;                // best bid seen since entry
    public double lastBid;
    public long lastQuoteMillis;
    public String stopReason = "initial stop";

    public Position(String securityId, String label, Signal.Side side, int qty, double entryPrice,
                    long entryMillis, double stop, double target) {
        this.securityId = securityId;
        this.label = label;
        this.side = side;
        this.qty = qty;
        this.entryPrice = entryPrice;
        this.entryMillis = entryMillis;
        this.initialStop = stop;
        this.stop = stop;
        this.riskPerUnit = entryPrice - stop;
        this.target = target;
        this.best = entryPrice;
        this.lastBid = entryPrice;
        this.lastQuoteMillis = entryMillis;
    }

    public double openPnl() { return (lastBid - entryPrice) * qty; }
    public double rMultiple() { return riskPerUnit > 0 ? (lastBid - entryPrice) / riskPerUnit : 0; }
}
