package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.Config;
import com.krish.niftyregimebot.core.DayStats;
import com.krish.niftyregimebot.core.Engine;
import com.krish.niftyregimebot.core.Leg;
import com.krish.niftyregimebot.core.MarketClock;
import com.krish.niftyregimebot.core.OptionQuote;
import com.krish.niftyregimebot.core.Position;
import com.krish.niftyregimebot.core.RegimeDetector;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Builds the screen's {@link BotHub.Snapshot}: state, market, position, warnings and the self-test. */
final class StatusBuilder {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(MarketClock.IST);

    private StatusBuilder() {}

    static BotHub.Snapshot build(Engine engine, Config cfg, Settings settings, DhanLink link, boolean live, long startedMillis) {
        BotHub.Snapshot s = new BotHub.Snapshot();
        long now = System.currentTimeMillis();
        if (engine != null) fromEngine(s, engine, cfg, now);
        s.live = live;
        s.holding = cfg == null ? "-" : cfg.holding.name();
        s.startedMillis = startedMillis;
        if (link != null) {
            s.feedConnected = link.feedConnected;
            s.feedNote = link.feedNote;
            s.dhanFunds = link.funds;
            s.lastError = link.lastError;
        }
        long made = settings == null ? 0 : settings.tokenMadeMillis();
        s.tokenAgeHours = made == 0 ? -1 : (now - made) / 3_600_000.0;
        s.autoRenew = settings != null && settings.autoRenewToken();
        warnings(s, engine, link, now, startedMillis);
        if (engine != null && link != null && settings != null) selfTest(s, engine, cfg, settings, link, now, startedMillis);
        return s;
    }

    private static void fromEngine(BotHub.Snapshot s, Engine engine, Config cfg, long now) {
        s.state = engine.state();
        s.spot = engine.spot();
        s.spotMillis = engine.lastSpotMillis();
        s.day = DayStats.fromMap(engine.day().toMap());
        s.job = engine.jobText();
        s.expiry = engine.tradeExpiry();
        s.barsFixed = engine.barsFixed();
        s.waitingForDhanBar = engine.pendingBarStart() > 0;
        if (engine.lastReading() != null) {
            s.regime = String.valueOf(engine.lastReading().confirmed);
            s.regimeWhy = engine.lastReading().text();
        }
        Engine.Decision d = engine.lastDecision();
        if (d != null) {
            s.ivText = d.iv == null ? "-" : d.iv.text();
            s.vix = d.iv == null ? Double.NaN : d.iv.vix;
            s.pick = d.pick.title;
            s.lastAction = d.action;
        }
        Position p = engine.position();
        if (p != null) s.position = view(p, engine, cfg);
        int fresh = 0;
        for (OptionQuote q : engine.quotes()) if (now - q.updatedMillis < 15_000) fresh++;
        s.contracts = fresh;
    }

    private static BotHub.PositionView view(Position p, Engine engine, Config cfg) {
        BotHub.PositionView v = new BotHub.PositionView();
        v.title = p.type.title;
        v.expiry = p.expiry;
        v.holding = p.holding.name();
        v.qty = p.qty;
        v.lots = p.qty / Math.max(1, cfg.lotSize);
        v.netPremium = p.netPremium;
        v.openPnl = p.openPnl;
        v.fairPnl = p.fairPnl;
        v.target = p.targetRs;
        v.stop = p.stopRs;
        v.maxLoss = p.maxLossRs();
        v.maxProfit = p.maxProfitRs();
        v.entryMillis = p.entryMillis;
        v.stale = p.stale;
        for (Leg l : p.legs) {
            BotHub.LegView lv = new BotHub.LegView();
            lv.label = l.label();
            lv.buy = l.buy;
            lv.entry = l.price;
            OptionQuote q = engine.quote(l.securityId);
            lv.now = q == null ? 0 : (l.buy ? q.bid : q.ask);
            v.legs.add(lv);
        }
        return v;
    }

    private static void warnings(BotHub.Snapshot s, Engine engine, DhanLink link, long now, long started) {
        LocalDate today = LocalDate.now(MarketClock.IST);
        String vixId = link == null ? "21" : link.vixId();
        if (!MarketClock.tradingDay(today)) s.warnings.add("Market closed today (weekend or NSE holiday)");
        if (MarketClock.holidayListOutdated(today)) s.warnings.add("No NSE holidays known for " + today.getYear() + " — add them in Settings");
        if (engine != null && !engine.ivGauge().hasVixHistory())
            s.warnings.add("India VIX history not loaded (VIX ID " + vixId + "?) — IV level uses ATM IV, which is rougher");
        if (link != null && link.feedConnected && MarketClock.sessionOpen(now) && now - started > 120_000 && now - link.lastVixTick > 120_000)
            s.warnings.add("No live India VIX ticks for 2+ min — check the VIX ID in Settings");
        if (s.tokenAgeHours >= 20) s.warnings.add(String.format(Locale.US, "Dhan token is %.0f h old (expires at 24 h)%s",
                s.tokenAgeHours, s.autoRenew && s.tokenAgeHours < 24 ? " — auto-renew will try" : " — paste a new one"));
        if (link != null && link.tokenExpired) s.warnings.add("Dhan token expired — paste a new one in Settings and tap Save");
    }

    /** Self-test lines for Home: ✓ ok, ✗ problem, … not known yet, – not needed now. */
    private static void selfTest(BotHub.Snapshot s, Engine engine, Config cfg, Settings settings, DhanLink link, long now, long started) {
        boolean open = MarketClock.sessionOpen(now);
        boolean waited = now - started > 90_000;
        s.checks.add((link.funds >= 0 ? "✓" : link.lastError.isEmpty() ? "…" : "✗") + " Dhan login");
        int minBars = new RegimeDetector(cfg).minBars();
        s.checks.add((engine.bars().size() >= minBars ? "✓" : "…") + " NIFTY bars (" + engine.bars().size() + ")");
        s.checks.add((engine.ivGauge().hasVixHistory() ? "✓" : waited ? "✗" : "…") + " India VIX history (ID " + link.vixId() + ")");
        String vixLive = link.lastVixTick > 0 && now - link.lastVixTick < 120_000 ? "✓" : !open ? "–" : waited ? "✗" : "…";
        s.checks.add(vixLive + " Live India VIX" + (open ? "" : " (market closed)"));
        int depth = 0, priced = 0;
        for (OptionQuote q : engine.quotes()) {
            if (now - q.updatedMillis < 30_000) priced++;
            if (q.depthMillis > 0 && now - q.depthMillis < 60_000) depth++;
        }
        s.checks.add((priced > 0 ? "✓" : waited ? "✗" : "…") + " Option prices (" + priced + " live)");
        s.checks.add((depth > 0 ? "✓" : !open ? "–" : waited ? "✗" : "…") + " Order book depth (" + depth + ")" + (open ? "" : " (market closed)"));
        long renewed = settings.lastRenewMillis();
        s.checks.add(renewed > 0 ? "✓ Token auto-renew worked (" + HM.format(Instant.ofEpochMilli(renewed)) + ")"
                : settings.autoRenewToken() ? "… Token auto-renew not tried yet (runs at 20 h)" : "– Token auto-renew is off");
        double pcr = engine.pcr();
        s.checks.add(pcr > 0 ? String.format(Locale.US, "✓ Open interest (PCR %.2f)", pcr) : "… Open interest not seen yet");
    }
}
