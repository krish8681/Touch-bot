package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.Random;

import org.junit.Test;

/** Backtester: runs the real engine over synthetic history without crashing, and expiry dates are right. */
public class BacktesterTest {

    @Test public void expiryDatesFollowTuesdaysAndHolidays() {
        assertEquals("2026-12-01", Backtester.expiryFor(LocalDate.parse("2026-11-25"), 1, false));
        assertEquals("2026-12-08", Backtester.expiryFor(LocalDate.parse("2026-11-25"), 2, false));
        // 24 Nov 2026 (Tue) is a holiday → that week's expiry is Mon 23 Nov
        assertEquals("2026-11-23", Backtester.expiryFor(LocalDate.parse("2026-11-20"), 1, false));
        assertEquals("2026-12-01", Backtester.expiryFor(LocalDate.parse("2026-11-20"), 2, false));
        assertEquals("2026-11-23", Backtester.expiryFor(LocalDate.parse("2026-11-23"), 1, false));
        assertEquals("2026-12-01", Backtester.expiryFor(LocalDate.parse("2026-11-23"), 1, true));
        // before Sep 2025 expiries were Thursdays
        assertEquals("2025-08-28", Backtester.expiryFor(LocalDate.parse("2025-08-25"), 1, false));
    }

    @Test public void deltaLooksSane() {
        double atm = Backtester.delta(OptionQuote.Right.CE, 26000, 26000, 13, 5 / 365.0);
        assertEquals(0.5, atm, 0.03);
        assertTrue(Backtester.delta(OptionQuote.Right.PE, 26000, 25500, 13, 5 / 365.0) > -0.2);
    }

    private static double bs(boolean call, double s, double k, double sig, double t) {
        if (t <= 0) return Math.max(0, call ? s - k : k - s);
        double d1 = (Math.log(s / k) + 0.5 * sig * sig * t) / (sig * Math.sqrt(t)), d2 = d1 - sig * Math.sqrt(t);
        return call ? s * n(d1) - k * n(d2) : k * n(-d2) - s * n(-d1);
    }

    private static double n(double x) { return 0.5 * (1 + erf(x / Math.sqrt(2))); }

    private static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    private static Backtester.Data synthetic(long seed) {
        Backtester.Data d = new Backtester.Data();
        Random rnd = new Random(seed);
        LocalDate v = LocalDate.parse("2025-09-01");
        for (int i = 0; i < 300; i++, v = v.plusDays(1)) if (MarketClock.weekday(v)) d.vixCloses.put(v, 12 + 4 * rnd.nextDouble());
        double spot = 26000;
        // warmup: 3 earlier trading days of 5-min bars
        LocalDate day = LocalDate.parse("2026-10-27");
        for (int k = 0; k < 3; k++, day = MarketClock.nextTradingDay(day.plusDays(1))) {
            for (int b = 0; b < 75; b++) {
                long t = day.atTime(9, 15).plusMinutes(5L * b).atZone(MarketClock.IST).toInstant().toEpochMilli();
                double o = spot, c = spot + (rnd.nextGaussian() * 6);
                d.warmup.add(new Candle(t, o, Math.max(o, c) + 3, Math.min(o, c) - 3, c));
                spot = c;
            }
        }
        day = LocalDate.parse("2026-11-02");
        for (int k = 0; k < 14; k++, day = MarketClock.nextTradingDay(day.plusDays(1))) {
            double drift = (k % 3 == 0 ? 6 : k % 3 == 1 ? -6 : 0);
            String near = Backtester.expiryFor(day, 1, false), next = Backtester.expiryFor(day, 2, false);
            for (int b = 0; b < 75; b++) {
                long t = day.atTime(9, 15).plusMinutes(5L * b).atZone(MarketClock.IST).toInstant().toEpochMilli();
                double o = spot, c = spot + drift + rnd.nextGaussian() * 8;
                d.spot5.add(new Candle(t, o, Math.max(o, c) + 4, Math.min(o, c) - 4, c));
                double atm = Math.round(o / 50) * 50;
                for (int code = 1; code <= 2; code++) {
                    String exp = code == 1 ? near : next;
                    long expMs = LocalDate.parse(exp).atTime(15, 30).atZone(MarketClock.IST).toInstant().toEpochMilli();
                    int w = code == 1 ? 10 : 3;
                    for (int off = -w; off <= w; off++) {
                        double kx = atm + 50 * off;
                        double to = (expMs - t) / (365.0 * 86400000), tc = (expMs - t - 300_000) / (365.0 * 86400000);
                        for (int side = 0; side < 2; side++) {
                            boolean call = side == 0;
                            double po = bs(call, o, kx, 0.13, to), pc = bs(call, c, kx, 0.13, tc);
                            if (pc < 0.05) continue;
                            d.options.add(new Backtester.OptBar(t, code, call ? OptionQuote.Right.CE : OptionQuote.Right.PE,
                                    kx, Math.max(0.05, po), pc, 13));
                        }
                    }
                }
                spot = c;
            }
        }
        return d;
    }

    @Test public void intradayBacktestRunsAndTrades() {
        Config c = new Config();
        c.holding = Config.Holding.INTRADAY;
        c.riskPerTrade = 20_000;
        Backtester.Report r = new Backtester().run(c, synthetic(7), null);
        assertTrue(r.text(), r.tradingDaysWithData >= 13);
        assertTrue(r.text() + r.skipReasons, r.trades.size() > 0);
        for (TradeRecord t : r.trades) {
            assertTrue("intraday never uses credit trades", !t.strategy.contains("put spread") || t.strategy.startsWith("Bear"));
            assertEquals(MarketClock.date(t.entryMillis), MarketClock.date(t.exitMillis));
            assertTrue("loss never beyond the per-trade budget", t.net > -c.riskPerTrade * 1.2);
        }
        assertFalse(Double.isNaN(r.net));
        assertEquals(r.wins + r.losses, r.trades.size());
        assertTrue(r.missingQuotePct < 1);
    }

    @Test public void weeklyBacktestRuns() {
        Config c = new Config();
        c.holding = Config.Holding.WEEKLY;
        c.weeklyBarMinutes = 15;     // short warmup in this synthetic set
        c.riskPerTrade = 20_000;
        Backtester.Report r = new Backtester().run(c, synthetic(11), null);
        assertTrue(r.tradingDaysWithData >= 13);
        assertFalse(Double.isNaN(r.net));
        assertNotNull(r.text());
    }
}
