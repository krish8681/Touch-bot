package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Payoff numbers are checked against the worked NIFTY examples in the strategy guide. */
public class StrategyMathTest {

    static Leg leg(double strike, OptionQuote.Right r, boolean buy, int ratio, double px) {
        return new Leg("id" + strike + r + buy, r, strike, "2026-12-01", buy, ratio, px);
    }

    @Test public void bullCallSpreadMatchesGuide() {
        // Buy 26000 CE @150, sell 26200 CE @60 → debit 90, max profit 110, max loss 90, BE 26090
        Payoff p = Payoff.of(Arrays.asList(leg(26000, OptionQuote.Right.CE, true, 1, 150), leg(26200, OptionQuote.Right.CE, false, 1, 60)));
        assertEquals(-90, p.netPremium, 1e-9);
        assertEquals(110, p.maxProfit, 1e-9);
        assertEquals(90, p.maxLoss, 1e-9);
        assertEquals(Arrays.asList(26090.0), p.breakevens);
    }

    @Test public void bullPutSpreadMatchesGuide() {
        // Sell 25900 PE @80, buy 25700 PE @30 → credit 50, max loss 150, BE 25850
        Payoff p = Payoff.of(Arrays.asList(leg(25700, OptionQuote.Right.PE, true, 1, 30), leg(25900, OptionQuote.Right.PE, false, 1, 80)));
        assertEquals(50, p.netPremium, 1e-9);
        assertEquals(50, p.maxProfit, 1e-9);
        assertEquals(150, p.maxLoss, 1e-9);
        assertEquals(Arrays.asList(25850.0), p.breakevens);
    }

    @Test public void ironCondorMatchesGuide() {
        // Credit 73, max loss 127, breakevens 25727 / 26273
        Payoff p = Payoff.of(Arrays.asList(
                leg(25600, OptionQuote.Right.PE, true, 1, 20), leg(26400, OptionQuote.Right.CE, true, 1, 22),
                leg(25800, OptionQuote.Right.PE, false, 1, 55), leg(26200, OptionQuote.Right.CE, false, 1, 60)));
        assertEquals(73, p.netPremium, 1e-9);
        assertEquals(73, p.maxProfit, 1e-9);
        assertEquals(127, p.maxLoss, 1e-9);
        assertEquals(Arrays.asList(25727.0, 26273.0), p.breakevens);
    }

    @Test public void butterflyMatchesGuide() {
        // Buy 25800 CE @260, sell 2× 26000 CE @150, buy 26200 CE @60 → debit 20, max 180, BE 25820/26180
        Payoff p = Payoff.of(Arrays.asList(
                leg(25800, OptionQuote.Right.CE, true, 1, 260), leg(26200, OptionQuote.Right.CE, true, 1, 60),
                leg(26000, OptionQuote.Right.CE, false, 2, 150)));
        assertEquals(-20, p.netPremium, 1e-9);
        assertEquals(180, p.maxProfit, 1e-9);
        assertEquals(20, p.maxLoss, 1e-9);
        assertEquals(Arrays.asList(25820.0, 26180.0), p.breakevens);
    }

    @Test public void nakedShortCallIsUnlimitedLoss() {
        Payoff p = Payoff.of(Arrays.asList(leg(26000, OptionQuote.Right.CE, false, 1, 150)));
        assertTrue(p.unlimitedLoss());
    }

