package com.krish.niftyregimebot.core;

/** One price bar. startMillis is the bar's start (epoch ms). */
public final class Candle {
    public final long startMillis;
    public double open, high, low, close;
    /** When the bot saw this bar's first live tick (0 = bar came from Dhan's history). */
    public long firstTickMillis;

    public Candle(long startMillis, double open, double high, double low, double close) {
        this.startMillis = startMillis;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    @Override public String toString() {
        return "Candle{" + startMillis + " o=" + open + " h=" + high + " l=" + low + " c=" + close + "}";
    }
}
