package com.krish.niftyregimebot;

import android.content.Context;

import com.krish.niftyregimebot.core.Backtester;
import com.krish.niftyregimebot.core.Candle;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.TradeRecord;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * Downloads past data from Dhan and runs {@link Backtester} on a background thread.
 * Uses the current Settings (risk, holding mode, strategy rules). Never places orders.
 * Works whether or not the bot is running.
 */
public final class BacktestRunner {
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(MarketClock.IST);

    private static volatile Thread worker;
    private static volatile String status = "";
    private static volatile String result = "";

    public static boolean running() { Thread w = worker; return w != null && w.isAlive(); }
    public static String status() { return status; }
    public static String result(Context c) { return result.isEmpty() ? new Settings(c).lastBacktest() : result; }

    public static void cancel() {
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    public static void start(Context ctx, int days) {
        if (running()) return;
        Context app = ctx.getApplicationContext();
        worker = new Thread(() -> {
            try {
                String text = run(app, days);
                result = text;
                new Settings(app).saveLastBacktest(text);
                status = "Done";
            } catch (InterruptedException e) {
                status = "Cancelled";
            } catch (DhanApi.DhanException e) {
                status = e.code == 401 || e.code == 403 ? "Dhan rejected the token — paste a fresh one in Settings" : "Dhan error: " + e.getMessage();
            } catch (Exception e) {
                status = "Failed: " + e;
            }
        }, "backtest");
        worker.start();
    }

    private static void say(String s) throws InterruptedException {
        status = s;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static void pause() throws InterruptedException { Thread.sleep(260); }  // Dhan data APIs: ≤ 5 calls a second

    private static String run(Context c, int days) throws Exception {
        Settings s = new Settings(c);
        if (s.clientId().isEmpty() || s.token().isEmpty()) throw new IOException("add your Dhan client ID and token in Settings first");
        MarketClock.setExtraHolidays(s.extraHolidayList());
        Config cfg = s.config();
        DhanApi api = new DhanApi(new OkHttpClient(), s.clientId(), s.token());

        LocalDate today = LocalDate.now(MarketClock.IST);
        LocalDate to = today;                        // exclusive for option data; today's partial day is skipped
        LocalDate from = today.minusDays(days);
        Backtester.Data data = new Backtester.Data();

        say("Loading NIFTY bars…");
        int warm = cfg.holding == Config.Holding.WEEKLY ? 45 : 7;
        for (Candle k : api.niftyBars(cfg.barMinutes(), from.minusDays(warm), from.minusDays(1))) data.warmup.add(k);
        pause();
        LocalDate a = from;
        while (a.isBefore(to)) {
            LocalDate b = a.plusDays(85).isBefore(to) ? a.plusDays(85) : to.minusDays(1);
            for (Candle k : api.niftyBars(5, a, b)) if (MarketClock.date(k.startMillis).isBefore(today)) data.spot5.add(k);
            pause();
            a = b.plusDays(1);
        }
        say("Loading India VIX…");
        try {
            data.vixCloses.putAll(api.indexDailySeries(s.vixId(), from.minusDays(400), today));
        } catch (DhanApi.DhanException e) {
            if (e.code == 401 || e.code == 403) throw e;
            data.notes.add("India VIX history not available (ID " + s.vixId() + "): IV level uses ATM IV");
        }
        pause();

        int chunks = (int) Math.ceil(days / 30.0), chunk = 0;
        boolean nextWide = true;
        for (LocalDate x = from; x.isBefore(to); x = x.plusDays(30)) {
            chunk++;
            LocalDate y = x.plusDays(30).isBefore(to) ? x.plusDays(30) : to;
            for (int code = 1; code <= 2; code++) {
                for (int off = -10; off <= 10; off++) {
                    if (code == 2 && !nextWide && Math.abs(off) > 3) continue;
                    for (int side = 0; side < 2; side++) {
                        say(String.format(Locale.US, "Option data %d/%d: %s expiry, %s, %s", chunk, chunks,
                                code == 1 ? "near" : "next", off == 0 ? "ATM" : (off > 0 ? "ATM+" + off : "ATM" + off), side == 0 ? "CE" : "PE"));
                        try {
                            data.options.addAll(api.rollingOption(code, off, side == 0, x, y));
                        } catch (DhanApi.DhanException e) {
                            if (e.code == 401 || e.code == 403) throw e;
                            if (code == 2 && Math.abs(off) > 3) {
                                nextWide = false;
                                data.notes.add("Dhan gives next-week options only for ATM±3 strikes, so wide next-week trades can't be tested");
                            } else {
                                data.notes.add("Missing option data " + x + " code " + code + " offset " + off + ": HTTP " + e.code);
                            }
                        }
                        pause();
                    }
                }
            }
        }
        if (data.options.isEmpty()) throw new IOException("Dhan returned no option history");

        say("Running the bot's rules over " + data.spot5.size() + " bars…");
        Backtester bt = new Backtester();
        Backtester.Report r = bt.run(cfg, data, msg -> status = msg);
        return format(r);
    }

    private static String format(Backtester.Report r) {
        StringBuilder b = new StringBuilder();
        b.append(r.text());
        b.append("\nBY STRATEGY\n");
        if (r.byStrategy.isEmpty()) b.append("  no trades\n");
        for (Map.Entry<String, Backtester.StrategyStats> e : r.byStrategy.entrySet()) {
            Backtester.StrategyStats st = e.getValue();
            b.append(String.format(Locale.US, "  %-20s %3d trades  win %3.0f%%  net ₹%,.0f\n", e.getKey(), st.trades,
                    st.trades == 0 ? 0 : 100.0 * st.wins / st.trades, st.net));
        }
        b.append("\nHOW TRADES ENDED\n");
        for (Map.Entry<String, Integer> e : r.byExit.entrySet()) b.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        b.append("\nWHY TRADES WERE SKIPPED (top)\n");
        List<Map.Entry<String, Integer>> skips = new ArrayList<>(r.skipReasons.entrySet());
        skips.sort((p, q) -> q.getValue() - p.getValue());
        for (int i = 0; i < Math.min(6, skips.size()); i++) b.append("  ").append(skips.get(i).getValue()).append("× ").append(skips.get(i).getKey()).append('\n');
        b.append("\nDAY BY DAY (net ₹)\n");
        for (Map.Entry<LocalDate, Double> e : r.dailyNet.entrySet())
            if (Math.abs(e.getValue()) > 0.5) b.append(String.format(Locale.US, "  %s  %,.0f\n", e.getKey(), e.getValue()));
        b.append("\nLAST TRADES\n");
        List<TradeRecord> ts = r.trades;
        for (int i = Math.max(0, ts.size() - 15); i < ts.size(); i++) {
            TradeRecord t = ts.get(i);
            b.append(String.format(Locale.US, "  %s %s %d lot · %s · net ₹%,.0f\n", DHM.format(Instant.ofEpochMilli(t.entryMillis)),
                    t.strategy, t.lots, t.exitReason, t.net));
        }
        b.append("\nNOTES\n");
        for (String n : r.notes) b.append("  • ").append(n).append('\n');
        b.append("  • Past results do not promise future results\n");
        return b.toString();
    }
}