    @Test public void gridFollowsGuideSection8() {
        assertEquals(StrategyGrid.Type.BULL_CALL_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.UP, IvGauge.Level.LOW));
        assertEquals(StrategyGrid.Type.BULL_CALL_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.UP, IvGauge.Level.NORMAL));
        assertEquals(StrategyGrid.Type.BULL_PUT_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.UP, IvGauge.Level.HIGH));
        assertEquals(StrategyGrid.Type.BEAR_PUT_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.DOWN, IvGauge.Level.NORMAL));
        assertEquals(StrategyGrid.Type.BEAR_CALL_SPREAD, StrategyGrid.pick(RegimeDetector.Regime.DOWN, IvGauge.Level.HIGH));
        assertEquals(StrategyGrid.Type.IRON_CONDOR, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.HIGH));
        assertEquals(StrategyGrid.Type.LONG_CALL_BUTTERFLY, StrategyGrid.pick(RegimeDetector.Regime.SIDEWAYS, IvGauge.Level.LOW));
        assertEquals(StrategyGrid.Type.NONE, StrategyGrid.pick(RegimeDetector.Regime.UNKNOWN, IvGauge.Level.LOW));
    }

    @Test public void regimeFlipRules() {
        Config c = new Config();
        assertTrue(StrategyGrid.regimeAgainst(StrategyGrid.Type.BULL_CALL_SPREAD, RegimeDetector.Regime.DOWN, 30, c));
        assertFalse(StrategyGrid.regimeAgainst(StrategyGrid.Type.BULL_CALL_SPREAD, RegimeDetector.Regime.SIDEWAYS, 30, c));
        assertTrue(StrategyGrid.regimeAgainst(StrategyGrid.Type.IRON_CONDOR, RegimeDetector.Regime.UP, 30, c));
        assertFalse(StrategyGrid.regimeAgainst(StrategyGrid.Type.IRON_CONDOR, RegimeDetector.Regime.UP, 21, c));
    }

    @Test public void ivGaugeUsesVixPercentile() {
        Config c = new Config();
        IvGauge g = new IvGauge(c);
        List<Double> h = new ArrayList<>();
        for (int i = 0; i < 250; i++) h.add(10 + (i % 100) / 10.0);   // 10.0 … 19.9
        g.setVixHistory(h);
        g.onVix(11);
        assertEquals(IvGauge.Level.LOW, g.read(0).level);
        g.onVix(18.5);
        assertEquals(IvGauge.Level.HIGH, g.read(0).level);
        IvGauge empty = new IvGauge(c);
        assertEquals(IvGauge.Level.HIGH, empty.read(17).level);       // fallback: ATM IV
        assertEquals(IvGauge.Level.UNKNOWN, empty.read(0).level);
    }

    @Test public void expiryChoiceSkipsTodayAndShortWeeks() {
        List<String> e = Arrays.asList("2026-12-01", "2026-11-24", "2026-12-08");
        LocalDate tue = LocalDate.parse("2026-11-24");
        assertEquals("2026-12-01", MarketClock.chooseExpiry(e, tue, 1));       // never trade today's expiry
        LocalDate fri = LocalDate.parse("2026-11-27");
        assertEquals("2026-12-01", MarketClock.chooseExpiry(e, fri, 3));
        LocalDate sat = LocalDate.parse("2026-11-28");
        assertEquals("2026-12-08", MarketClock.chooseExpiry(e, sat, 4));
    }

    @Test public void weeklyExitTheDayBeforeExpiry() {
        long monday1510 = TestUtil.ist("2026-11-30T15:10");
        long monday1516 = TestUtil.ist("2026-11-30T15:16");
        long friday = TestUtil.ist("2026-11-27T15:20");
        assertFalse(MarketClock.weeklyExitDue("2026-12-01", monday1510, 15 * 60 + 15));
        assertTrue(MarketClock.weeklyExitDue("2026-12-01", monday1516, 15 * 60 + 15));
        assertFalse(MarketClock.weeklyExitDue("2026-12-01", friday, 15 * 60 + 15));
        assertTrue(MarketClock.weeklyExitDue("2026-12-01", TestUtil.ist("2026-12-01T09:20"), 15 * 60 + 15));
    }

    @Test public void sizerUsesMaxLoss() {
        Config c = new Config();
        c.riskPerTrade = 20_000;
        StrategyBuilder.Plan p = new StrategyBuilder.Plan();
        p.legs.add(leg(25600, OptionQuote.Right.PE, true, 1, 20));
        p.legs.add(leg(26400, OptionQuote.Right.CE, true, 1, 22));
        p.legs.add(leg(25800, OptionQuote.Right.PE, false, 1, 55));
        p.legs.add(leg(26200, OptionQuote.Right.CE, false, 1, 60));
        p.payoff = Payoff.of(p.legs);
        Sizer.Result r = new Sizer(c, new Charges()).size(p, c.riskPerTrade, 300_000);
        // max loss 127 × 65 = ₹8,255 per lot (+ charges) → 2 lots within ₹20,000
        assertEquals(2, r.lots);
        assertEquals(130, r.qty);
        assertEquals(16_510, r.maxLossRs, 1e-6);
        Sizer.Result tooSmall = new Sizer(c, new Charges()).size(p, 5_000, 300_000);
        assertFalse(tooSmall.ok());
    }

    @Test public void positionSurvivesSaveAndLoad() {
        Position p = new Position();
        p.type = StrategyGrid.Type.IRON_CONDOR;
        p.legs.add(leg(25800, OptionQuote.Right.PE, false, 1, 55));
        p.legs.add(leg(25600, OptionQuote.Right.PE, true, 1, 20));
        p.qty = 130;
        p.expiry = "2026-12-01";
        p.holding = Config.Holding.WEEKLY;
        p.maxProfit = 73; p.maxLoss = 127; p.targetRs = 4745; p.stopRs = 8255;
        Position q = Position.fromMap(Json.obj(Json.parse(Json.write(p.toMap()))));
        assertEquals(p.type, q.type);
        assertEquals(2, q.legs.size());
        assertFalse(q.legs.get(0).buy);
        assertTrue(q.legs.get(1).buy);
        assertEquals(55, q.legs.get(0).price, 1e-9);
        assertEquals(Config.Holding.WEEKLY, q.holding);
        assertEquals(4745, q.targetRs, 1e-9);
    }
}
