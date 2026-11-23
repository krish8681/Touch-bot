package com.krish.niftydhanbot.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/** Full paper-trading day: trend → CE signal → buy → trail → sell, plus failure cases. */
public class EngineFlowTest {
    static final long MIN = 60_000L;
    final BotConfig cfg = new BotConfig();
    final List<String> logs = new ArrayList<>();
    final List<TradeRecord> trades = new ArrayList<>();
    final List<String> alerts = new ArrayList<>();
    Engine engine;
    Broker broker;
    long t0;            // 10:00 IST
    double lastClose;

    Engine.Listener listener = new Engine.Listener() {
        public void onLog(String l, String m) { logs.add(l + " " + m); }
        public void onSignal(Signal s) { logs.add("SIGNAL " + s.side + " " + s.reasonText()); }
        public void onStateChanged(BotState s, String w) {}
        public void onPositionChanged(Position p) {}
        public void onTradeClosed(TradeRecord t) { trades.add(t); }
        public void onStatsChanged(DayStats d) {}
        public void onAlert(String m) { alerts.add(m); }
    };

    @Before public void setUp() {
        t0 = ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, Engine.IST).toInstant().toEpochMilli();
        PaperBroker.QuoteSource qs = id -> engine.quote(id);
        broker = new PaperBroker(qs, cfg.paperSlippagePts);
        engine = newEngine(broker);
    }

    Engine newEngine(Broker b) {
        Engine e = new Engine(cfg, new Charges(), b, listener);
        e.onTimer(t0 - 40 * MIN);
        List<Candle> seed = new ArrayList<>();
        double c = 25000;
        for (int k = 0; k < 30; k++) {
            long start = t0 - (30 - k) * MIN;
            seed.add(new Candle(start, c - 1, c + 2, c - 8, c));
            c += 2;
        }
        lastClose = c - 2;
        e.seedCandles(seed);
        e.setState(BotState.READY, "test");
        return e;
    }

    void quote(Engine e, double bid, double ask, long now) {
        if (e.quote("CE1") == null)
            e.upsertContract("CE1", Signal.Side.CE, 25050, 0.55, 12, 200_000, ask, bid, ask, now);
        e.onOptionTick("CE1", ask, bid, ask, now);
    }

    /** Make the 10:00 candle a breakout and close it at 10:01. */
    void breakout(Engine e) {
        long now = t0 + 1_000;
        e.onSpot(lastClose + 1, now);
        e.onSpot(lastClose + 8, now + 30_000);
        quote(e, 100, 100.5, t0 + MIN + 500);
        e.onSpot(lastClose + 8, t0 + MIN + 1_000); // first tick of 10:01 closes 10:00
    }

    @Test public void fullPaperTradeWithTrailingExit() {
        assertTrue(engine.startTrading());
        breakout(engine);
        assertNotNull("should have bought; logs=" + logs, engine.position());
        Position p = engine.position();
        assertEquals(0, p.qty % cfg.lotSize);
        assertEquals(1, engine.day().entries);
        double risk = p.riskPerUnit;

        long now = t0 + 2 * MIN;
        quote(engine, p.entryPrice + 3 * risk, p.entryPrice + 3 * risk + 0.5, now);   // big move up
        engine.onTimer(now);
        assertNotNull("winner kept running", engine.position());
        assertTrue(engine.position().stop > p.entryPrice);

        quote(engine, p.entryPrice + 1.5 * risk, p.entryPrice + 1.5 * risk + 0.5, now + 1000); // pullback hits trail
        engine.onTimer(now + 1500);
        assertNull(engine.position());
        assertEquals(1, trades.size());
        assertEquals("TRAIL_STOP", trades.get(0).exitReason);
        assertTrue(trades.get(0).net > 0);
        assertEquals(0, engine.day().consecutiveLosses);
    }

    @Test public void stopFiresFromChainRefreshWithoutLiveFeed() {
        engine.startTrading();
        breakout(engine);
        Position p = engine.position();
        assertNotNull(p);
        long now = t0 + 2 * MIN;
        // Price arrives only through the option-chain refresh path, never onOptionTick.
        engine.upsertContract("CE1", Signal.Side.CE, 25050, 0.55, 12, 200_000, p.stop - 1, p.stop - 1, p.stop - 0.5, now);
        engine.onTimer(now);
        assertNull("stop must fire from chain prices too", engine.position());
        assertEquals("STOP", trades.get(0).exitReason);
    }

    @Test public void noEntryWhenNotTrading() {
        breakout(engine); // READY, not TRADING
        assertNull(engine.position());
    }

    @Test public void stopLossCountsLossAndCooldownBlocksNextEntry() {
        engine.startTrading();
        breakout(engine);
        Position p = engine.position();
        assertNotNull(p);
        long now = t0 + 2 * MIN;
        quote(engine, p.stop - 1, p.stop - 0.5, now);
        engine.onTimer(now);
        assertNull(engine.position());
        assertEquals(1, engine.day().losses);
        assertEquals(1, engine.day().consecutiveLosses);
        assertTrue(engine.day().realizedPnl < 0);
        assertTrue("loss within budget: " + engine.day().realizedPnl, engine.day().realizedPnl >= -cfg.riskPerTrade - 1);
    }

    @Test public void emergencyExitClosesAndPauses() {
        engine.startTrading();
        breakout(engine);
        assertNotNull(engine.position());
        engine.emergencyExit(t0 + 2 * MIN);
        assertNull(engine.position());
        assertEquals(BotState.PAUSED, engine.state());
        assertEquals("EMERGENCY", trades.get(0).exitReason);
    }

    @Test public void unexpectedDhanPositionBlocksEntriesUntilAcknowledged() {
        engine.startTrading();
        List<Engine.BrokerPosition> bp = List.of(new Engine.BrokerPosition("999", "NIFTY 24000 PE", 75));
        engine.reconcile(bp);
        assertEquals(BotState.RECOVERY, engine.state());
        breakout(engine);
        assertNull(engine.position());
        engine.acknowledgeRecovery();
        engine.reconcile(bp);
        assertEquals(BotState.READY, engine.state());
    }

    /** Broker that times out on submit but actually placed the order. */
    static final class FlakyBroker implements Broker {
        final PaperBroker inner;
        int submits;
        FlakyBroker(PaperBroker inner) { this.inner = inner; }
        public OrderUpdate submit(OrderRequest r) throws IOException {
            submits++;
            inner.submit(r);
            throw new IOException("timeout");
        }
        public OrderUpdate status(String c) { return inner.status(c); }
        public void cancel(String id) { inner.cancel(id); }
        public boolean isLive() { return false; }
    }

    @Test public void timeoutIsCheckedNotRetried() {
        FlakyBroker fb = new FlakyBroker(new PaperBroker(id -> engine.quote(id), 0.5));
        engine = newEngine(fb);
        engine.startTrading();
        breakout(engine);
        assertNull("position not assumed before confirmation", engine.position());
        assertTrue(engine.hasPendingOrder());
        engine.onTimer(t0 + MIN + 3_000);
        assertNotNull("confirmed by status lookup", engine.position());
        assertEquals("never re-sent", 1, fb.submits);
    }

    @Test public void dailyLossLimitStopsEntries() {
        engine.startTrading();
        DayStats d = engine.day();
        d.realizedPnl = -6_000;
        breakout(engine);
        assertNull(engine.position());
        assertTrue(logs.stream().anyMatch(s -> s.contains("daily loss limit")));
    }
}
