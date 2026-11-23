package com.krish.niftydhanbot.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class RiskAndSizingTest {
    private final BotConfig cfg = new BotConfig();
    private final Charges charges = new Charges();

    @Test public void sizingRoundsDownToWholeLotsWithinBudget() {
        PositionSizer.Result r = new PositionSizer(cfg, charges).size(150, 140, 300_000);
        assertTrue(r.lots >= 1);
        assertEquals(0, r.qty % cfg.lotSize);
        assertTrue("worst case " + r.worstCaseLoss, r.worstCaseLoss <= cfg.riskPerTrade);
        // one more lot would break the budget
        assertTrue((r.lots + 1) * cfg.lotSize * r.lossPerUnit > cfg.riskPerTrade);
    }

    @Test public void sizingSaysNoTradeWhenOneLotIsTooRisky() {
        PositionSizer.Result r = new PositionSizer(cfg, charges).size(200, 170, 300_000); // 30-pt stop × 65 > 1500
        assertEquals(0, r.lots);
        assertEquals(0, r.qty);
    }

    @Test public void sizingRespectsFunds() {
        PositionSizer.Result r = new PositionSizer(cfg, charges).size(100, 96, 7_000); // ~1 lot affordable
        assertEquals(1, r.lots);
    }

    @Test public void sizingRejectsBadInput() {
        assertEquals(0, new PositionSizer(cfg, charges).size(100, 100, 300_000).lots);
        assertEquals(0, new PositionSizer(cfg, charges).size(100, 0, 300_000).lots);
    }

    @Test public void chargesArePositiveAndIncludeTwoBrokerages() {
        double c = charges.roundTrip(100, 110, 65);
        assertTrue(c > 40 && c < 70);
    }

    private RiskGate.Request okRequest() {
        RiskGate.Request r = new RiskGate.Request();
        r.state = BotState.TRADING;
        r.day = new DayStats();
        r.now = 1_000_000_000L;
        r.day.lastExitMillis = 0;
        r.minuteOfDayIst = 10 * 60;
        r.lastSpotMillis = r.now;
        OptionQuote q = new OptionQuote("1", Signal.Side.CE, 25000);
        q.bid = 99.5; q.ask = 100; q.updatedMillis = r.now;
        r.quote = q;
        r.size = new PositionSizer(cfg, charges).size(100, 94, 300_000);
        r.availableFunds = 300_000;
        r.limitPrice = 100.5;
        return r;
    }

    @Test public void gateAllowsCleanRequest() {
        RiskGate.Decision d = new RiskGate(cfg).check(okRequest());
        assertTrue(d.text(), d.allowed);
    }

    @Test public void gateBlocksEveryLimit() {
        RiskGate g = new RiskGate(cfg);
        RiskGate.Request r;

        r = okRequest(); r.state = BotState.PAUSED; assertFalse(g.check(r).allowed);
        r = okRequest(); r.day.entries = 3; assertFalse(g.check(r).allowed);
        r = okRequest(); r.openPositions = 1; assertFalse(g.check(r).allowed);
        r = okRequest(); r.day.consecutiveLosses = 2; assertFalse(g.check(r).allowed);
        r = okRequest(); r.day.realizedPnl = -6_000; assertFalse(g.check(r).allowed);
        r = okRequest(); r.day.realizedPnl = -5_500; assertFalse("worst case would cross cap", g.check(r).allowed);
        r = okRequest(); r.day.weekPnlBefore = -12_000; assertFalse(g.check(r).allowed);
        r = okRequest(); r.minuteOfDayIst = 9 * 60 + 16; assertFalse(g.check(r).allowed);
        r = okRequest(); r.minuteOfDayIst = 15 * 60; assertFalse(g.check(r).allowed);
        r = okRequest(); r.lastSpotMillis = r.now - 10_000; assertFalse(g.check(r).allowed);
        r = okRequest(); r.quote.updatedMillis = r.now - 10_000; assertFalse(g.check(r).allowed);
        r = okRequest(); r.pendingOrder = true; assertFalse(g.check(r).allowed);
        r = okRequest(); r.availableFunds = 1_000; assertFalse(g.check(r).allowed);
        r = okRequest(); r.size = null; assertFalse(g.check(r).allowed);
        r = okRequest(); r.day.lastExitMillis = r.now - 60_000; assertFalse("cooldown", g.check(r).allowed);
    }

    @Test public void dailyCapCountsOpenLoss() {
        RiskGate.Request r = okRequest();
        r.day.realizedPnl = -3_000;
        r.openPnl = -3_100;
        assertFalse(new RiskGate(cfg).check(r).allowed);
    }
}
