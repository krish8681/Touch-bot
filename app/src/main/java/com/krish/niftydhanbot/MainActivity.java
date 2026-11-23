package com.krish.niftydhanbot;

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

import com.krish.niftydhanbot.core.BotConfig;
import com.krish.niftydhanbot.core.BotState;
import com.krish.niftydhanbot.core.DayStats;
import com.krish.niftydhanbot.core.Engine;
import com.krish.niftydhanbot.core.Signal;
import com.krish.niftydhanbot.core.TradeRecord;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Dark, terminal-style control screen. Reads {@link BotHub}; sends commands to {@link BotService}. */
public final class MainActivity extends Activity {
    static final int BG = Color.parseColor("#0A0F14"), PANEL = Color.parseColor("#121A22"),
            TEXT = Color.parseColor("#E6EDF3"), DIM = Color.parseColor("#7D8B99"),
            GREEN = Color.parseColor("#00E676"), RED = Color.parseColor("#FF1744"),
            AMBER = Color.parseColor("#FFC400"), BLUE = Color.parseColor("#40C4FF");
    private static final String[] TABS = {"Home", "Signals", "Trades", "Log", "Settings"};
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(Engine.IST);
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(Engine.IST);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout tabBar;
    private ScrollView scroll;
    private int tab;
    private TextView homeText, listText;
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

