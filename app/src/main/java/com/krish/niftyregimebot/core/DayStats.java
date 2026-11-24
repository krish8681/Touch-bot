package com.krish.niftyregimebot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counters the risk gate needs. Saved by the app so a restart does not reset limits. */
public final class DayStats {
    public String date = "";          // yyyy-MM-dd (IST)
    public int entries;
    public int wins, losses;
    public int consecutiveLosses;
    public double realizedPnl;        // net of estimated charges
    public double weekPnlBefore;      // realized this week before today
    public double monthPnlBefore;     // realized this month before today
    public long lastExitMillis;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("entries", (double) entries);
        m.put("wins", (double) wins);
        m.put("losses", (double) losses);
        m.put("consecutiveLosses", (double) consecutiveLosses);
        m.put("realizedPnl", realizedPnl);
        m.put("weekPnlBefore", weekPnlBefore);
        m.put("monthPnlBefore", monthPnlBefore);
        m.put("lastExitMillis", (double) lastExitMillis);
        return m;
    }

    public static DayStats fromMap(Map<String, Object> m) {
        DayStats d = new DayStats();
        d.date = Json.str(m, "date", "");
        d.entries = (int) Json.num(m, "entries", 0);
        d.wins = (int) Json.num(m, "wins", 0);
        d.losses = (int) Json.num(m, "losses", 0);
        d.consecutiveLosses = (int) Json.num(m, "consecutiveLosses", 0);
        d.realizedPnl = Json.num(m, "realizedPnl", 0);
        d.weekPnlBefore = Json.num(m, "weekPnlBefore", 0);
        d.monthPnlBefore = Json.num(m, "monthPnlBefore", 0);
        d.lastExitMillis = (long) Json.num(m, "lastExitMillis", 0);
        return d;
    }
}
