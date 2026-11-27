package com.krish.niftyregimebot;

import android.app.Activity;
import android.content.Intent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.krish.niftyregimebot.core.BotState;
import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.MarketClock;

import java.time.LocalDate;

/** The Settings tab: every setting, checked and saved in one place. */
final class SettingsScreen {
    private final Activity a;
    private final Ui ui;
    private final Settings settings;
    private final Runnable redraw;

    SettingsScreen(Activity a, Ui ui, Settings settings, Runnable redraw) {
        this.a = a;
        this.ui = ui;
        this.settings = settings;
        this.redraw = redraw;
    }

    LinearLayout build() {
        LinearLayout col = ui.column();
        Config c = settings.config();

        ui.section(col, "Dhan login");
        EditText client = ui.field(col, "Client ID", settings.clientId(), false);
        EditText token = ui.field(col, "Access token (leave empty to keep the saved one)", "", true);
        ui.note(col, settings.token().isEmpty() ? "No token saved" : "Token saved (hidden). Dhan tokens last about 24 hours.");
        CheckBox autoRenew = ui.check(col, "Auto-renew the Dhan token before it expires (works for tokens made on Dhan Web)", settings.autoRenewToken());

        ui.section(col, "How long to hold");
        CheckBox weekly = ui.check(col, "WEEKLY: hold for days (exit the day before expiry). Off = INTRADAY: close by 15:15", settings.weekly());

        ui.section(col, "Money and risk");
        EditText capital = ui.field(col, "Capital ₹ (paper mode uses this as funds)", Ui.num(c.capital), false);
        EditText risk = ui.field(col, "Max loss per trade ₹ (sizes the lots)", Ui.num(c.riskPerTrade), false);
        EditText daily = ui.field(col, "Daily loss limit ₹", Ui.num(c.dailyLossLimit), false);
        EditText trades = ui.field(col, "Max trades per day", Ui.num(c.maxTradesPerDay), false);
        EditText consec = ui.field(col, "Stop after this many losses in a row", Ui.num(c.maxConsecutiveLosses), false);

        ui.section(col, "Strategy");
        EditText width = ui.field(col, "Spread width (points between strikes)", Ui.num(c.spreadWidth), false);
        EditText tCredit = ui.field(col, "Take profit at % of max profit (credit spreads)", Ui.num(c.targetPctCredit * 100), false);
        EditText tDebit = ui.field(col, "Take profit at % of max profit (debit spreads)", Ui.num(c.targetPctDebit * 100), false);
        EditText stop = ui.field(col, "Stop loss at % of max loss", Ui.num(c.stopPctOfMaxLoss * 100), false);
        EditText minX = ui.field(col, "Skip a trade if its profit target is less than this × its charges", Ui.num(c.minTargetToCharges), false);
        CheckBox fly = ui.check(col, "INTRADAY sideways: trade a long call butterfly (off = no trade; credit trades are WEEKLY only)", c.intradayButterfly);

        ui.section(col, "Entry filters and guards");
        ui.note(col, "Compare on vs off with the Test tab before you decide.");
        CheckBox f15 = ui.check(col, "INTRADAY: 15-min trend must agree with the 5-min trend", c.confirm15m);
        CheckBox fChop = ui.check(col, "Skip trend trades while ADX is falling or the day's range is tiny", c.chopFilter);
        CheckBox fPcr = ui.check(col, "Open-interest check: skip a bullish trade if the put-call ratio fell over 10% today (and the reverse)", c.pcrFilter);
        CheckBox fEvent = ui.check(col, "Event days: no entries on RBI policy / Budget days; WEEKLY closes the day before", c.eventFilter);
        CheckBox fGap = ui.check(col, "WEEKLY: exit at the open if NIFTY gaps 0.8% or more against the trade", c.gapCheck);
        CheckBox fRe = ui.check(col, "After a trend-flip exit, allow a new trade at once (cool-down 3 min after a win, 10 min after a loss)", c.reenterAfterFlip);
        ui.note(col, "Built-in event days: RBI policy days of 2026-27 and Budget days (" + String.join(", ", MarketClock.BUILT_IN_EVENTS) + ")");
        EditText events = ui.field(col, "Extra event days (yyyy-MM-dd, comma separated)", settings.extraEvents(), false);

        ui.section(col, "Contract");
        EditText lot = ui.field(col, "NIFTY lot size (check NSE's current value)", Ui.num(c.lotSize), false);
        EditText maxLots = ui.field(col, "Max lots per trade", Ui.num(c.maxLots), false);
        CheckBox lotOk = ui.check(col, "I checked this lot size is correct", settings.lotSizeConfirmed());
        EditText vix = ui.field(col, "India VIX security ID on Dhan (IDX_I)", settings.vixId(), false);

        ui.section(col, "NSE holidays");
        ui.note(col, "Built in: all 2026 NSE holidays. Add next year's dates when NSE publishes them (usually in December).");
        EditText holidays = ui.field(col, "Extra holidays (yyyy-MM-dd, comma separated)", settings.extraHolidays(), false);

        ui.section(col, "Telegram alerts (optional)");
        EditText tgToken = ui.field(col, "Bot token (leave empty to keep the saved one)", "", true);
        EditText tgChat = ui.field(col, "Chat ID", settings.telegramChat(), false);

        col.addView(ui.button("Save settings", Ui.GREEN, v -> {
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
                put("lot_size", lot, 1, 5000);
                put("max_lots", maxLots, 1, 100);
                String hol = dates(holidays, "Holiday");
                String ev = dates(events, "Event day");
                boolean newToken = !token.getText().toString().trim().isEmpty();
                settings.saveExtraHolidays(hol);
                settings.saveExtraEvents(ev);
                settings.putBool("intraday_butterfly", fly.isChecked());
                settings.putBool("f_confirm15m", f15.isChecked());
                settings.putBool("f_chop", fChop.isChecked());
                settings.putBool("f_pcr", fPcr.isChecked());
                settings.putBool("f_event", fEvent.isChecked());
                settings.putBool("f_gap", fGap.isChecked());
                settings.putBool("f_reenter", fRe.isChecked());
                settings.putBool("auto_renew_token", autoRenew.isChecked());
                settings.saveCredentials(client.getText().toString(), token.getText().toString());
                settings.saveTelegram(tgToken.getText().toString(), tgChat.getText().toString());
                settings.saveVixId(vix.getText().toString().isEmpty() ? "21" : vix.getText().toString());
                settings.putBool("weekly", weekly.isChecked());
                settings.putBool("lot_size_confirmed", lotOk.isChecked());
                if (newToken && BotHub.get().state != BotState.OFF) {
                    BotService.send(a, BotService.ACTION_RELOAD);
                    toast("Saved. Reconnecting with the new token now. Other changes apply after a bot restart.");
                } else {
                    toast("Saved. Stop and start the bot to use new settings.");
                }
                redraw.run();
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        }));

        liveSection(col);

        ui.section(col, "Phone");
        col.addView(ui.button("Battery settings (set this app to Unrestricted)", Ui.BLUE, v -> {
            try { a.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { toast("Open Android Settings → Apps → this app → Battery"); }
        }));

        ui.section(col, "Files");
        ui.note(col, "Journal (every decision, order and trade): " + new Journal(a).directory().getAbsolutePath());
        return col;
    }

    private void liveSection(LinearLayout col) {
        ui.section(col, "Live orders");
        boolean live = settings.live();
        col.addView(ui.text(live ? "LIVE ORDERS ARE ON — real money" : "Paper mode (no real orders)", 14, live ? Ui.RED : Ui.GREEN, true));
        ui.note(col, "Before turning live on:\n"
                + "• Register a static IP with Dhan (SEBI rule from 1 Apr 2026). Orders from other IPs are refused with error DH-905. "
                + "Phone mobile data IPs change, so live orders from the phone will usually fail unless you route through a registered static IP.\n"
                + "• Run paper mode for a few weeks and check the Trades tab is positive after charges.\n"
                + "• Only hedged spreads are used. Max loss per trade is your setting above.");
        col.addView(ui.button(live ? "Switch to PAPER mode" : "Switch to LIVE mode", live ? Ui.GREEN : Ui.RED, v -> {
            if (BotHub.get().state != BotState.OFF) { toast("Stop the bot first, then switch mode."); return; }
            if (live) { settings.putBool("live", false); redraw.run(); return; }
            ui.confirm("Turn on LIVE orders?", "The bot will place REAL orders on your Dhan account with real money.\n\n"
                    + "Your static IP must be registered with Dhan, or every order fails (DH-905).\n\nAre you sure?", () -> {
                settings.putBool("live", true);
                redraw.run();
            });
        }));
    }

    private void put(String key, EditText e, double min, double max) {
        String s = e.getText().toString().trim().replace(",", "");
        double v;
        try { v = Double.parseDouble(s); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Not a number: " + s); }
        if (v < min || v > max) throw new IllegalArgumentException(key.replace('_', ' ') + " must be between " + Ui.num(min) + " and " + Ui.num(max));
        settings.put(key, v);
    }

    /** Check a comma-separated list of yyyy-MM-dd dates; returns it trimmed. */
    private static String dates(EditText e, String what) {
        String s = e.getText().toString().trim();
        for (String d : s.split("[,\\s]+")) {
            if (d.isEmpty()) continue;
            try { LocalDate.parse(d); } catch (RuntimeException ex) { throw new IllegalArgumentException(what + " not a date (use yyyy-MM-dd): " + d); }
        }
        return s;
    }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); }
}
