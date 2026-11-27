package com.krish.niftyregimebot;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.TradeRecord;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.krish.niftyregimebot.Ui.AMBER;
import static com.krish.niftyregimebot.Ui.BG;
import static com.krish.niftyregimebot.Ui.BLUE;
import static com.krish.niftyregimebot.Ui.DIM;
import static com.krish.niftyregimebot.Ui.GREEN;
import static com.krish.niftyregimebot.Ui.PANEL;
import static com.krish.niftyregimebot.Ui.RED;
import static com.krish.niftyregimebot.Ui.TEXT;
import static com.krish.niftyregimebot.Ui.add;
import static com.krish.niftyregimebot.Ui.pnl;

/**
 * Dark terminal-style screen with six tabs: Home · Decisions · Trades · Log · Test · Settings.
 * Reads {@link BotHub} once a second; sends commands to {@link BotService}.
 */
public final class MainActivity extends Activity {
    private static final String[] TABS = {"Home", "Decisions", "Trades", "Log", "Test", "Settings"};
    private static final int TAB_HOME = 0, TAB_DECISIONS = 1, TAB_TRADES = 2, TAB_LOG = 3, TAB_TEST = 4, TAB_SETTINGS = 5;
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(MarketClock.IST);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Settings settings;
    private LinearLayout tabBar;
    private ScrollView scroll;
    private int tab;
    private TextView title, homeText, listText, testStatus, testText;
    private Button btnBot, btnTrade, btnAck, btnCancelTest;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        settings = new Settings(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        title = ui.text("", 15, AMBER, true);
        title.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(6));
        root.addView(title);

        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(PANEL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = ui.text(TABS[i], 12, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ui.dp(14), 0, ui.dp(14));
            t.setOnClickListener(v -> show(idx));
            tabBar.addView(t, Ui.weight());
        }
        root.addView(tabBar);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        Reminder.schedule(this);
        show(TAB_HOME);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresher); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1_000);
        }
    };

    private void show(int idx) {
        tab = idx;
        for (int i = 0; i < tabBar.getChildCount(); i++)
            ((TextView) tabBar.getChildAt(i)).setTextColor(i == idx ? GREEN : DIM);
        scroll.removeAllViews();
        homeText = listText = testText = null;
        switch (idx) {
            case TAB_HOME: scroll.addView(buildHome()); break;
            case TAB_TEST: scroll.addView(buildTest()); break;
            case TAB_SETTINGS: scroll.addView(new SettingsScreen(this, ui, settings, () -> show(TAB_SETTINGS)).build()); break;
            default:
                listText = ui.text("", 13, TEXT, false);
                listText.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(24));
                listText.setTextIsSelectable(true);
                scroll.addView(listText);
        }
        refresh();
    }

    private void refresh() {
        BotHub.Snapshot s = BotHub.get();
        boolean live = s.state == BotState.OFF ? settings.live() : s.live;
        String hold = s.state == BotState.OFF ? (settings.weekly() ? "WEEKLY" : "INTRADAY") : s.holding;
        title.setText(getString(R.string.app_name) + "  ·  " + (live ? "LIVE" : "PAPER") + "  ·  " + hold);
        title.setTextColor(live ? RED : AMBER);
        if (homeText != null) refreshHome(s);
        else if (testText != null) refreshTest();
        else if (listText != null) refreshList();
    }

    // ------------------------------------------------------------------ Home

    private LinearLayout buildHome() {
        LinearLayout col = ui.column();
        LinearLayout row1 = ui.row();
        btnBot = ui.button("Start bot", BLUE, v -> {
            if (BotHub.get().state == BotState.OFF) BotService.send(this, BotService.ACTION_START);
            else ui.confirm("Stop the bot?", BotHub.get().position != null
                    ? "A position is open. It is saved, but nobody watches it while the bot is stopped." : "Live data and the engine will stop.",
                    () -> BotService.send(this, BotService.ACTION_STOP));
        });
        btnTrade = ui.button("Start trading", GREEN, v ->
                BotService.send(this, BotHub.get().state == BotState.TRADING ? BotService.ACTION_PAUSE : BotService.ACTION_TRADE));
        row1.addView(btnBot, Ui.weight());
        row1.addView(btnTrade, Ui.weight());
        col.addView(row1);
        col.addView(ui.button("EMERGENCY EXIT — close all legs now", RED, v ->
                ui.confirm("Emergency exit?", "Close every leg of the open position now (short legs first) and stop new entries.",
                        () -> BotService.send(this, BotService.ACTION_EMERGENCY))));
        btnAck = ui.button("I checked my Dhan account — continue", AMBER, v -> BotService.send(this, BotService.ACTION_ACK));
        col.addView(btnAck);
        homeText = ui.text("", 14, TEXT, false);
        homeText.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(24));
        col.addView(homeText);
        return col;
    }

    private void refreshHome(BotHub.Snapshot s) {
        btnBot.setText(s.state == BotState.OFF ? "Start bot" : "Stop bot");
        btnTrade.setText(s.state == BotState.TRADING ? "Pause entries" : "Start trading");
        btnTrade.setEnabled(s.state == BotState.READY || s.state == BotState.PAUSED || s.state == BotState.TRADING);
        btnTrade.setAlpha(btnTrade.isEnabled() ? 1f : 0.35f);
        btnAck.setVisibility(s.state == BotState.RECOVERY || s.state == BotState.ERROR ? View.VISIBLE : View.GONE);

        long now = System.currentTimeMillis();
        SpannableStringBuilder t = new SpannableStringBuilder();
        status(t, s);
        market(t, s, now);
        if (s.state != BotState.OFF && !s.checks.isEmpty()) {
            add(t, "\nSELF-TEST\n", DIM);
            for (String ck : s.checks) add(t, ck + "\n", ck.startsWith("✓") ? GREEN : ck.startsWith("✗") ? RED : DIM);
        }
        reading(t, s);
        position(t, s, now);
        today(t, s);
        homeText.setText(t);
    }

    private static void status(SpannableStringBuilder t, BotHub.Snapshot s) {
        add(t, "STATUS  ", DIM);
        add(t, s.state + "\n", stateColor(s.state));
        add(t, explain(s.state, s.live) + "\n", DIM);
        if (!s.lastError.isEmpty() && s.state == BotState.ERROR) add(t, s.lastError + "\n", RED);
        if (s.state != BotState.OFF) for (String w : s.warnings) add(t, "⚠ " + w + "\n", w.contains("expired") ? RED : AMBER);
    }

    private static void market(SpannableStringBuilder t, BotHub.Snapshot s, long now) {
        add(t, "\nMARKET\n", DIM);
        long age = s.spotMillis == 0 ? -1 : (now - s.spotMillis) / 1000;
        add(t, String.format(Locale.US, "NIFTY  %.2f", s.spot), TEXT);
        add(t, age < 0 ? "  (no price yet)\n" : "  (" + age + "s ago)\n", age >= 0 && age <= 5 ? GREEN : AMBER);
        add(t, "Feed   ", DIM);
        add(t, (s.feedConnected ? "live" : s.feedNote) + "\n", s.feedConnected ? GREEN : AMBER);
        add(t, "Expiry " + (s.expiry.isEmpty() ? "-" : s.expiry) + "   contracts live " + s.contracts + "\n", DIM);
        if (s.dhanFunds >= 0) add(t, "Dhan funds ₹" + BotService.money(s.dhanFunds) + "\n", DIM);
        if (s.state == BotState.OFF) return;
        if (s.tokenAgeHours >= 0) add(t, String.format(Locale.US, "Token age %.1f h%s\n", s.tokenAgeHours,
                s.autoRenew ? " (auto-renews after 20 h)" : " (expires at 24 h)"), s.tokenAgeHours >= 20 ? AMBER : DIM);
        add(t, "Bars fixed from Dhan data: " + s.barsFixed + (s.waitingForDhanBar ? "   (checking latest bar…)" : "") + "\n", DIM);
    }

    private static void reading(SpannableStringBuilder t, BotHub.Snapshot s) {
        add(t, "\nBOT READING\n", DIM);
        add(t, "Trend   ", DIM); add(t, s.regime + "\n", regimeColor(s.regime));
        if (!s.regimeWhy.isEmpty()) add(t, "  " + s.regimeWhy + "\n", DIM);
        add(t, "IV      ", DIM); add(t, s.ivText + "\n", TEXT);
        add(t, "Pick    ", DIM); add(t, s.pick + "\n", BLUE);
        if (!s.lastAction.isEmpty()) add(t, "  " + s.lastAction + "\n", DIM);
    }

    private static void position(SpannableStringBuilder t, BotHub.Snapshot s, long now) {
        add(t, "\nPOSITION\n", DIM);
        if (!s.job.isEmpty()) add(t, "Orders working: " + s.job + "\n", AMBER);
        BotHub.PositionView p = s.position;
        if (p == null) { add(t, "None\n", TEXT); return; }
        add(t, p.title + "  " + p.lots + " lots (" + p.qty + " qty)  " + p.holding + "\n", TEXT);
        add(t, "Expiry " + p.expiry + "   " + (p.netPremium >= 0 ? "credit " : "debit ")
                + String.format(Locale.US, "%.2f", Math.abs(p.netPremium)) + " per unit\n", DIM);
        for (BotHub.LegView l : p.legs) {
            add(t, l.buy ? "  BUY  " : "  SELL ", l.buy ? GREEN : RED);
            add(t, String.format(Locale.US, "%-16s %.2f → %.2f\n", l.label, l.entry, l.now), TEXT);
        }
        add(t, "Open P&L  ", TEXT);
        add(t, "₹" + BotService.money(p.openPnl) + (p.stale ? "  (prices old)" : "") + "  if closed now\n", pnl(p.openPnl));
        add(t, "Fair value ", DIM);
        add(t, "₹" + BotService.money(p.fairPnl) + "  (stop uses this)\n", pnl(p.fairPnl));
        add(t, "Target ", DIM); add(t, "₹" + BotService.money(p.target), GREEN);
        add(t, "   Stop ", DIM); add(t, "₹" + BotService.money(p.stop) + "\n", RED);
        add(t, "Max profit ₹" + BotService.money(p.maxProfit) + "   max loss ₹" + BotService.money(p.maxLoss) + "\n", DIM);
        add(t, "Held " + (now - p.entryMillis) / 60_000 + " min\n", DIM);
    }

    private void today(SpannableStringBuilder t, BotHub.Snapshot s) {
        DayStats d = s.day;
        Config c = settings.config();
        double dayTotal = d.realizedPnl + (s.position == null ? 0 : s.position.openPnl);
        add(t, "\nTODAY\n", DIM);
        add(t, "P&L  ", TEXT); add(t, "₹" + BotService.money(dayTotal) + "\n", pnl(dayTotal));
        add(t, String.format(Locale.US, "Trades %d/%d   wins %d   losses %d   in a row %d/%d\n",
                d.entries, c.maxTradesPerDay, d.wins, d.losses, d.consecutiveLosses, c.maxConsecutiveLosses), TEXT);
        double used = Math.max(0, -dayTotal) / c.dailyLossLimit * 100;
        add(t, String.format(Locale.US, "Daily loss limit used %.0f%% of ₹%s\n", used, BotService.money(c.dailyLossLimit)), used >= 75 ? RED : DIM);
    }

    // ------------------------------------------------------------------ Decisions / Trades / Log

    private void refreshList() {
        SpannableStringBuilder t = new SpannableStringBuilder();
        if (tab == TAB_DECISIONS) {
            List<String> dec = BotHub.decisions();
            if (dec.isEmpty()) add(t, "No decisions yet. One appears each time a bar closes ("
                    + settings.config().barMinutes() + " min bars).\n", DIM);
            for (String l : dec) add(t, l + "\n\n", l.contains("ENTER") || l.contains("Entered") ? GREEN : TEXT);
        } else if (tab == TAB_TRADES) {
            List<TradeRecord> tr = new Journal(this).trades(200);
            double total = 0;
            int wins = 0;
            for (TradeRecord r : tr) { total += r.net; if (r.net >= 0) wins++; }
            add(t, String.format(Locale.US, "%d trades   wins %d   net ", tr.size(), wins), TEXT);
            add(t, "₹" + BotService.money(total) + "\n", pnl(total));
            add(t, "Net = after estimated charges\n\n", DIM);
            if (tr.isEmpty()) add(t, "No trades yet.\n", DIM);
            for (TradeRecord r : tr) {
                add(t, DHM.format(Instant.ofEpochMilli(r.exitMillis)) + "  " + r.mode + "  " + r.holding + "\n", DIM);
                add(t, r.strategy + "  " + r.lots + " lots\n", TEXT);
                add(t, "  " + r.legs + "\n", DIM);
                add(t, "  " + r.regime + " / " + r.iv + "  exit " + r.exitReason + "\n", DIM);
                add(t, String.format(Locale.US, "  gross ₹%s  charges ₹%.0f  net ", BotService.money(r.gross), r.charges), DIM);
                add(t, "₹" + BotService.money(r.net) + "\n\n", pnl(r.net));
            }
        } else if (tab == TAB_LOG) {
            List<String> logs = BotHub.logs();
            if (logs.isEmpty()) add(t, "Nothing yet. Start the bot on Home.\n", DIM);
            for (String l : logs) add(t, l + "\n", l.contains("ERROR") ? RED : l.contains("WARN") || l.contains("⚠") ? AMBER : TEXT);
        }
        listText.setText(t);
    }

    // ------------------------------------------------------------------ Test (backtest)

    private LinearLayout buildTest() {
        LinearLayout col = ui.column();
        ui.note(col, "Backtest: runs the bot's own rules over past NIFTY and option prices from Dhan, "
                + "with your current Settings (holding mode, risk, targets, filters) and charges. No orders are placed. "
                + "Takes about 1 minute per 30 days.");
        LinearLayout row = ui.row();
        for (int d : new int[]{30, 60, 90}) {
            final int days = d;
            row.addView(ui.button(d + " days", GREEN, v -> { if (!BacktestRunner.running()) BacktestRunner.start(this, days); }), Ui.weight());
        }
        col.addView(row);
        btnCancelTest = ui.button("Cancel test", RED, v -> BacktestRunner.cancel());
        col.addView(btnCancelTest);
        testStatus = ui.text("", 13, AMBER, true);
        testStatus.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4));
        col.addView(testStatus);
        testText = ui.text("", 12, TEXT, false);
        testText.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(24));
        testText.setTextIsSelectable(true);
        col.addView(testText);
        return col;
    }

    private void refreshTest() {
        boolean run = BacktestRunner.running();
        btnCancelTest.setVisibility(run ? View.VISIBLE : View.GONE);
        String st = BacktestRunner.status();
        testStatus.setText(run ? "⏳ " + st : st);
        String r = BacktestRunner.result(this);
        SpannableStringBuilder t = new SpannableStringBuilder();
        if (r.isEmpty()) add(t, "No test run yet. Tap 30, 60 or 90 days.\n", DIM);
        for (String line : r.split("\n")) {
            int color = TEXT;
            if (line.matches("^[A-Z][A-Z ()]+$")) color = AMBER;
            else if (line.contains("net ₹-") || line.matches(".*  -[0-9,]+$") || line.startsWith("Net ₹-")) color = RED;
            else if (line.contains("net ₹") || line.matches(".*  [0-9,]+$") || line.startsWith("Net ₹")) color = GREEN;
            else if (line.startsWith("  •")) color = DIM;
            add(t, line + "\n", color);
        }
        testText.setText(t);
    }

    // ------------------------------------------------------------------ colours and words

    private static String explain(BotState s, boolean live) {
        switch (s) {
            case OFF: return "Bot is stopped.";
            case STARTING: return "Connecting to Dhan and loading data…";
            case READY: return "Connected. Tap Start trading to allow " + (live ? "LIVE" : "paper") + " entries.";
            case TRADING: return "Watching for trades. Targets, stops and time exits are active.";
            case PAUSED: return "No new entries. Open position is still managed.";
            case RECOVERY: return "Found something unexpected in your Dhan account. Entries blocked.";
            case ERROR: return "Something needs your attention. Entries blocked.";
            default: return "";
        }
    }

    private static int stateColor(BotState s) {
        switch (s) {
            case TRADING: return GREEN;
            case READY: return BLUE;
            case PAUSED: case STARTING: case RECOVERY: return AMBER;
            case ERROR: return RED;
            default: return DIM;
        }
    }

    private static int regimeColor(String r) {
        if ("UP".equals(r)) return GREEN;
        if ("DOWN".equals(r)) return RED;
        if ("SIDEWAYS".equals(r)) return AMBER;
        return DIM;
    }
}
