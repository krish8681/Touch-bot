package com.krish.niftyregimebot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** One finished strategy trade, for the history screen and the journal file. */
public final class TradeRecord {
    public String strategy = "", legs = "", regime = "", iv = "", exitReason = "", mode = "", holding = "";
    public int lots, qty;
    public double entryNet, exitNet;      // net premium per unit at entry / to close (+ credit, − debit)
    public double gross, charges, net, maxLossRs;
    public long entryMillis, exitMillis;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", strategy);
        m.put("legs", legs);
        m.put("regime", regime);
        m.put("iv", iv);
        m.put("mode", mode);
        m.put("holding", holding);
        m.put("lots", (double) lots);
        m.put("qty", (double) qty);
        m.put("entryNet", entryNet);
        m.put("exitNet", exitNet);
        m.put("gross", gross);
        m.put("charges", charges);
        m.put("net", net);
        m.put("maxLossRs", maxLossRs);
        m.put("entryMillis", (double) entryMillis);
        m.put("exitMillis", (double) exitMillis);
        m.put("exitReason", exitReason);
        return m;
    }

    public static TradeRecord fromMap(Map<String, Object> m) {
        TradeRecord t = new TradeRecord();
        t.strategy = Json.str(m, "strategy", "");
        t.legs = Json.str(m, "legs", "");
        t.regime = Json.str(m, "regime", "");
        t.iv = Json.str(m, "iv", "");
        t.mode = Json.str(m, "mode", "");
        t.holding = Json.str(m, "holding", "");
        t.lots = (int) Json.num(m, "lots", 0);
        t.qty = (int) Json.num(m, "qty", 0);
        t.entryNet = Json.num(m, "entryNet", 0);
        t.exitNet = Json.num(m, "exitNet", 0);
        t.gross = Json.num(m, "gross", 0);
        t.charges = Json.num(m, "charges", 0);
        t.net = Json.num(m, "net", 0);
        t.maxLossRs = Json.num(m, "maxLossRs", 0);
        t.entryMillis = (long) Json.num(m, "entryMillis", 0);
        t.exitMillis = (long) Json.num(m, "exitMillis", 0);
        t.exitReason = Json.str(m, "exitReason", "");
        return t;
    }
}
