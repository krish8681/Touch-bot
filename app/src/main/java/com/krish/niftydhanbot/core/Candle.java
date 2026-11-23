package com.krish.niftydhanbot.core;

/** One 1-minute bar. startMillis is the minute's start (epoch ms). */
public final class Candle {
    public final long startMillis;
    public double open, high, low, close;

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
