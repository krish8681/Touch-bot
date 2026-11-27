package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.Engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Read-only snapshot the screen shows. The engine thread writes; the UI reads once a second.
 * The screen never talks to the engine directly — it sends commands to {@link BotService}.
 */
public final class BotHub {
    private BotHub() {}

    public static final class LegView {
        public String label;
        public boolean buy;
        public double entry, now;
    }

    public static final class PositionView {
        public String title, expiry, holding;
        public int qty, lots;
        public double netPremium, openPnl, fairPnl, target, stop, maxLoss, maxProfit;
        public long entryMillis;
        public boolean stale;
        public final List<LegView> legs = new ArrayList<>();
    }

    public static final class Snapshot {
        public BotState state = BotState.OFF;
        public boolean live;
        public String holding = "INTRADAY";
        public double spot, vix = Double.NaN;
        public long spotMillis;
        public boolean feedConnected;
        public String feedNote = "";
        public double dhanFunds = -1;
        public String expiry = "";
        public int contracts;
        public String regime = "-", regimeWhy = "", ivText = "-", pick = "-", lastAction = "";
        public DayStats day = new DayStats();
        public PositionView position;
        public String job = "";
        public String lastError = "";
        public long startedMillis;
        public double tokenAgeHours = -1;
        public boolean autoRenew;
        public int barsFixed;
        public boolean waitingForDhanBar;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> checks = new ArrayList<>();
    }

    private static Snapshot snap = new Snapshot();
    private static final Deque<String> logs = new ArrayDeque<>();
    private static final Deque<String> decisions = new ArrayDeque<>();

    public static synchronized Snapshot get() { return snap; }
    public static synchronized void set(Snapshot s) { snap = s; }

    public static synchronized void log(String line) {
        logs.addFirst(line);
        while (logs.size() > 500) logs.removeLast();
    }

    public static synchronized List<String> logs() { return new ArrayList<>(logs); }

    public static synchronized void decision(String line) {
        decisions.addFirst(line);
        while (decisions.size() > 200) decisions.removeLast();
    }

    public static synchronized List<String> decisions() { return new ArrayList<>(decisions); }

    /** Formats a decision for the Decisions tab. */
    public static String describe(Engine.Decision d, String time) {
        StringBuilder b = new StringBuilder(time).append("  ");
        b.append(d.regime == null ? "-" : d.regime.confirmed).append(" · ");
        b.append(d.iv == null ? "IV ?" : "IV " + d.iv.level).append(" → ").append(d.pick.title).append("\n  ");
        b.append(d.action);
        if (d.regime != null && !d.regime.text().isEmpty()) b.append("\n  ").append(d.regime.text());
        if (d.iv != null) b.append("\n  ").append(d.iv.text());
        return b.toString();
    }
}
