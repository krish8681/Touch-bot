package com.krish.niftyregimebot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.TradeRecord;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Dark terminal-style screen. Reads {@link BotHub}; sends commands to {@link BotService}. */
public final class MainActivity extends Activity {
    static final int BG = Color.parseColor("#0A0F14"), PANEL = Color.parseColor("#121A22"),
            TEXT = Color.parseColor("#E6EDF3"), DIM = Color.parseColor("#7D8B99"),
            GREEN = Color.parseColor("#00E676"), RED = Color.parseColor("#FF1744"),
            AMBER = Color.parseColor("#FFC400"), BLUE = Color.parseColor("#40C4FF");
    private static final String[] TABS = {"Home", "Decisions", "Trades", "Log", "Test", "Settings"};
    private static final int TAB_TEST = 4, TAB_SETTINGS = 5;
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(MarketClock.IST);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout tabBar;
    private ScrollView scroll;
    private int tab;
    private TextView title, homeText, listText;
    private Button btnBot, btnTrade, btnAck;
    private Settings settings;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = new Settings(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        title = text("", 15, AMBER, true);
        title.setPadding(dp(14), dp(12), dp(14), dp(6));
        root.addView(title);

        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(PANEL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 12, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(14), 0, dp(14));
            t.setOnClickListener(v -> show(idx));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        Reminder.schedule(this);
        show(0);
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresher); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1_000);
        }
    };

    private void show(int idx) {
        tab = idx;
        for (int i = 0; i < tabBar.getChildCount(); i++)
            ((TextView) tabBar.getChildAt(i)).setTextColor(i == idx ? GREEN : DIM);
        scroll.removeAllViews();
        homeText = null;
        listText = null;
        switch (idx) {
            case 0: buildHome(); break;
            case TAB_TEST: buildTest(); break;
            case TAB_SETTINGS: buildSettings(); break;
            default:
                listText = text("", 13, TEXT, false);
                listText.setPadding(dp(14), dp(8), dp(14), dp(24));
                listText.setTextIsSelectable(true);
                scroll.addView(listText);
        }
        refresh();
    }

    // ------------------------------------------------------------------ Home

    private void buildHome() {
        LinearLayout col = column();
        LinearLayout row1 = row();
        btnBot = button("Start bot", BLUE, v -> {
            if (BotHub.get().state == BotState.OFF) BotService.send(this, BotService.ACTION_START);
            else confirm("Stop the bot?", BotHub.get().position != null
                    ? "A position is open. It is saved, but nobody watches it while the bot is stopped." : "Live data and the engine will stop.",
                    () -> BotService.send(this, BotService.ACTION_STOP));
        });
        btnTrade = button("Start trading", GREEN, v -> {
            BotState s = BotHub.get().state;
            BotService.send(this, s == BotState.TRADING ? BotService.ACTION_PAUSE : BotService.ACTION_TRADE);
        });
        row1.addView(btnBot, weight());
        row1.addView(btnTrade, weight());
        col.addView(row1);

        col.addView(button("EMERGENCY EXIT — close all legs now", RED, v ->
                confirm("Emergency exit?", "Close every leg of the open position now (short legs first) and stop new entries.",
                        () -> BotService.send(this, BotService.ACTION_EMERGENCY))));

        btnAck = button("I checked my Dhan account — continue", AMBER, v -> BotService.send(this, BotService.ACTION_ACK));
        col.addView(btnAck);

        homeText = text("", 14, TEXT, false);
        homeText.setPadding(dp(4), dp(12), dp(4), dp(24));
        col.addView(homeText);
        scroll.addView(col);
    }

    private void refreshHome() {
        BotHub.Snapshot s = BotHub.get();
        long now = System.currentTimeMillis();
        btnBot.setText(s.state == BotState.OFF ? "Start bot" : "Stop bot");
        btnTrade.setText(s.state == BotState.TRADING ? "Pause entries" : "Start trading");
        btnTrade.setEnabled(s.state == BotState.READY || s.state == BotState.PAUSED || s.state == BotState.TRADING);
        btnTrade.setAlpha(btnTrade.isEnabled() ? 1f : 0.35f);
        btnAck.setVisibility(s.state == BotState.RECOVERY || s.state == BotState.ERROR ? View.VISIBLE : View.GONE);

        SpannableStringBuilder t = new SpannableStringBuilder();
        add(t, "STATUS  ", DIM);
        add(t, s.state + "\n", stateColor(s.state));
        add(t, explain(s.state, s.live) + "\n", DIM);
        if (!s.lastError.isEmpty() && s.state == BotState.ERROR) add(t, s.lastError + "\n", RED);
        if (s.state != BotState.OFF) for (String w : s.warnings) add(t, "⚠ " + w + "\n", w.contains("expired") ? RED : AMBER);

        add(t, "\nMARKET\n", DIM);
        long age = s.spotMillis == 0 ? -1 : (now - s.spotMillis) / 1000;
        add(t, String.format(Locale.US, "NIFTY  %.2f", s.spot), TEXT);
        add(t, age < 0 ? "  (no price yet)\n" : "  (" + age + "s ago)\n", age >= 0 && age <= 5 ? GREEN : AMBER);
        add(t, "Feed   ", DIM); add(t, (s.feedConnected ? "live" : s.feedNote) + "\n", s.feedConnected ? GREEN : AMBER);
        add(t, "Expiry " + (s.expiry.isEmpty() ? "-" : s.expiry) + "   contracts live " + s.contracts + "\n", DIM);
        if (s.dhanFunds >= 0) add(t, "Dhan funds ₹" + BotService.money(s.dhanFunds) + "\n", DIM);
        if (s.state != BotState.OFF) {
            if (s.tokenAgeHours >= 0) add(t, String.format(Locale.US, "Token age %.1f h%s\n", s.tokenAgeHours,
                    s.autoRenew ? " (auto-renews after 20 h)" : " (expires at 24 h)"), s.tokenAgeHours >= 20 ? AMBER : DIM);
            add(t, "Bars fixed from Dhan data: " + s.barsFixed + (s.waitingForDhanBar ? "   (checking latest bar…)" : "") + "\n", DIM);
        }

        add(t, "\nBOT READING\n", DIM);
        add(t, "Trend   ", DIM); add(t, s.regime + "\n", regimeColor(s.regime));
        if (!s.regimeWhy.isEmpty()) add(t, "  " + s.regimeWhy + "\n", DIM);
        add(t, "IV      ", DIM); add(t, s.ivText + "\n", TEXT);
        add(t, "Pick    ", DIM); add(t, s.pick + "\n", BLUE);
        if (!s.lastAction.isEmpty()) add(t, "  " + s.lastAction + "\n", DIM);

        add(t, "\nPOSITION\n", DIM);
        if (!s.job.isEmpty()) add(t, "Orders working: " + s.job + "\n", AMBER);
        if (s.position == null) add(t, "None\n", TEXT);
        else {
            BotHub.PositionView p = s.position;
            add(t, p.title + "  " + p.lots + " lots (" + p.qty + " qty)  " + p.holding + "\n", TEXT);
            add(t, "Expiry " + p.expiry + "   " + (p.netPremium >= 0 ? "credit " : "debit ")
                    + String.format(Locale.US, "%.2f", Math.abs(p.netPremium)) + " per unit\n", DIM);
            for (BotHub.LegView l : p.legs) {
                add(t, (l.buy ? "  BUY  " : "  SELL ") , l.buy ? GREEN : RED);
                add(t, String.format(Locale.US, "%-16s %.2f → %.2f\n", l.label, l.entry, l.now), TEXT);
            }
            add(t, "Open P&L  ", TEXT);
            add(t, "₹" + BotService.money(p.openPnl) + (p.stale ? "  (prices old)" : "") + "  if closed now\n", p.openPnl >= 0 ? GREEN : RED);
            add(t, "Fair value ", DIM);
            add(t, "₹" + BotService.money(p.fairPnl) + "  (stop uses this)\n", p.fairPnl >= 0 ? GREEN : RED);
            add(t, "Target ", DIM); add(t, "₹" + BotService.money(p.target), GREEN);
            add(t, "   Stop ", DIM); add(t, "₹" + BotService.money(p.stop) + "\n", RED);
            add(t, "Max profit ₹" + BotService.money(p.maxProfit) + "   max loss ₹" + BotService.money(p.maxLoss) + "\n", DIM);
            add(t, "Held " + (now - p.entryMillis) / 60_000 + " min\n", DIM);
        }

        DayStats d = s.day;
        Config c = settings.config();
        double dayTotal = d.realizedPnl + (s.position == null ? 0 : s.position.openPnl);
        add(t, "\nTODAY\n", DIM);
        add(t, "P&L  ", TEXT); add(t, "₹" + BotService.money(dayTotal) + "\n", dayTotal >= 0 ? GREEN : RED);
        add(t, String.format(Locale.US, "Trades %d/%d   wins %d   losses %d   in a row %d/%d\n",
                d.entries, c.maxTradesPerDay, d.wins, d.losses, d.consecutiveLosses, c.maxConsecutiveLosses), TEXT);
        double used = Math.max(0, -dayTotal) / c.dailyLossLimit * 100;
        add(t, String.format(Locale.US, "Daily loss limit used %.0f%% of ₹%s\n", used, BotService.money(c.dailyLossLimit)), used >= 75 ? RED : DIM);
        homeText.setText(t);
    }

    // ------------------------------------------------------------------ Decisions / Trades / Log

    private void refreshList() {
        SpannableStringBuilder t = new SpannableStringBuilder();
        if (tab == 1) {
            List<String> dec = BotHub.decisions();
            if (dec.isEmpty()) add(t, "No decisions yet. One appears each time a bar closes ("
                    + settings.config().barMinutes() + " min bars).\n", DIM);
            for (String l : dec) add(t, l + "\n\n", l.contains("ENTER") || l.contains("Entered") ? GREEN : TEXT);
        } else if (tab == 2) {
            List<TradeRecord> tr = new Journal(this).trades(200);
            double total = 0;
            int wins = 0;
            for (TradeRecord r : tr) { total += r.net; if (r.net >= 0) wins++; }
            add(t, String.format(Locale.US, "%d trades   wins %d   net ", tr.size(), wins), TEXT);
            add(t, "₹" + BotService.money(total) + "\n", total >= 0 ? GREEN : RED);
            add(t, "Net = after estimated charges\n\n", DIM);
            if (tr.isEmpty()) add(t, "No trades yet.\n", DIM);
            for (TradeRecord r : tr) {
                add(t, DHM.format(Instant.ofEpochMilli(r.exitMillis)) + "  " + r.mode + "  " + r.holding + "\n", DIM);
                add(t, r.strategy + "  " + r.lots + " lots\n", TEXT);
                add(t, "  " + r.legs + "\n", DIM);
                add(t, "  " + r.regime + " / " + r.iv + "  exit " + r.exitReason + "\n", DIM);
                add(t, String.format(Locale.US, "  gross ₹%s  charges ₹%.0f  net ", BotService.money(r.gross), r.charges), DIM);
                add(t, "₹" + BotService.money(r.net) + "\n\n", r.net >= 0 ? GREEN : RED);
            }
        } else if (tab == 3) {
            List<String> logs = BotHub.logs();
            if (logs.isEmpty()) add(t, "Nothing yet. Start the bot on Home.\n", DIM);
            for (String l : logs) add(t, l + "\n", l.contains("ERROR") ? RED : l.contains("WARN") || l.contains("⚠") ? AMBER : TEXT);
        }
        listText.setText(t);
    }

    // ------------------------------------------------------------------ Test (backtest)

    private TextView testStatus, testText;
    private Button btnCancelTest;

    private void buildTest() {
        LinearLayout col = column();
        col.addView(text("Backtest: runs the bot's own rules over past NIFTY and option prices from Dhan, "
                + "with your current Settings (holding mode, risk, targets) and charges. No orders are placed. "
                + "Takes about 1 minute per 30 days.", 12, DIM, false));
        LinearLayout row = row();
        for (int d : new int[]{30, 60, 90}) {
            final int days = d;
            row.addView(button(d + " days", GREEN, v -> {
                if (BacktestRunner.running()) return;
                BacktestRunner.start(this, days);
            }), weight());
        }
        col.addView(row);
        btnCancelTest = button("Cancel test", RED, v -> BacktestRunner.cancel());
        col.addView(btnCancelTest);
        testStatus = text("", 13, AMBER, true);
        testStatus.setPadding(dp(4), dp(8), dp(4), dp(4));
        col.addView(testStatus);
        testText = text("", 12, TEXT, false);
        testText.setPadding(dp(4), dp(4), dp(4), dp(24));
        testText.setTextIsSelectable(true);
        col.addView(testText);
        scroll.addView(col);
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

    // ------------------------------------------------------------------ Settings

    private void buildSettings() {
        LinearLayout col = column();
        Config c = settings.config();

        section(col, "Dhan login");
        EditText client = field(col, "Client ID", settings.clientId(), false);
        EditText token = field(col, "Access token (leave empty to keep the saved one)", "", true);
        col.addView(text(settings.token().isEmpty() ? "No token saved" : "Token saved (hidden). Dhan tokens last about 24 hours.", 12, DIM, false));

        CheckBox autoRenew = check(col, "Auto-renew the Dhan token before it expires (works for tokens made on Dhan Web)", settings.autoRenewToken());

        section(col, "How long to hold");
        CheckBox weekly = check(col, "WEEKLY: hold for days (exit the day before expiry). Off = INTRADAY: close by 15:15", settings.weekly());

        section(col, "Money and risk");
        EditText capital = field(col, "Capital ₹ (paper mode uses this as funds)", num(c.capital), false);
        EditText risk = field(col, "Max loss per trade ₹ (sizes the lots)", num(c.riskPerTrade), false);
        EditText daily = field(col, "Daily loss limit ₹", num(c.dailyLossLimit), false);
        EditText trades = field(col, "Max trades per day", num(c.maxTradesPerDay), false);
        EditText consec = field(col, "Stop after this many losses in a row", num(c.maxConsecutiveLosses), false);

        section(col, "Strategy");
        EditText width = field(col, "Spread width (points between strikes)", num(c.spreadWidth), false);
        EditText tCredit = field(col, "Take profit at % of max profit (credit spreads)", num(c.targetPctCredit * 100), false);
        EditText tDebit = field(col, "Take profit at % of max profit (debit spreads)", num(c.targetPctDebit * 100), false);
        EditText stop = field(col, "Stop loss at % of max loss", num(c.stopPctOfMaxLoss * 100), false);
        EditText minX = field(col, "Skip a trade if its profit target is less than this × its charges", num(c.minTargetToCharges), false);
        CheckBox fly = check(col, "INTRADAY sideways: trade a long call butterfly (off = no trade; credit trades are WEEKLY only)", c.intradayButterfly);

        section(col, "Contract");
        EditText lot = field(col, "NIFTY lot size (check NSE's current value)", num(c.lotSize), false);
        EditText maxLots = field(col, "Max lots per trade", num(c.maxLots), false);
        CheckBox lotOk = check(col, "I checked this lot size is correct", settings.lotSizeConfirmed());
        EditText vix = field(col, "India VIX security ID on Dhan (IDX_I)", settings.vixId(), false);

        section(col, "NSE holidays");
        col.addView(text("Built in: all 2026 NSE holidays. Add next year's dates when NSE publishes them (usually in December).", 12, DIM, false));
        EditText holidays = field(col, "Extra holidays (yyyy-MM-dd, comma separated)", settings.extraHolidays(), false);

        section(col, "Telegram alerts (optional)");
        EditText tgToken = field(col, "Bot token (leave empty to keep the saved one)", "", true);
        EditText tgChat = field(col, "Chat ID", settings.telegramChat(), false);

        col.addView(button("Save settings", GREEN, v -> {
            try {
                put("capital", capital, 10_000, 1e8);
                put("risk_per_trade", risk, 500, 200_000);
                put("daily_loss", daily, 500, 1e6);
                put("max_trades", trades, 1, 10);
                put("max_consec_losses", consec, 1, 10);
                put("spread_width", width, 50, 1000);
                put("target_credit_pct", tCredit, 10, 90);
                put("target_debit_pct", tDebit, 10, 100);
                put("stop_pct", stop, 10, 100);
                put("min_target_x_charges", minX, 0, 20);
                settings.putBool("intraday_butterfly", fly.isChecked());
                put("lot_size", lot, 1, 5000);
                put("max_lots", maxLots, 1, 100);
                String hol = holidays.getText().toString().trim();
                for (String h : hol.split("[,\\s]+")) {
                    if (h.isEmpty()) continue;
                    try { java.time.LocalDate.parse(h); } catch (RuntimeException ex) { throw new IllegalArgumentException("Holiday not a date (use yyyy-MM-dd): " + h); }
                }
                boolean newToken = !token.getText().toString().trim().isEmpty();
                settings.saveExtraHolidays(hol);
                settings.putBool("auto_renew_token", autoRenew.isChecked());
                settings.saveCredentials(client.getText().toString(), token.getText().toString());
                settings.saveTelegram(tgToken.getText().toString(), tgChat.getText().toString());
                settings.saveVixId(vix.getText().toString().isEmpty() ? "21" : vix.getText().toString());
                settings.putBool("weekly", weekly.isChecked());
                settings.putBool("lot_size_confirmed", lotOk.isChecked());
                if (newToken && BotHub.get().state != BotState.OFF) {
                    BotService.send(this, BotService.ACTION_RELOAD);
                    Toast.makeText(this, "Saved. Reconnecting with the new token now. Other changes apply after a bot restart.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Saved. Stop and start the bot to use new settings.", Toast.LENGTH_LONG).show();
                }
                show(TAB_SETTINGS);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));

        section(col, "Live orders");
        boolean live = settings.live();
        TextView liveState = text(live ? "LIVE ORDERS ARE ON — real money" : "Paper mode (no real orders)", 14, live ? RED : GREEN, true);
        col.addView(liveState);
        col.addView(text("Before turning live on:\n"
                + "• Register a static IP with Dhan (SEBI rule from 1 Apr 2026). Orders from other IPs are refused with error DH-905. "
                + "Phone mobile data IPs change, so live orders from the phone will usually fail unless you route through a registered static IP.\n"
                + "• Run paper mode for a few weeks and check the Trades tab is positive after charges.\n"
                + "• Only hedged spreads are used. Max loss per trade is your setting above.", 12, DIM, false));
        col.addView(button(live ? "Switch to PAPER mode" : "Switch to LIVE mode", live ? GREEN : RED, v -> {
            if (BotHub.get().state != BotState.OFF) {
                Toast.makeText(this, "Stop the bot first, then switch mode.", Toast.LENGTH_LONG).show();
                return;
            }
            if (live) {
                settings.putBool("live", false);
                show(TAB_SETTINGS);
                return;
            }
            confirm("Turn on LIVE orders?", "The bot will place REAL orders on your Dhan account with real money.\n\n"
                    + "Your static IP must be registered with Dhan, or every order fails (DH-905).\n\nAre you sure?", () -> {
                settings.putBool("live", true);
                show(TAB_SETTINGS);
            });
        }));

        section(col, "Phone");
        col.addView(button("Battery settings (set this app to Unrestricted)", BLUE, v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { Toast.makeText(this, "Open Android Settings → Apps → this app → Battery", Toast.LENGTH_LONG).show(); }
        }));

        section(col, "Files");
        col.addView(text("Journal (every decision, order and trade): " + new Journal(this).directory().getAbsolutePath(), 12, DIM, false));
        scroll.addView(col);
    }

    private void put(String key, EditText e, double min, double max) {
        String s = e.getText().toString().trim().replace(",", "");
        double v;
        try { v = Double.parseDouble(s); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Not a number: " + s); }
        if (v < min || v > max) throw new IllegalArgumentException(key.replace('_', ' ') + " must be between " + num(min) + " and " + num(max));
        settings.put(key, v);
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        BotHub.Snapshot s = BotHub.get();
        boolean live = s.state == BotState.OFF ? settings.live() : s.live;
        String hold = s.state == BotState.OFF ? (settings.weekly() ? "WEEKLY" : "INTRADAY") : s.holding;
        title.setText(getString(R.string.app_name) + "  ·  " + (live ? "LIVE" : "PAPER") + "  ·  " + hold);
        title.setTextColor(live ? RED : AMBER);
        if (tab == 0 && homeText != null) refreshHome();
        else if (tab == TAB_TEST && testText != null) refreshTest();
        else if (listText != null) refreshList();
    }

    // ------------------------------------------------------------------ small view helpers

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

    private static void add(SpannableStringBuilder b, String s, int color) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void confirm(String t, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(t).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(4), dp(12), dp(12));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private CheckBox check(LinearLayout col, String label, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setChecked(on);
        col.addView(cb);
        return cb;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(dp(1), color);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void section(LinearLayout col, String t) {
        TextView v = text(t, 14, AMBER, true);
        v.setPadding(0, dp(18), 0, dp(4));
        col.addView(v);
    }

    private EditText field(LinearLayout col, String label, String value, boolean secret) {
        col.addView(text(label, 12, DIM, false));
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (value.matches("-?[0-9.]+")) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        col.addView(e);
        return e;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
