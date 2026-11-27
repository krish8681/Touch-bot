package com.krish.niftyregimebot;

import android.content.Context;
import android.content.SharedPreferences;

import com.krish.niftyregimebot.core.Config;

/** User settings. Numbers live in normal prefs; tokens live in {@link SecretStore}. */
public final class Settings {
    public static final String K_TOKEN = "dhan_token", K_TG_TOKEN = "tg_token";

    private final SharedPreferences p;
    private final SecretStore secrets;

    public Settings(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        secrets = new SecretStore(c);
    }

    public String clientId() { return p.getString("client_id", ""); }
    public String token() { return secrets.get(K_TOKEN); }
    public String telegramToken() { return secrets.get(K_TG_TOKEN); }
    public String telegramChat() { return p.getString("tg_chat", ""); }
    public String vixId() { return p.getString("vix_id", "21"); }

    public void saveCredentials(String clientId, String token) {
        p.edit().putString("client_id", clientId.trim()).apply();
        if (token != null && !token.trim().isEmpty()) saveToken(token, System.currentTimeMillis());
    }

    /** Save a Dhan token and when it was made (Dhan tokens last 24 hours). commit() so a renewed token is never lost. */
    public void saveToken(String token, long madeMillis) {
        secrets.put(K_TOKEN, token.trim());
        p.edit().putLong("token_made", madeMillis).commit();
    }

    /** When the current token was pasted or renewed (0 = unknown). */
    public long tokenMadeMillis() { return p.getLong("token_made", 0L); }

    /** Extra event days (RBI policy, Budget, elections…), yyyy-MM-dd comma separated. */
    public String extraEvents() { return p.getString("extra_events", ""); }
    public void saveExtraEvents(String s) { p.edit().putString("extra_events", s.trim()).apply(); }
    public java.util.List<String> extraEventList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : extraEvents().split("[,\\s]+")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    public String lastBacktest() { return p.getString("last_backtest", ""); }
    public void saveLastBacktest(String text) { p.edit().putString("last_backtest", text).apply(); }
    public boolean intradayButterfly() { return p.getBoolean("intraday_butterfly", false); }

    public long lastRenewMillis() { return p.getLong("last_renew", 0L); }
    public void markRenewed(long t) { p.edit().putLong("last_renew", t).apply(); }

    public boolean autoRenewToken() { return p.getBoolean("auto_renew_token", true); }

    /** Extra NSE holidays the user typed (yyyy-MM-dd, comma separated), e.g. next year's list. */
    public String extraHolidays() { return p.getString("extra_holidays", ""); }
    public void saveExtraHolidays(String s) { p.edit().putString("extra_holidays", s.trim()).apply(); }
    public java.util.List<String> extraHolidayList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : extraHolidays().split("[,\\s]+")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    public void saveTelegram(String botToken, String chat) {
        p.edit().putString("tg_chat", chat.trim()).apply();
        if (botToken != null && !botToken.trim().isEmpty()) secrets.put(K_TG_TOKEN, botToken.trim());
    }

    public void saveVixId(String id) { p.edit().putString("vix_id", id.trim()).apply(); }

    public Config config() {
        Config c = new Config();
        c.capital = d("capital", c.capital);
        c.riskPerTrade = d("risk_per_trade", c.riskPerTrade);
        c.dailyLossLimit = d("daily_loss", c.dailyLossLimit);
        c.maxTradesPerDay = (int) d("max_trades", c.maxTradesPerDay);
        c.maxConsecutiveLosses = (int) d("max_consec_losses", c.maxConsecutiveLosses);
        c.lotSize = (int) d("lot_size", c.lotSize);
        c.maxLots = (int) d("max_lots", c.maxLots);
        c.spreadWidth = d("spread_width", c.spreadWidth);
        c.targetPctCredit = d("target_credit_pct", c.targetPctCredit * 100) / 100;
        c.targetPctDebit = d("target_debit_pct", c.targetPctDebit * 100) / 100;
        c.stopPctOfMaxLoss = d("stop_pct", c.stopPctOfMaxLoss * 100) / 100;
        c.holding = weekly() ? Config.Holding.WEEKLY : Config.Holding.INTRADAY;
        c.minTargetToCharges = d("min_target_x_charges", c.minTargetToCharges);
        c.intradayButterfly = intradayButterfly();
        c.confirm15m = p.getBoolean("f_confirm15m", c.confirm15m);
        c.chopFilter = p.getBoolean("f_chop", c.chopFilter);
        c.pcrFilter = p.getBoolean("f_pcr", c.pcrFilter);
        c.eventFilter = p.getBoolean("f_event", c.eventFilter);
        c.gapCheck = p.getBoolean("f_gap", c.gapCheck);
        c.reenterAfterFlip = p.getBoolean("f_reenter", c.reenterAfterFlip);
        return c;
    }

    public boolean weekly() { return p.getBoolean("weekly", false); }
    public boolean live() { return p.getBoolean("live", false); }
    public boolean lotSizeConfirmed() { return p.getBoolean("lot_size_confirmed", false); }

    public void put(String key, double v) { p.edit().putFloat(key, (float) v).apply(); }
    public void putBool(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    public double d(String key, double def) { return p.contains(key) ? p.getFloat(key, (float) def) : def; }

    public String dayStatsJson() { return p.getString("day_stats", ""); }
    public void saveDayStats(String json) { p.edit().putString("day_stats", json).apply(); }

    /** The open position, saved so a weekly trade survives the app or phone restarting. */
    public String positionJson() { return p.getString("position", ""); }
    public void savePosition(String json) { p.edit().putString("position", json).commit(); }
}
