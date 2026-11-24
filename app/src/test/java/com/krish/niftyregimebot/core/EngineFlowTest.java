package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** End-to-end paper runs of the engine with a synthetic market. */
public class EngineFlowTest {

    static final class Rec implements Engine.Listener {
        final List<String> logs = new ArrayList<>(), alerts = new ArrayList<>();
        final List<TradeRecord> trades = new ArrayList<>();
        final List<Engine.Decision> decisions = new ArrayList<>();
        String saved = "";
        public void onLog(String level, String m) { logs.add(level + " " + m); }
        public void onDecision(Engine.Decision d) { decisions.add(d); }
        public void onStateChanged(BotState s, String why) {}
        public void onPositionChanged(Position p) {}
        public void onPositionSaved(String json) { saved = json; }
        public void onTradeClosed(TradeRecord t) { trades.add(t); }
        public void onStatsChanged(DayStats d) {}
        public void onAlert(String m) { alerts.add(m); }
        String all() { return String.join("\n", logs) + "\nALERTS:\n" + String.join("\n", alerts); }
    }

    static final String EXP = "2026-12-01";

    /** Engine primed with history, VIX, chain; ready to trade at 10:00 on Tue 24 Nov 2026. */
    static Engine engine(Config c, Rec rec, double vixNow, double step, double spotEnd) {
        Engine[] holder = new Engine[1];
        PaperBroker paper = new PaperBroker(id -> holder[0].quote(id), c.paperSlippage);
        Engine e = new Engine(c, new Charges(), paper, rec);
        holder[0] = e;
        long t0 = TestUtil.ist("2026-11-24T10:00");
        e.onTimer(t0);
        List<Double> vix = new ArrayList<>();
        for (int i = 0; i < 250; i++) vix.add(10 + (i % 100) / 10.0);
        e.setVixHistory(vix);
        e.onVix(vixNow);
        double start = spotEnd - step * 60;
        e.seedBars(TestUtil.bars(60, 5, t0, start, step, step == 0 ? 6 : 2));
        e.setTradeExpiry(EXP);
        TestUtil.loadChain(e, EXP, spotEnd, t0);
        e.setState(BotState.READY, "test");
        e.startTrading();
        return e;
    }

    /** Feed ticks so the 10:00 bar closes at 10:05 and the engine decides. */
    static long closeBar(Engine e, double spot) {
        long t = TestUtil.ist("2026-11-24T10:00");
        e.onSpot(spot, t + 1_000);
        e.onSpot(spot, t + 5 * 60_000 + 500);     // first tick of the next bar closes 10:00 bar
        return t + 5 * 60_000 + 500;
    }

    @Test public void uptrendLowIvOpensBullCallSpreadAndHitsTarget() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        assertEquals(RegimeDetector.Regime.UP, e.lastReading().confirmed);

        long t = closeBar(e, 26000);
        for (int i = 0; i < 5; i++) e.onTimer(t + i * 1000);
        Position p = e.position();
        assertNotNull(rec.all(), p);
        assertEquals(StrategyGrid.Type.BULL_CALL_SPREAD, p.type);
        assertEquals(2, p.legs.size());
        assertTrue(p.legs.get(0).buy);
        assertEquals(26000, p.legs.get(0).strike, 0.01);
        assertEquals(26200, p.legs.get(1).strike, 0.01);
        assertTrue(p.netPremium < 0);
        assertTrue("max loss within budget", p.maxLossRs() <= c.riskPerTrade);
        assertFalse(rec.saved.isEmpty());