        TextView title = text(getString(R.string.app_name) + "  ·  PAPER MODE", 15, AMBER, true);
        title.setPadding(dp(14), dp(12), dp(14), dp(6));
        root.addView(title);

        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(PANEL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 13, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(14), 0, dp(14));
            t.setOnClickListener(v -> show(idx));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
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
            case 4: buildSettings(); break;
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
                    ? "A position is open. After stopping, nobody manages it." : "Live data and the engine will stop.",
                    () -> BotService.send(this, BotService.ACTION_STOP));
        });
        btnTrade = button("Start trading", GREEN, v -> {
            BotState s = BotHub.get().state;
            BotService.send(this, s == BotState.TRADING ? BotService.ACTION_PAUSE : BotService.ACTION_TRADE);
        });
        row1.addView(btnBot, weight());
        row1.addView(btnTrade, weight());
        col.addView(row1);

        Button emergency = button("EMERGENCY EXIT — close position now", RED, v ->
                confirm("Emergency exit?", "Sell the open position at the market and stop new entries.",
                        () -> BotService.send(this, BotService.ACTION_EMERGENCY)));
        col.addView(emergency);

        btnAck = button("I checked my Dhan account — continue", AMBER, v ->
                BotService.send(this, BotService.ACTION_ACK));
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
        add(t, explain(s.state) + "\n", DIM);
        if (!s.lastError.isEmpty() && s.state == BotState.ERROR) add(t, s.lastError + "\n", RED);

        add(t, "\nMARKET\n", DIM);
        long age = s.spotMillis == 0 ? -1 : (now - s.spotMillis) / 1000;
        add(t, String.format(Locale.US, "NIFTY  %.2f", s.spot), TEXT);
        add(t, age < 0 ? "  (no price yet)\n" : "  (" + age + "s ago)\n", age >= 0 && age <= 5 ? GREEN : AMBER);
        add(t, "Feed   ", DIM); add(t, (s.feedConnected ? "live" : s.feedNote) + "\n", s.feedConnected ? GREEN : AMBER);
        add(t, "Expiry " + (s.expiry.isEmpty() ? "-" : s.expiry) + "   contracts live " + s.contracts + "\n", DIM);
        if (s.dhanFunds >= 0) add(t, "Dhan funds ₹" + BotService.money(s.dhanFunds) + "\n", DIM);

        add(t, "\nPOSITION\n", DIM);
        if (s.position == null) add(t, s.pendingOrder ? "Order working…\n" : "None\n", s.pendingOrder ? AMBER : TEXT);
        else {
            BotHub.PositionView p = s.position;
            add(t, p.label + "  qty " + p.qty + "\n", TEXT);
            add(t, String.format(Locale.US, "Bought %.2f   now %.2f   ", p.entry, p.bid), TEXT);
            add(t, String.format(Locale.US, "₹%s (%.1fR)\n", BotService.money(p.openPnl), p.rMultiple), p.openPnl >= 0 ? GREEN : RED);
            add(t, String.format(Locale.US, "Stop %.2f (%s)   first target %.2f\n", p.stop, p.stopReason, p.target), DIM);
            add(t, "Held " + (now - p.entryMillis) / 60_000 + " min\n", DIM);
        }

        DayStats d = s.day;
        BotConfig c = settings.config();
        double dayTotal = d.realizedPnl + (s.position == null ? 0 : s.position.openPnl);
        add(t, "\nTODAY\n", DIM);
        add(t, "P&L  ", TEXT); add(t, "₹" + BotService.money(dayTotal) + "\n", dayTotal >= 0 ? GREEN : RED);
        add(t, String.format(Locale.US, "Entries %d/%d   wins %d   losses %d   losses in a row %d/%d\n",
                d.entries, c.maxEntriesPerDay, d.wins, d.losses, d.consecutiveLosses, c.maxConsecutiveLosses), TEXT);
        double used = Math.max(0, -dayTotal) / c.dailyLossLimit * 100;
        add(t, String.format(Locale.US, "Daily loss limit used %.0f%% of ₹%s\n", used, BotService.money(c.dailyLossLimit)), used >= 75 ? RED : DIM);

        List<Signal> sig = BotHub.signals();
        if (!sig.isEmpty()) {
            Signal l = sig.get(0);
            add(t, "\nLAST SIGNAL  " + HM.format(Instant.ofEpochMilli(l.timeMillis)) + "\n", DIM);
            add(t, l.side + "  ", sideColor(l.side));
            add(t, l.regime + "  strength " + l.strength + "/100 (rule score, not a probability)\n", TEXT);
            add(t, l.reasonText() + "\n", DIM);
        }
        homeText.setText(t);
    }

    // ------------------------------------------------------------------ Signals / Trades / Log

    private void refreshList() {
        SpannableStringBuilder t = new SpannableStringBuilder();
        if (tab == 1) {
            List<Signal> sig = BotHub.signals();
            if (sig.isEmpty()) add(t, "No signals yet. Signals appear each minute once the bot has enough candles.\n", DIM);
            for (Signal s : sig) {
                add(t, HM.format(Instant.ofEpochMilli(s.timeMillis)) + "  ", DIM);
                add(t, String.format(Locale.US, "%-8s", s.side), sideColor(s.side));
                add(t, s.regime + "  " + s.strength + "\n", TEXT);
                add(t, "  " + s.reasonText() + "\n\n", DIM);
            }
        } else if (tab == 2) {
            List<TradeRecord> tr = new Journal(this).trades(200);
            double total = 0;
            int wins = 0;
            for (TradeRecord r : tr) { total += r.net; if (r.net >= 0) wins++; }
            add(t, String.format(Locale.US, "%d trades   wins %d   net ", tr.size(), wins), TEXT);
            add(t, "₹" + BotService.money(total) + "\n", total >= 0 ? GREEN : RED);
            add(t, "Net = after estimated charges and slippage\n\n", DIM);
            if (tr.isEmpty()) add(t, "No trades yet.\n", DIM);
            for (TradeRecord r : tr) {
                add(t, DHM.format(Instant.ofEpochMilli(r.exitMillis)) + "  " + r.mode + "  " + r.label + "\n", TEXT);
                add(t, String.format(Locale.US, "  qty %d  %.2f → %.2f  %s  charges ₹%.0f  ", r.qty, r.entry, r.exit, r.exitReason, r.charges), DIM);
                add(t, "₹" + BotService.money(r.net) + "\n\n", r.net >= 0 ? GREEN : RED);
            }
        } else if (tab == 3) {
            List<String> logs = BotHub.logs();
            if (logs.isEmpty()) add(t, "Nothing yet. Start the bot on Home.\n", DIM);
            for (String l : logs) add(t, l + "\n", l.contains("WARN") || l.contains("⚠") ? AMBER : TEXT);
        }
        listText.setText(t);
    }

    // ------------------------------------------------------------------ Settings

    private void buildSettings() {
        LinearLayout col = column();
        BotConfig c = settings.config();

        section(col, "Dhan login");
        EditText client = field(col, "Client ID", settings.clientId(), false);
        EditText token = field(col, "Access token (leave empty to keep the saved one)", "", true);
        TextView tokenNote = text(settings.token().isEmpty() ? "No token saved" : "Token saved (hidden). Dhan tokens last about 24 hours.", 12, DIM, false);
        col.addView(tokenNote);

        section(col, "Money and risk");
        EditText capital = field(col, "Capital ₹", num(c.capital), false);
        EditText risk = field(col, "Max loss per trade ₹", num(c.riskPerTrade), false);
        EditText daily = field(col, "Daily loss limit ₹", num(c.dailyLossLimit), false);
        EditText weekly = field(col, "Weekly loss limit ₹", num(c.weeklyLossLimit), false);
        EditText monthly = field(col, "Monthly loss limit ₹", num(c.monthlyLossLimit), false);
        EditText entries = field(col, "Max trades per day", num(c.maxEntriesPerDay), false);
        EditText consec = field(col, "Stop after this many losses in a row", num(c.maxConsecutiveLosses), false);
        EditText hold = field(col, "Max minutes to hold a trade", num(c.maxHoldMinutes), false);

        section(col, "Contract");
        EditText lot = field(col, "NIFTY lot size (check NSE's current value)", num(c.lotSize), false);
        EditText maxLots = field(col, "Max lots per trade", num(c.maxLots), false);
        CheckBox lotOk = new CheckBox(this);
        lotOk.setText("I checked this lot size is correct");
        lotOk.setTextColor(TEXT);
        lotOk.setChecked(settings.lotSizeConfirmed());
        col.addView(lotOk);
        CheckBox avoidExpiry = new CheckBox(this);
        avoidExpiry.setText("On expiry day, trade next week's contract");
        avoidExpiry.setTextColor(TEXT);
        avoidExpiry.setChecked(c.avoidExpiryDayContract);
        col.addView(avoidExpiry);

        section(col, "Telegram alerts (optional)");
        EditText tgToken = field(col, "Bot token (leave empty to keep the saved one)", "", true);
        EditText tgChat = field(col, "Chat ID", settings.telegramChat(), false);

        Button save = button("Save settings", GREEN, v -> {
            try {
                settings.saveCredentials(client.getText().toString(), token.getText().toString());
                settings.saveTelegram(tgToken.getText().toString(), tgChat.getText().toString());
                put("capital", capital, 10_000, 1e8);
                put("risk_per_trade", risk, 100, 100_000);
                put("daily_loss", daily, 100, 1e6);
                put("weekly_loss", weekly, 100, 1e7);
                put("monthly_loss", monthly, 100, 1e7);
                put("max_entries", entries, 1, 20);
                put("max_consec_losses", consec, 1, 10);
                put("max_hold_min", hold, 1, 120);
                put("lot_size", lot, 1, 5000);
                put("max_lots", maxLots, 1, 100);
                settings.putBool("lot_size_confirmed", lotOk.isChecked());
                settings.putBool("avoid_expiry_day", avoidExpiry.isChecked());
                Toast.makeText(this, "Saved. Stop and start the bot to use new settings.", Toast.LENGTH_LONG).show();
                show(4);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        col.addView(save);

        section(col, "Phone");
        col.addView(button("Battery settings (set this app to Unrestricted)", BLUE, v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { Toast.makeText(this, "Open Android Settings → Apps → this app → Battery", Toast.LENGTH_LONG).show(); }
        }));

        section(col, "Live orders");
        col.addView(text("LOCKED in this version. The bot only paper-trades on live Dhan prices.\n\n"
                + "Before live orders can be switched on:\n"
                + "• Written OK from Dhan for running your own algo from this phone (retail algo rules).\n"
                + "• A static IP registered with Dhan. Mobile data IPs change, so Dhan refuses orders from them.\n"
                + "• Several weeks of paper results that are positive after charges.", 13, DIM, false));

        section(col, "Files");
        col.addView(text("Journal (every signal, order, block and trade): " + new Journal(this).directory().getAbsolutePath(), 12, DIM, false));
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
        if (tab == 0 && homeText != null) refreshHome();
        else if (listText != null) refreshList();
    }

    // ------------------------------------------------------------------ small view helpers

    private static String explain(BotState s) {
        switch (s) {
            case OFF: return "Bot is stopped.";
            case STARTING: return "Connecting to Dhan and loading data…";
            case READY: return "Connected. Tap Start trading to allow paper entries.";
            case TRADING: return "Watching for trades. Stops and exits are active.";
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

    private static int sideColor(Signal.Side s) { return s == Signal.Side.CE ? GREEN : s == Signal.Side.PE ? RED : DIM; }

    private static void add(SpannableStringBuilder b, String s, int color) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
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

    private void section(LinearLayout col, String title) {
        TextView t = text(title, 14, AMBER, true);
        t.setPadding(0, dp(18), 0, dp(4));
        col.addView(t);
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
