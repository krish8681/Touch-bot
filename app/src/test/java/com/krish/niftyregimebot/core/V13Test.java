package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** v1.3: resampling, event days, legging guard, cool-down after wins / trend flips. */
public class V13Test {

    @Test public void resampleBuilds15MinuteBarsFrom0915() {
        long t0 = TestUtil.ist("2026-11-25T09:15");
        List<Candle> five = new ArrayList<>();
        for (int i = 0; i < 6; i++) five.add(new Candle(t0 + i * 300_000L, 100 + i, 105 + i, 95 + i, 101 + i));
        List<Candle> f = Engine.resample(five, 15);
        assertEquals(2, f.size());
        assertEquals(t0, f.get(0).startMillis);
        assertEquals(t0 + 900_000L, f.get(1).startMillis);
        assertEquals(100, f.get(0).open, 0.001);
        assertEquals(107, f.get(0).high, 0.001);
        assertEquals(95, f.get(0).low, 0.001);
        assertEquals(103, f.get(0).close, 0.001);
    }

    @Test public void eventDayBlocksEntries() {
        try {
            MarketClock.setExtraEvents(Arrays.asList("2026-11-25"));
            Config c = TestUtil.cfg();
            EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
            Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
            long t = EngineFlowTest.closeBar(e, 26000);
            for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
            assertNull(e.position());
            Engine.Decision d = rec.decisions.get(rec.decisions.size() - 1);
            assertTrue(d.action, d.action.contains("event day"));
        } finally {
            MarketClock.setExtraEvents(null);
        }
    }

    @Test public void eventFilterCanBeSwitchedOff() {
        try {
            MarketClock.setExtraEvents(Arrays.asList("2026-11-25"));
            Config c = TestUtil.cfg();
            c.eventFilter = false;
            EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
            Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
            long t = EngineFlowTest.closeBar(e, 26000);
            for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
            assertNotNull(rec.all(), e.position());
        } finally {
            MarketClock.setExtraEvents(null);
        }
    }

    @Test public void bigMoveWhileLeggingInUndoesTheEntry() {
        Config c = TestUtil.cfg();
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine[] h = new Engine[1];
        Broker broker = new Broker() {
            String first;
            final PaperBroker inner = new PaperBroker(id -> h[0].quote(id), c.paperSlippage);
            Broker.OrderRequest firstReq;
            public OrderUpdate submit(OrderRequest r) {
                if (first == null && r.correlationId.startsWith("NRBE")) { first = r.correlationId; firstReq = r; return new OrderUpdate("H1", Status.PENDING, 0, 0, "wait"); }
                return inner.submit(r);
            }
            public OrderUpdate status(String id) {
                if (id.equals(first)) {
                    if (h[0].spot() < 26030) return new OrderUpdate("H1", Status.PENDING, 0, 0, "wait");
                    return new OrderUpdate("H1", Status.TRADED, firstReq.qty, firstReq.limitPrice, "filled");
                }
                return inner.status(id);
            }
            public void cancel(String id) { inner.cancel(id); }
            public boolean isLive() { return false; }
        };
        Engine e = new Engine(c, new Charges(), broker, rec);
        h[0] = e;
        long t0 = TestUtil.ist("2026-11-25T10:00");
        e.onTimer(t0);
        List<Double> vix = new ArrayList<>();
        for (int i = 0; i < 250; i++) vix.add(10 + (i % 100) / 10.0);
        e.setVixHistory(vix);
        e.onVix(11);
        e.seedBars(TestUtil.bars(60, 5, t0, 26000 - 360, 6, 2));
        e.setTradeExpiry(EngineFlowTest.EXP);
        TestUtil.loadChain(e, EngineFlowTest.EXP, 26000, t0);
        e.setState(BotState.READY, "t");
        e.startTrading();
        long t = EngineFlowTest.closeBar(e, 26000);
        e.onTimer(t);
        assertTrue("entry started", e.busy());
        // NIFTY jumps 40 points before the first leg fills
        e.onSpot(26040, t + 2_000);
        for (int i = 2; i < 12; i++) e.onTimer(t + i * 1000);
        assertNull(rec.all(), e.position());
        assertTrue(rec.all(), rec.alerts.stream().anyMatch(a -> a.contains("moved")));
    }

    @Test public void cooldownIsShortAfterAWin() {
        Config c = TestUtil.cfg();
        c.maxTradesPerDay = 5;
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        long t = EngineFlowTest.closeBar(e, 26000);
        for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
        assertNotNull(e.position());
        long t2 = t + 60_000;
        TestUtil.reprice(e, 26450, t2);
        e.onSpot(26450, t2);
        for (int i = 0; i < 8; i++) e.onTimer(t2 + i * 1000);
        assertEquals("TARGET", rec.trades.get(0).exitReason);
        long exit = t2 + 8_000;
        e.onSpot(26450, exit + 4 * 60_000);
        assertEquals("after a win the wait is 3 minutes, not 10", null, waitReason(e));
    }

    private static String waitReason(Engine e) {
        String b = e.entryBlock();
        return b != null && b.contains("cooling") ? b : null;
    }
}