        // Market rallies to 26450 → spread near max value → target exit.
        long t2 = t + 60_000;
        TestUtil.reprice(e, 26450, t2);
        e.onSpot(26450, t2);
        for (int i = 0; i < 6; i++) e.onTimer(t2 + i * 1000);
        assertNull(rec.all(), e.position());
        assertEquals(1, rec.trades.size());
        TradeRecord tr = rec.trades.get(0);
        assertEquals("TARGET", tr.exitReason);
        assertTrue("profit after charges", tr.net > 0);
        assertEquals("", rec.saved);
        assertEquals(1, e.day().wins);
    }

    @Test public void highIvSidewaysOpensIronCondorAndStopsOut() {
        Config c = new Config();
        c.riskPerTrade = 20_000;
        Rec rec = new Rec();
        Engine e = engine(c, rec, 19, 0, 26000);
        assertEquals(RegimeDetector.Regime.SIDEWAYS, e.lastReading().confirmed);
        long t = closeBar(e, 26000);
        for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
        Position p = e.position();
        assertNotNull(rec.all(), p);
        assertEquals(StrategyGrid.Type.IRON_CONDOR, p.type);
        assertEquals(4, p.legs.size());
        assertTrue("credit trade", p.netPremium > 0);
        // wings bought first
        assertTrue(p.legs.get(0).buy && p.legs.get(1).buy);

        // Crash through the put side → stop.
        long t2 = t + 120_000;
        TestUtil.reprice(e, 25350, t2);
        e.onSpot(25350, t2);
        for (int i = 0; i < 8; i++) e.onTimer(t2 + i * 1000);
        assertNull(rec.all(), e.position());
        assertEquals("STOP", rec.trades.get(0).exitReason);
        assertTrue(rec.trades.get(0).net < 0);
        assertTrue("loss capped near the stop, well below max loss",
                -rec.trades.get(0).net < p.maxLossRs());
    }

    @Test public void missingLegPriceUnwindsTheFilledLeg() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        // Kill the book on the 26200 CE so the second leg cannot be priced.
        long t0 = TestUtil.ist("2026-11-24T10:00");
        e.quote(TestUtil.id(OptionQuote.Right.CE, 26200)).bid = 0;
        e.quote(TestUtil.id(OptionQuote.Right.CE, 26200)).ask = 0;
        long t = closeBar(e, 26000);
        // Builder rejects legs without a book, so nothing should be sent at all.
        for (int i = 0; i < 3; i++) e.onTimer(t + i * 1000);
        assertNull(e.position());
        assertFalse(e.busy());
        assertTrue(rec.all(), rec.decisions.get(rec.decisions.size() - 1).action.contains("no live bid/ask"));

        // Now: book present at planning, but vanishes after the first leg fills.
        e.quote(TestUtil.id(OptionQuote.Right.CE, 26200)).bid = 59.5;
        e.quote(TestUtil.id(OptionQuote.Right.CE, 26200)).ask = 60.5;
        Engine e2 = e;
        long t1 = t + 10 * 60_000;
        e2.onSpot(26000, t1);
        e2.onSpot(26000, t1 + 5 * 60_000);     // close another bar → entry starts, first leg fills
        OptionQuote sell = e2.quote(TestUtil.id(OptionQuote.Right.CE, 26200));
        assertTrue(rec.all(), e2.busy() || e2.position() != null);
        if (e2.position() == null) {
            sell.bid = 0; sell.ask = 0;
            long t3 = t1 + 5 * 60_000;
            for (int i = 1; i <= 40; i++) e2.onTimer(t3 + i * 1000);   // >30s without a price → abort + unwind
            assertNull(rec.all(), e2.position());
            assertFalse(rec.all(), e2.busy());
            assertTrue(rec.all(), rec.alerts.stream().anyMatch(a -> a.contains("Half-built entry closed")));
        }
    }

    @Test public void intradaySquareOffAt1515() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        long t = closeBar(e, 26000);
        for (int i = 0; i < 5; i++) e.onTimer(t + i * 1000);
        assertNotNull(e.position());
        long late = TestUtil.ist("2026-11-24T15:15") + 2_000;
        TestUtil.reprice(e, 26010, late);
        e.onSpot(26010, late);
        for (int i = 0; i < 6; i++) e.onTimer(late + i * 1000);
        assertNull(rec.all(), e.position());
        assertEquals("TIME", rec.trades.get(0).exitReason);
    }

    @Test public void noEntryOutsideWindowOrAfterLimits() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        e.day().realizedPnl = -c.dailyLossLimit;
        closeBar(e, 26000);
        assertNull(e.position());
        assertTrue(rec.decisions.get(rec.decisions.size() - 1).action.contains("daily loss limit"));
    }

    @Test public void emergencyExitClosesEverything() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        long t = closeBar(e, 26000);
        for (int i = 0; i < 5; i++) e.onTimer(t + i * 1000);
        assertNotNull(e.position());
        e.emergencyExit(t + 6_000);
        for (int i = 7; i < 14; i++) e.onTimer(t + i * 1000);
        assertNull(rec.all(), e.position());
        assertEquals("EMERGENCY", rec.trades.get(0).exitReason);
        assertEquals(BotState.PAUSED, e.state());
    }

    @Test public void restoredWeeklyPositionIsManaged() {
        Config c = new Config();
        c.holding = Config.Holding.WEEKLY;
        Rec rec = new Rec();
        Engine e = engine(c, rec, 11, 6, 26000);
        Position p = new Position();
        p.type = StrategyGrid.Type.BULL_PUT_SPREAD;
        p.legs.add(new Leg(TestUtil.id(OptionQuote.Right.PE, 25500), OptionQuote.Right.PE, 25500, EXP, true, 1, 20));
        p.legs.add(new Leg(TestUtil.id(OptionQuote.Right.PE, 25700), OptionQuote.Right.PE, 25700, EXP, false, 1, 45));
        p.qty = 65;
        p.expiry = EXP;
        p.holding = Config.Holding.WEEKLY;
        p.entryMillis = TestUtil.ist("2026-11-20T11:00");
        p.netPremium = 25; p.maxProfit = 25; p.maxLoss = 175;
        p.targetRs = 0.5 * 25 * 65; p.stopRs = 0.5 * 175 * 65;
        e.restorePosition(p);
        assertTrue(e.neededExpiries().contains(EXP));
        // Monday 30 Nov 15:16 → day before Tuesday expiry → close.
        long mon = TestUtil.ist("2026-11-30T15:16");
        TestUtil.reprice(e, 26000, mon);
        e.onSpot(26000, mon);
        for (int i = 0; i < 6; i++) e.onTimer(mon + i * 1000);
        assertNull(rec.all(), e.position());
        assertTrue(Arrays.asList("EXPIRY_NEAR", "TARGET").contains(rec.trades.get(0).exitReason));
    }

    /** Broker that rejects every SELL during entry: forces the "half-built" unwind path. */
    static final class RejectSells implements Broker {
        final PaperBroker inner;
        boolean active = true;
        RejectSells(PaperBroker inner) { this.inner = inner; }
        public OrderUpdate submit(OrderRequest r) {
            if (active && !r.buy && r.correlationId.startsWith("NRBE")) return new OrderUpdate("R" + r.correlationId, Status.REJECTED, 0, 0, "test rejection");
            return inner.submit(r);
        }
        public OrderUpdate status(String id) { return inner.status(id); }
        public void cancel(String id) { inner.cancel(id); }
        public boolean isLive() { return false; }
    }

    @Test public void rejectedSecondLegUnwindsFirstLeg() {
        Config c = new Config();
        Rec rec = new Rec();
        Engine[] h = new Engine[1];
        RejectSells broker = new RejectSells(new PaperBroker(id -> h[0].quote(id), c.paperSlippage));
        Engine e = new Engine(c, new Charges(), broker, rec);
        h[0] = e;
        long t0 = TestUtil.ist("2026-11-24T10:00");
        e.onTimer(t0);
        List<Double> vix = new ArrayList<>();
        for (int i = 0; i < 250; i++) vix.add(10 + (i % 100) / 10.0);
        e.setVixHistory(vix);
        e.onVix(11);
        e.seedBars(TestUtil.bars(60, 5, t0, 26000 - 360, 6, 2));
        e.setTradeExpiry(EXP);
        TestUtil.loadChain(e, EXP, 26000, t0);
        e.setState(BotState.READY, "t");
        e.startTrading();
        long t = closeBar(e, 26000);
        for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
        assertNull(rec.all(), e.position());
        assertFalse(rec.all(), e.busy());
        assertTrue(rec.all(), rec.alerts.stream().anyMatch(a -> a.contains("Entry stopped part-way")));
        assertTrue(rec.all(), rec.alerts.stream().anyMatch(a -> a.contains("Half-built entry closed")));
        assertTrue("unwind cost is small (bid-ask + charges)", e.day().realizedPnl < 0 && e.day().realizedPnl > -500);
    }
}
