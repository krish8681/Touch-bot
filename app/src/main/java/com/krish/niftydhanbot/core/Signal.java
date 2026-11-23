package com.krish.niftydhanbot.core;

import java.util.ArrayList;
import java.util.List;

/** Output of the signal engine. strength is a rule score (0–100), NOT a probability. */
public final class Signal {
    public enum Side { CE, PE, NO_TRADE }
    public enum Regime { UP, DOWN, SIDEWAYS, WARMING_UP }

    public final Side side;
    public final Regime regime;
    public final int strength;
    public final double spot;
    public final double atr;
    public final long timeMillis;
    public final List<String> reasons;

    public Signal(Side side, Regime regime, int strength, double spot, double atr, long timeMillis, List<String> reasons) {
        this.side = side;
        this.regime = regime;
        this.strength = strength;
        this.spot = spot;
        this.atr = atr;
        this.timeMillis = timeMillis;
        this.reasons = new ArrayList<>(reasons);
    }

    public String reasonText() { return String.join("; ", reasons); }
}
