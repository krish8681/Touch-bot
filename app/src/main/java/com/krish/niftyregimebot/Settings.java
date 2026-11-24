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
        if (token != null && !token.trim().isEmpty()) secrets.put(K_TOKEN, token.trim());
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
