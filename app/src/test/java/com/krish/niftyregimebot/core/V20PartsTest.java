package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.time.LocalDate;

import org.junit.Test;

/** v2.0: the split-out parts work on their own. */
public class V20PartsTest {

    @Test public void ledgerCarriesWeekAndMonthTotals() {
        DayStats mon = new DayStats();
        mon.date = "2026-11-23";
        mon.realizedPnl = 1500;
        mon.weekPnlBefore = 0;
        mon.monthPnlBefore = 4000;
        DayStats wed = Ledger.nextDay(mon, "2026-11-25", LocalDate.parse("2026-11-25"));
        assertEquals(1500, wed.weekPnlBefore, 0.001);
        assertEquals(5500, wed.monthPnlBefore, 0.001);
        DayStats nextMonth = Ledger.nextDay(mon, "2026-12-01", LocalDate.parse("2026-12-01"));
        assertEquals(0, nextMonth.weekPnlBefore, 0.001);
        assertEquals(0, nextMonth.monthPnlBefore, 0.001);
    }

    @Test public void ledgerRecordsWinsAndLossStreaks() {
        DayStats d = new DayStats();
        Ledger.record(d, -100, 1);
        Ledger.record(d, -50, 2);
        assertEquals(2, d.consecutiveLosses);
        Ledger.record(d, 300, 3);
        assertEquals(0, d.consecutiveLosses);
        assertEquals(1, d.wins);
        assertEquals(150, d.realizedPnl, 0.001);
        assertEquals(3, d.lastExitMillis);
    }

    @Test public void gateWaitsLongerAfterALoss() {
        Config c = new Config();
        EntryGate g = new EntryGate(c);
        long t = TestUtil.ist("2026-11-25T11:00");
        DayStats d = new DayStats();
        d.lastExitMillis = t - 5 * 60_000L;
        assertEquals("cooling down after last exit", g.limits(t, t, d, "LOSS"));
        assertNull(g.limits(t, t, d, "WIN"));
        assertNull(g.limits(t, t, d, "FLIP"));
        assertEquals("outside entry window", g.limits(TestUtil.ist("2026-11-25T09:30"), t, new DayStats(), ""));
    }

    @Test public void hardStopUsesTheCloseNowValue() {
        Config c = new Config();
        ExitRules r = new ExitRules(c, new EngineFlowTest.Rec());
        Position p = new Position();
        p.type = StrategyGrid.Type.BULL_CALL_SPREAD;
        p.holding = Config.Holding.INTRADAY;
        p.qty = 65;
        p.legs.add(new Leg("A", OptionQuote.Right.CE, 26000, "2026-12-01", true, 1, 100));
        p.legs.add(new Leg("B", OptionQuote.Right.CE, 26200, "2026-12-01", false, 1, 40));
        p.maxLoss = 60;                 // ₹3,900 for 65 qty
        p.stopRs = 0.5 * p.maxLossRs();
        p.targetRs = 5000;
        long t = TestUtil.ist("2026-11-25T11:00");
        p.entryMillis = t;
        p.fairPnl = -1000;              // fair value still fine
        p.openPnl = -3200;              // close-now loss beyond 80% of ₹3,900
        assertEquals(Engine.ExitReason.STOP, r.check(p, t, 26000));
        p.openPnl = -1000;
        assertNull(r.check(p, t, 26000));
    }
}
