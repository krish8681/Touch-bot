package com.krish.niftydhanbot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Trend-following rules on NIFTY 1-minute candles. It does not forecast; it only
 * says CE when the market is already trending up and breaks out, PE for the mirror,
 * and NO_TRADE otherwise (sideways or unclear). Unvalidated starting rules.
 */
public final class SignalEngine {
    private final BotConfig cfg;

    public SignalEngine(BotConfig cfg) { this.cfg = cfg; }

    public Signal evaluate(List<Candle> bars, long nowMillis) {
        List<String> why = new ArrayList<>();
        int n = bars.size();
        int need = Math.max(cfg.emaSlow + 6, cfg.atrPeriod + 2);
        if (n < need) {
            why.add("warming up: " + n + "/" + need + " candles");
            return new Signal(Signal.Side.NO_TRADE, Signal.Regime.WARMING_UP, 0,
                    n == 0 ? 0 : bars.get(n - 1).close, Double.NaN, nowMillis, why);
        }
        int last = n - 1;
        double close = bars.get(last).close;
        double fast = Indicators.ema(bars, cfg.emaFast, last);
        double slow = Indicators.ema(bars, cfg.emaSlow, last);
        double slowBefore = Indicators.ema(bars, cfg.emaSlow, last - 5);
        double atr = Indicators.atr(bars, cfg.atrPeriod, last);
        if (!(atr > 0)) {
            why.add("ATR is zero (no movement)");
            return new Signal(Signal.Side.NO_TRADE, Signal.Regime.SIDEWAYS, 0, close, atr, nowMillis, why);
        }
        double strength = (fast - slow) / atr;

        Signal.Regime regime;
        if (fast > slow && slow > slowBefore && close > fast) regime = Signal.Regime.UP;
        else if (fast < slow && slow < slowBefore && close < fast) regime = Signal.Regime.DOWN;
        else regime = Signal.Regime.SIDEWAYS;

        why.add(String.format(java.util.Locale.US, "EMA%d %.1f vs EMA%d %.1f, ATR %.1f, trend %.2f ATR",
                cfg.emaFast, fast, cfg.emaSlow, slow, atr, strength));

        if (regime == Signal.Regime.SIDEWAYS) {
            why.add("no clear trend");
            return new Signal(Signal.Side.NO_TRADE, regime, 0, close, atr, nowMillis, why);
        }
        if (Math.abs(strength) < cfg.minTrendStrengthAtr) {
            why.add(String.format(java.util.Locale.US, "trend too weak (%.2f < %.2f)", Math.abs(strength), cfg.minTrendStrengthAtr));
            return new Signal(Signal.Side.NO_TRADE, regime, 20, close, atr, nowMillis, why);
        }

        int from = last - cfg.breakoutLookback;
        double prevHigh = Indicators.highest(bars, from, last - 1);
        double prevLow = Indicators.lowest(bars, from, last - 1);
        double chase = Math.abs(close - fast) / atr;

        if (regime == Signal.Regime.UP) {
            if (close <= prevHigh) {
                why.add(String.format(java.util.Locale.US, "uptrend but no breakout (close %.1f ≤ high %.1f)", close, prevHigh));
                return new Signal(Signal.Side.NO_TRADE, regime, 40, close, atr, nowMillis, why);
            }
            if (chase > cfg.maxChaseAtr) {
                why.add(String.format(java.util.Locale.US, "too stretched above EMA (%.2f ATR)", chase));
                return new Signal(Signal.Side.NO_TRADE, regime, 50, close, atr, nowMillis, why);
            }
            why.add(String.format(java.util.Locale.US, "breakout above %.1f", prevHigh));
            return new Signal(Signal.Side.CE, regime, score(strength, chase), close, atr, nowMillis, why);
        } else {
            if (close >= prevLow) {
                why.add(String.format(java.util.Locale.US, "downtrend but no breakdown (close %.1f ≥ low %.1f)", close, prevLow));
                return new Signal(Signal.Side.NO_TRADE, regime, 40, close, atr, nowMillis, why);
            }
            if (chase > cfg.maxChaseAtr) {
                why.add(String.format(java.util.Locale.US, "too stretched below EMA (%.2f ATR)", chase));
                return new Signal(Signal.Side.NO_TRADE, regime, 50, close, atr, nowMillis, why);
            }
            why.add(String.format(java.util.Locale.US, "breakdown below %.1f", prevLow));
            return new Signal(Signal.Side.PE, regime, score(strength, chase), close, atr, nowMillis, why);
        }
    }

    private int score(double strength, double chase) {
        double s = 60 + Math.min(30, Math.abs(strength) * 30) + Math.max(0, 10 - chase * 6);
        return (int) Math.max(0, Math.min(100, Math.round(s)));
    }
}
