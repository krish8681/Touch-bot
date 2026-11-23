package com.krish.niftydhanbot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Independent check that every new entry must pass. Any failed rule = no order.
 * It only ever blocks entries; it never blocks exits.
 */
public final class RiskGate {
    private final BotConfig cfg;

    public RiskGate(BotConfig cfg) { this.cfg = cfg; }

    public static final class Request {
        public BotState state;
        public DayStats day;
        public int openPositions;
        public boolean pendingOrder;
        public double openPnl;            // unrealized P&L of anything open
        public int minuteOfDayIst;
        public long now;
        public long lastSpotMillis;
        public OptionQuote quote;
        public PositionSizer.Result size;
        public double availableFunds;
        public double limitPrice;
    }

    public static final class Decision {
        public final boolean allowed;
        public final List<String> failures;
        Decision(List<String> f) { this.failures = f; this.allowed = f.isEmpty(); }
        public String text() { return allowed ? "all checks passed" : String.join("; ", failures); }
    }

    public Decision check(Request r) {
        List<String> f = new ArrayList<>();
        if (r.state != BotState.TRADING) f.add("bot is " + r.state + ", not TRADING");
        if (r.pendingOrder) f.add("an order is still pending");
        if (r.openPositions >= cfg.maxOpenPositions) f.add("already holding " + r.openPositions + " position(s)");
        if (r.day.entries >= cfg.maxEntriesPerDay) f.add("daily entry limit reached (" + r.day.entries + ")");
        if (r.day.consecutiveLosses >= cfg.maxConsecutiveLosses) f.add(r.day.consecutiveLosses + " losses in a row — stopped for today");

        double dayPnl = r.day.realizedPnl + r.openPnl;
        if (dayPnl <= -cfg.dailyLossLimit) f.add(money("daily loss limit hit", dayPnl));
        if (r.day.weekPnlBefore + dayPnl <= -cfg.weeklyLossLimit) f.add(money("weekly loss limit hit", r.day.weekPnlBefore + dayPnl));
        if (r.day.monthPnlBefore + dayPnl <= -cfg.monthlyLossLimit) f.add(money("monthly loss limit hit", r.day.monthPnlBefore + dayPnl));
        // Worst case of the new trade must not push the day past the cap
        if (r.size != null && dayPnl - r.size.worstCaseLoss < -cfg.dailyLossLimit)
            f.add("this trade's worst case would break the daily loss limit");

        if (r.minuteOfDayIst < cfg.entryStartMin || r.minuteOfDayIst > cfg.entryEndMin) f.add("outside entry time window");
        if (r.now - r.day.lastExitMillis < cfg.cooldownMinAfterExit * 60_000L) f.add("cooling down after last exit");
        if (r.now - r.lastSpotMillis > cfg.maxSpotAgeMs) f.add("NIFTY price feed is stale");

        if (r.quote == null) f.add("no contract selected");
        else {
            if (r.now - r.quote.updatedMillis > cfg.maxQuoteAgeMs) f.add("option quote is stale");
            if (!(r.quote.bid > 0 && r.quote.ask > 0)) f.add("option has no two-sided quote");
        }
        if (r.size == null || r.size.lots <= 0) f.add("even 1 lot is over the per-trade risk budget or funds");
        else {
            if (r.size.qty % cfg.lotSize != 0) f.add("quantity is not a whole number of lots");
            if (r.size.worstCaseLoss > cfg.riskPerTrade * 1.001) f.add(money("worst-case loss over budget", -r.size.worstCaseLoss));
            double need = r.size.qty * r.limitPrice;
            if (need > r.availableFunds) f.add(String.format(Locale.US, "not enough funds (need ₹%.0f, have ₹%.0f)", need, r.availableFunds));
        }
        return new Decision(f);
    }

    private static String money(String label, double v) { return String.format(Locale.US, "%s (₹%.0f)", label, v); }
}
