package com.krish.niftydhanbot;

import android.content.Context;

import com.krish.niftydhanbot.core.Json;
import com.krish.niftydhanbot.core.TradeRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit files in the app's private storage (one JSON object per line):
 * trades.jsonl (every closed trade) and events-YYYY-MM-DD.jsonl (signals, orders, risk blocks, alerts).
 */
public final class Journal {
    private final File dir;

    public Journal(Context c) {
        dir = new File(c.getFilesDir(), "journal");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public synchronized void trade(TradeRecord t) { append(new File(dir, "trades.jsonl"), t.toMap()); }

    public synchronized void event(String date, String kind, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", System.currentTimeMillis());
        m.put("kind", kind);
        m.put("text", text);
        append(new File(dir, "events-" + date + ".jsonl"), m);
    }

    /** Most recent trades first. */
    public synchronized List<TradeRecord> trades(int max) {
        List<TradeRecord> all = new ArrayList<>();
        File f = new File(dir, "trades.jsonl");
        if (!f.exists()) return all;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { all.add(TradeRecord.fromMap(Json.obj(Json.parse(line)))); } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException ignored) {}
        List<TradeRecord> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) out.add(all.get(i));
        return out;
    }

    public File directory() { return dir; }

    private void append(File f, Map<String, Object> m) {
        try (FileOutputStream o = new FileOutputStream(f, true)) {
            o.write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Journal must never stop trading or exits.
        }
    }
}
