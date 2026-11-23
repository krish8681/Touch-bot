package com.krish.niftydhanbot;

import com.krish.niftydhanbot.core.BotState;
import com.krish.niftydhanbot.core.DayStats;
import com.krish.niftydhanbot.core.Signal;

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

    public static final class PositionView {
        public String label;
        public int qty;
        public double entry, stop, target, bid, openPnl, rMultiple;
        public long entryMillis;
        public String stopReason;
    }

    public static final class Snapshot {
        public BotState state = BotState.OFF;
        public String stateWhy = "";
        public double spot;
        public long spotMillis;
        public boolean feedConnected;
        public String feedNote = "";
        public double dhanFunds = -1;
        public String expiry = "";
        public int contracts;
        public DayStats day = new DayStats();
        public PositionView position;
        public boolean pendingOrder;
        public long lastReconcileMillis;
        public String lastError = "";
        public long startedMillis;
    }

    private static Snapshot snap = new Snapshot();
    private static final Deque<String> logs = new ArrayDeque<>();
    private static final Deque<Signal> signals = new ArrayDeque<>();

    public static synchronized Snapshot get() { return snap; }
    public static synchronized void set(Snapshot s) { snap = s; }

    public static synchronized void log(String line) {
        logs.addFirst(line);
        while (logs.size() > 400) logs.removeLast();
    }

    public static synchronized List<String> logs() { return new ArrayList<>(logs); }

    public static synchronized void signal(Signal s) {
        signals.addFirst(s);
        while (signals.size() > 120) signals.removeLast();
    }

    public static synchronized List<Signal> signals() { return new ArrayList<>(signals); }
}
