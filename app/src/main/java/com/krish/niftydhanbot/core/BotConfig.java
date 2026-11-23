package com.krish.niftydhanbot.core;

/**
 * Every tunable number in one place. Defaults come from the project record
 * (₹3 lakh capital, ₹1,500 risk per trade, ₹6,000 daily cap, 3 entries, 1 position,
 * 2 consecutive losses). They are starting points, not proven-optimal values.
 */
public final class BotConfig {
    // Account and risk
    public double capital = 300_000;
    public double riskPerTrade = 1_500;
    public double dailyLossLimit = 6_000;
    public double weeklyLossLimit = 12_000;
    public double monthlyLossLimit = 24_000;
    public int maxEntriesPerDay = 3;
    public int maxOpenPositions = 1;
    public int maxConsecutiveLosses = 2;

    /** NIFTY lot size. Exchange revises this — check the current value before trading. */
    public int lotSize = 65;
    /** Hard cap on lots per trade regardless of what sizing allows. */
    public int maxLots = 10;

    // Trading window (IST, minutes after midnight)
    public int entryStartMin = 9 * 60 + 25;
    public int entryEndMin = 14 * 60 + 45;
    public int squareOffMin = 15 * 60 + 15;

    /** Which entry rules to use: "TREND" (1-min trend breakout) or "ORB" (opening-range breakout). */
    public String strategy = "TREND";

    // Opening-range breakout
    public int orbMinutes = 15;                // range = first N minutes from 9:15
    public int orbLastEntryMin = 12 * 60;      // no ORB entries after this time
    public double orbStopRangeFrac = 0.5;      // stop distance = this × range (in NIFTY points)
    public double orbMaxRangePts = 150;        // skip days with a huge opening range

    // Signal (trend-following: trade with the current trend, never predict)
    public int emaFast = 9;
    public int emaSlow = 21;
    public int atrPeriod = 14;
    public int breakoutLookback = 5;
    public double minTrendStrengthAtr = 0.30;  // (EMAfast-EMAslow)/ATR
    public double maxChaseAtr = 1.5;           // don't buy if price is this far past EMAfast
    public int cooldownMinAfterExit = 5;

    // Strike selection
    public double targetDelta = 0.55;
    public double minPremium = 40;
    public double maxPremium = 450;
    public double maxSpreadPct = 1.5;          // of mid price
    public double maxSpreadAbs = 2.0;          // rupees
    public double minOi = 5_000;

    // Exits (all in option premium terms unless stated)
    public double stopAtrMultiple = 1.2;       // spot ATR × this × delta = premium stop
    public double minStopPoints = 4;           // premium points
    public double targetR = 2.0;               // first target at 2× risk
    public double breakevenAtR = 1.0;          // move stop to cost after +1R
    public double trailGivebackR = 0.8;        // trail: give back at most 0.8R from best
    public int maxHoldMinutes = 15;

    // Orders
    public double entryBufferPts = 0.5;        // limit = ask + buffer
    public double paperSlippagePts = 0.5;      // paper fills this much worse than quote

    // Data safety
    public long maxQuoteAgeMs = 5_000;
    public long maxSpotAgeMs = 5_000;

    public boolean avoidExpiryDayContract = true;
}
