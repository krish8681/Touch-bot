package com.krish.niftyregimebot.core;

/**
 * The guide's section 8 grid: trend (from {@link RegimeDetector}) × IV level (from {@link IvGauge}).
 * Hedged / defined-risk strategies only — no naked option selling.
 *
 *              IV LOW                 IV NORMAL          IV HIGH
 *  UP          Bull call spread       Bull call spread   Bull put spread
 *  DOWN        Bear put spread        Bear put spread    Bear call spread
 *  SIDEWAYS    Long call butterfly    Iron condor        Iron condor
 */
public final class StrategyGrid {
    public enum Type {
        BULL_CALL_SPREAD("Bull call spread", Bias.UP, false),
        BULL_PUT_SPREAD("Bull put spread", Bias.UP, true),
        BEAR_PUT_SPREAD("Bear put spread", Bias.DOWN, false),
        BEAR_CALL_SPREAD("Bear call spread", Bias.DOWN, true),
        IRON_CONDOR("Iron condor", Bias.RANGE, true),
        LONG_CALL_BUTTERFLY("Long call butterfly", Bias.RANGE, false),
        NONE("No trade", Bias.RANGE, false);

        public final String title;
        public final Bias bias;
        public final boolean credit;

        Type(String title, Bias bias, boolean credit) {
            this.title = title;
            this.bias = bias;
            this.credit = credit;
        }
    }

    public enum Bias { UP, DOWN, RANGE }

    private StrategyGrid() {}

    /**
     * Holding-aware pick. INTRADAY uses debit strategies only: credit spreads and the condor earn
     * from time decay, which is small inside one day (the expiry-day contract is never used).
     * UP/DOWN with high IV → the debit spread; SIDEWAYS → long call butterfly only if allowed,
     * else no trade.
     */
    public static Type pick(RegimeDetector.Regime regime, IvGauge.Level iv, Config cfg) {
        Type t = pick(regime, iv);
        if (cfg.holding != Config.Holding.INTRADAY || !t.credit) {
            if (cfg.holding == Config.Holding.INTRADAY && t == Type.LONG_CALL_BUTTERFLY && !cfg.intradayButterfly) return Type.NONE;
            return t;
        }
        switch (t) {
            case BULL_PUT_SPREAD: return Type.BULL_CALL_SPREAD;
            case BEAR_CALL_SPREAD: return Type.BEAR_PUT_SPREAD;
            case IRON_CONDOR: return cfg.intradayButterfly ? Type.LONG_CALL_BUTTERFLY : Type.NONE;
            default: return t;
        }
    }

    public static Type pick(RegimeDetector.Regime regime, IvGauge.Level iv) {
        if (regime == RegimeDetector.Regime.UNKNOWN || iv == IvGauge.Level.UNKNOWN) return Type.NONE;
        switch (regime) {
            case UP: return iv == IvGauge.Level.HIGH ? Type.BULL_PUT_SPREAD : Type.BULL_CALL_SPREAD;
            case DOWN: return iv == IvGauge.Level.HIGH ? Type.BEAR_CALL_SPREAD : Type.BEAR_PUT_SPREAD;
            case SIDEWAYS: return iv == IvGauge.Level.LOW ? Type.LONG_CALL_BUTTERFLY : Type.IRON_CONDOR;
            default: return Type.NONE;
        }
    }

    /** True if the current regime goes against an open trade's bias (exit signal). */
    public static boolean regimeAgainst(Type open, RegimeDetector.Regime now, double adx, Config cfg) {
        switch (open.bias) {
            case UP: return now == RegimeDetector.Regime.DOWN;
            case DOWN: return now == RegimeDetector.Regime.UP;
            case RANGE:
                return (now == RegimeDetector.Regime.UP || now == RegimeDetector.Regime.DOWN) && adx >= cfg.adxStrong;
            default: return false;
        }
    }
}
