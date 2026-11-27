package com.krish.niftyregimebot.core;

/**
 * Every tunable number in one place. Defaults follow the strategy guide:
 * hedged/defined-risk only, size by max loss, few trades, avoid same-day expiry.
 */
public final class Config {
    public enum Holding { INTRADAY, WEEKLY }

    // ---- money and risk
    public double capital = 300_000;          // ₹ used for paper fills and margin checks
    public double riskPerTrade = 10_000;      // ₹ max loss allowed per trade (at the strategy's max loss)
    public double dailyLossLimit = 20_000;    // ₹ stop new entries for the day after this realised loss
    public int maxTradesPerDay = 3;
    public int maxConsecutiveLosses = 2;
    public int cooldownMinutes = 10;          // wait after an exit before a new entry

    // ---- contract
    public int lotSize = 65;                  // NIFTY lot (NSE, from Jan 2026). Check before trading.
    public int maxLots = 10;
    public int freezeQty = 1800;              // NSE NIFTY quantity freeze per order
    public double strikeStep = 50;

    // ---- holding style
    public Holding holding = Holding.INTRADAY;

    // ---- regime detection (trend-following, no prediction)
    public int intradayBarMinutes = 5;        // regime candles for intraday
    public int weeklyBarMinutes = 60;         // regime candles for weekly holding
    public int emaFast = 9, emaSlow = 21, adxPeriod = 14;
    public double adxTrend = 20;              // ADX at/above this = trending
    public double adxStrong = 25;             // condor exits if a strong trend starts
    public int confirmBars = 2;               // a new regime must repeat this many bars

    // ---- IV level (India VIX percentile over ~1 year; fallback: ATM IV)
    public double ivLowPct = 30, ivHighPct = 60;
    public double atmIvLow = 11, atmIvHigh = 16;   // fallback thresholds when VIX history is missing

    // ---- strike rules
    public double spreadWidth = 200;          // points between bought and sold strike
    public double creditShortDelta = 0.30;    // short strike for bull put / bear call
    public double condorShortDelta = 0.20;    // short strikes for iron condor
    public double butterflyWing = 100;
    public double minCreditPctOfWidth = 0.20; // credit spreads: credit must be ≥ 20% of width
    public double maxDebitPctOfWidth = 0.65;  // debit spreads: debit must be ≤ 65% of width
    public double maxLegSpreadPct = 0.15;     // bid-ask spread ≤ 15% of mid (or ≤ ₹2)

    // ---- exits
    public double targetPctCredit = 0.50;     // close credit trades at 50% of max profit
    public double targetPctDebit = 0.60;      // close debit trades at 60% of max profit
    public double targetPctButterfly = 0.40;
    public double stopPctOfMaxLoss = 0.50;    // close if loss reaches 50% of max loss

    // ---- clock (IST minutes after midnight)
    public int entryStart = 9 * 60 + 45;
    public int entryEnd = 14 * 60 + 30;
    public int squareOff = 15 * 60 + 15;      // intraday exit; weekly: day-before-expiry exit
    public int minDaysToExpiryIntraday = 1;   // never trade a contract on its expiry day
    public int minDaysToExpiryWeekly = 3;     // weekly entries use an expiry ≥ 3 calendar days away

    // ---- v1.3 entry filters and guards (each can be switched off in Settings)
    public boolean confirm15m = true;         // INTRADAY: 15-min trend must agree with the 5-min trend
    public boolean chopFilter = true;         // no trend entry while ADX is falling or the day's range is tiny
    public int adxFallBars = 3;               // ADX now lower than this many bars ago = falling
    public double minDayRangePct = 0.0025;    // day's high-low so far must be ≥ 0.25% of NIFTY
    public boolean pcrFilter = false;         // bullish entry needs put OI not shrinking vs calls (and the reverse)
    public double pcrMaxChange = 0.10;        // PCR moved more than 10% against the trade since 09:30 → skip
    public double pcrBandPts = 500;           // strikes within ±500 of NIFTY count for PCR
    public boolean eventFilter = true;        // no entries on RBI policy / Budget days; WEEKLY exits the day before
    public double cooldownAfterWinMinutes = 3; // shorter wait after a winning trade
    public boolean reenterAfterFlip = true;   // after a trend-flip exit, a new trade with the new trend may start at once
    public double entryMaxSpotMove = 25;      // NIFTY moving more than this while legging in → undo the entry
    public boolean gapCheck = true;           // WEEKLY: exit at the open if NIFTY gaps against the trade
    public double gapExitPct = 0.008;         // gap of 0.8% or more

    // ---- v1.2
    public double wideGapPct = 0.05;          // leg bid-ask gap above 5% of mid → stop check uses mid
    public double hardStopPctOfMaxLoss = 0.80; // close-now loss beyond 80% of max loss always stops
    public double minTargetToCharges = 3.0;   // skip a trade whose profit target is under 3× its charges
    public boolean intradayButterfly = false; // INTRADAY sideways: false = no trade, true = long call butterfly

    // ---- bar data
    public long dhanBarWaitMillis = 60_000;   // after a bar closes, wait this long for Dhan's own copy of it
    public long partialBarMillis = 30_000;    // own bar whose first tick came later than this is incomplete

    // ---- paper fills
    public double paperSlippage = 0.5;        // ₹ per unit per leg
    public double limitBuffer = 1.0;          // ₹ added to ask (buy) / taken from bid (sell)
    public long depthMaxAgeMillis = 10_000;   // paper fills use market depth only if it is this fresh

    public int maxLotsByFreeze() { return Math.max(1, freezeQty / Math.max(1, lotSize)); }

    public int barMinutes() { return holding == Holding.WEEKLY ? weeklyBarMinutes : intradayBarMinutes; }
}
