package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** v1.2: holding-aware grid, charges check, fair-value stop. */
public class V12Test {
    @Test public void intradayUsesDebitStrategiesOnly() {
        Config c = new Config();
        c.holding = Config.Holding.INTRADAY;
        assertEquals(StrategyGrid.Type.BULL_CALL_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.UP, IvGauge.Level.HIGH, c));
        assertEquals(StrategyGrid.Type.BEAR_PUT_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.DOWN, IvGauge.Level.HIGH, c));
        assertEquals(StrategyGrid.Type.NONE, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.HIGH, c));
        assertEquals(StrategyGrid.Type.NONE, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.LOW, c));
        c.intradayButterfly = true;
        assertEquals(StrategyGrid.Type.LONG_CALL_BUTTERFLY, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.NORMAL, c));
    }

    @Test public void weeklyKeepsTheFullGrid() {
        Config c = new Config();
        c.holding = Config.Holding.WEEKLY;
        assertEquals(StrategyGrid.Type.BULL_PUT_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.UP, IvGauge.Level.HIGH, c));
        assertEquals(StrategyGrid.Type.IRON_CONDOR, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.NORMAL, c));
        assertEquals(StrategyGrid.Type.LONG_CALL_BUTTERFLY, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.LOW, c));
    }

    @Test public void tinyTargetVersusChargesIsSkipped() {
        Config c = TestUtil.cfg();
        c.minTargetToCharges = 1000;                   // impossible bar → must skip
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        long t = EngineFlowTest.closeBar(e, 26000);
        for (int i = 0; i < 6; i++) e.onTimer(t + i * 1000);
        assertNull(e.position());
        Engine.Decision d = rec.decisions.get(rec.decisions.size() - 1);
        assertTrue(d.action, d.action.contains("charges"));
    }

    private static OptionQuote q(String id, double bid, double ask) {
        OptionQuote q = new OptionQuote(id, OptionQuote.Right.CE, 26000, "2026-12-01");
        q.bid = bid; q.ask = ask; q.ltp = (bid + ask) / 2;
        return q;
    }

    @Test public void wideQuoteUsesMidForFairValue() {
        Position p = new Position();
        p.qty = 65;
        p.legs.add(new Leg("A", OptionQuote.Right.CE, 26000, "2026-12-01", true, 1, 100));
        p.legs.add(new Leg("B", OptionQuote.Right.CE, 26200, "2026-12-01", false, 1, 40));
        Map<String, OptionQuote> m = new HashMap<>();
        m.put("A", q("A", 99.5, 100.5));               // tight: bid used
        m.put("B", q("B", 36, 48));                    // wide (12 on mid 42): mid used for fair value
        double closeNow = p.markToMarket(m);
        double fair = p.fairMark(m, 0.05);
        assertEquals((99.5 - 100) * 65 + (40 - 48) * 65, closeNow, 0.001);
        assertEquals((99.5 - 100) * 65 + (40 - 42) * 65, fair, 0.001);
        assertTrue(fair > closeNow);
    }
}
