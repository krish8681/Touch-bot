package com.krish.niftydhanbot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** One finished trade, for the history screen and the journal file. */
public final class TradeRecord {
    public String label, side, exitReason, mode;
    public int qty;
    public double entry, exit, gross, charges, net;
    public long entryMillis, exitMillis;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("side", side);
        m.put("mode", mode);
        m.put("qty", (double) qty);
        m.put("entry", entry);
        m.put("exit", exit);
        m.put("gross", gross);
        m.put("charges", charges);
        m.put("net", net);
        m.put("entryMillis", (double) entryMillis);
        m.put("exitMillis", (double) exitMillis);
        m.put("exitReason", exitReason);
        return m;
    }

    public static TradeRecord fromMap(Map<String, Object> m) {
        TradeRecord t = new TradeRecord();
        t.label = Json.str(m, "label", "");
        t.side = Json.str(m, "side", "");
        t.mode = Json.str(m, "mode", "");
        t.qty = (int) Json.num(m, "qty", 0);
        t.entry = Json.num(m, "entry", 0);
        t.exit = Json.num(m, "exit", 0);
        t.gross = Json.num(m, "gross", 0);
        t.charges = Json.num(m, "charges", 0);
        t.net = Json.num(m, "net", 0);
        t.entryMillis = (long) Json.num(m, "entryMillis", 0);
        t.exitMillis = (long) Json.num(m, "exitMillis", 0);
        t.exitReason = Json.str(m, "exitReason", "");
        return t;
    }
}
