package com.krish.niftyregimebot.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** v1.1 data fixes: Dhan bar sync, incomplete bars, holidays, depth-based paper fills, depth parsing. */
public class DataFixesTest {

    private static Candle bar(long start, double o, double h, double l, double c) { return new Candle(start, o, h, l, c); }

    @Test public void closedBarWaitsForDhanCopyAndUsesIt() {
        Config c = new Config();                       // default: wait for Dhan's bar
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        int before = rec.decisions.size();
        long t = EngineFlowTest.closeBar(e, 26000);
        e.onTimer(t);
        assertEquals("no decision until Dhan's bar arrives", before, rec.decisions.size());
        long barStart = TestUtil.ist("2026-11-25T10:00");
        assertEquals(barStart, e.pendingBarStart());

        e.onDhanBars(Arrays.asList(bar(barStart, 25995, 26020, 25990, 26005)));
        assertEquals(0, e.pendingBarStart());
        assertEquals(before + 1, rec.decisions.size());
        Candle last = e.bars().get(e.bars().size() - 1);
        assertEquals(26005, last.close, 0.001);
        assertEquals(26020, last.high, 0.001);
    }

    @Test public void incompleteOwnBarBlocksEntryWhenDhanCopyMissing() {
        Config c = new Config();
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        long start = TestUtil.ist("2026-11-25T10:00");
        e.onSpot(26000, start + 3 * 60_000);             // bot first saw this bar 3 minutes late
        e.onSpot(26000, start + 5 * 60_000 + 500);       // next bar starts → 10:00 bar closes
        long t = start + 5 * 60_000 + 500;
        for (int i = 0; i <= 61; i++) e.onTimer(t + i * 1000L);
        assertEquals(0, e.pendingBarStart());
        Engine.Decision d = rec.decisions.get(rec.decisions.size() - 1);
        assertTrue(d.action, d.action.contains("incomplete"));
        assertNull("no entry on an incomplete bar", e.position());
    }

    @Test public void completeOwnBarIsUsedAfterTheWait() {
        Config c = new Config();
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        long t = EngineFlowTest.closeBar(e, 26000);      // first tick 1s into the bar = complete
        for (int i = 0; i <= 70; i++) { e.onSpot(26000, t + i * 1000L); e.onTimer(t + i * 1000L); }
        assertNotNull(rec.all(), e.position());
        assertEquals(StrategyGrid.Type.BULL_CALL_SPREAD, e.position().type);
    }

    @Test public void dhanBarsCorrectOldBarsAndFillGaps() {
        Config c = TestUtil.cfg();
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        List<Candle> bars = e.bars();
        int n = bars.size();
        Candle wrong = bars.get(n - 3);
        Candle removed = bars.remove(n - 2);            // pretend the feed missed this bar
        List<Candle> official = new ArrayList<>();
        official.add(bar(wrong.startMillis, wrong.open, wrong.high + 5, wrong.low, wrong.close));
        official.add(bar(removed.startMillis, removed.open, removed.high, removed.low, removed.close));
        e.onDhanBars(official);
        assertEquals(n, e.bars().size());
        assertEquals(wrong.high + 5, e.bars().get(n - 3).high, 0.001);
        assertEquals(removed.startMillis, e.bars().get(n - 2).startMillis);
        assertEquals(2, e.barsFixed());
    }

    @Test public void misalignedDhanBarsAreIgnored() {
        Config c = TestUtil.cfg();
        EngineFlowTest.Rec rec = new EngineFlowTest.Rec();
        Engine e = EngineFlowTest.engine(c, rec, 11, 6, 26000);
        int n = e.bars().size();
        long odd = e.bars().get(n - 2).startMillis + 60_000;   // 09:16-style start, not on our 5-min grid
        e.onDhanBars(Arrays.asList(bar(odd, 1, 2, 0.5, 1.5)));
        assertEquals(n, e.bars().size());
        assertEquals(0, e.barsFixed());
    }

    @Test public void holidaysMoveTheWeeklyExitDay() {
        assertFalse(MarketClock.tradingDay(java.time.LocalDate.parse("2026-11-24")));   // Guru Nanak Jayanti
        assertTrue(MarketClock.tradingDay(java.time.LocalDate.parse("2026-11-23")));
        // Expiry Wed 25 Nov: the day before (24th) is a holiday, so exit on Mon 23rd from 15:15.
        assertFalse(MarketClock.weeklyExitDue("2026-11-25", TestUtil.ist("2026-11-23T15:10"), 15 * 60 + 15));
        assertTrue(MarketClock.weeklyExitDue("2026-11-25", TestUtil.ist("2026-11-23T15:16"), 15 * 60 + 15));
        assertTrue(MarketClock.weeklyExitDue("2026-11-25", TestUtil.ist("2026-11-24T10:00"), 15 * 60 + 15));
        assertFalse(MarketClock.sessionOpen(TestUtil.ist("2026-11-24T10:00")));
        assertTrue(MarketClock.sessionOpen(TestUtil.ist("2026-11-25T10:00")));
    }

    @Test public void userHolidaysAreAdded() {
        try {
            MarketClock.setExtraHolidays(Arrays.asList("2027-01-26", "junk"));
            assertFalse(MarketClock.tradingDay(java.time.LocalDate.parse("2027-01-26")));
            assertFalse(MarketClock.holidayListOutdated(java.time.LocalDate.parse("2027-03-01")));
        } finally {
            MarketClock.setExtraHolidays(null);
        }
        assertTrue(MarketClock.holidayListOutdated(java.time.LocalDate.parse("2027-03-01")));
    }

    @Test public void paperBuyFillsOnlyWhatDepthShows() {
        OptionQuote q = new OptionQuote("X", OptionQuote.Right.CE, 26000, "2026-12-01");
        q.bid = 99.5; q.ask = 100;
        long[] now = {1_000_000};
        q.setDepth(new double[]{99.5, 99}, new int[]{65, 65}, new double[]{100, 100.5, 101}, new int[]{65, 65, 1000}, now[0]);
        PaperBroker b = new PaperBroker(id -> q, 0.5, 10_000, () -> now[0]);

        Broker.OrderUpdate u = b.submit(new Broker.OrderRequest("C1", "X", "X", true, 195, 100.6));
        assertEquals(Broker.Status.PART_TRADED, u.status);
        assertEquals(130, u.filledQty);                 // 101 is above the limit
        assertEquals(PaperBroker.round((65 * 100.5 + 65 * 100.6) / 130), u.avgPrice, 0.001);

        now[0] += 1000;
        assertEquals(130, b.status("C1").filledQty);    // same depth snapshot is not reused

        q.setDepth(new double[]{99.5}, new int[]{65}, new double[]{100}, new int[]{500}, now[0]);
        Broker.OrderUpdate done = b.status("C1");
        assertEquals(Broker.Status.TRADED, done.status);
        assertEquals(195, done.filledQty);
    }

    @Test public void paperCancelKeepsPartFill() {
        OptionQuote q = new OptionQuote("Y", OptionQuote.Right.PE, 26000, "2026-12-01");
        q.bid = 50; q.ask = 50.5;
        long[] now = {5_000};
        q.setDepth(new double[]{50, 49.5}, new int[]{30, 40}, new double[]{50.5}, new int[]{100}, now[0]);
        PaperBroker b = new PaperBroker(id -> q, 0.5, 10_000, () -> now[0]);
        Broker.OrderUpdate u = b.submit(new Broker.OrderRequest("S1", "Y", "Y", false, 130, 49.5));
        assertEquals(70, u.filledQty);
        b.cancel(u.orderId);
        Broker.OrderUpdate c = b.status("S1");
        assertEquals(Broker.Status.CANCELLED, c.status);
        assertEquals(70, c.filledQty);
    }

    @Test public void paperWithoutDepthFillsFully() {
        OptionQuote q = new OptionQuote("Z", OptionQuote.Right.CE, 26000, "2026-12-01");
        q.bid = 10; q.ask = 10.5;
        PaperBroker b = new PaperBroker(id -> q, 0.5, 10_000, () -> 1L);
        Broker.OrderUpdate u = b.submit(new Broker.OrderRequest("B1", "Z", "Z", true, 650, 12));
        assertEquals(Broker.Status.TRADED, u.status);
        assertEquals(650, u.filledQty);
        assertEquals(11.0, u.avgPrice, 0.001);
    }

    @Test public void feedParserReadsFiveDepthLevels() {
        ByteBuffer b = ByteBuffer.allocate(162).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) FeedParser.FULL);
        b.putShort(1, (short) 162);
        b.put(3, (byte) FeedParser.SEG_NSE_FNO);
        b.putInt(4, 43210);
        b.putFloat(8, 101.5f);
        for (int i = 0; i < 5; i++) {
            int at = 62 + i * 20;
            b.putInt(at, 100 * (i + 1));
            b.putInt(at + 4, 200 * (i + 1));
            b.putFloat(at + 12, 101f - i);
            b.putFloat(at + 16, 102f + i);
        }
        final double[][] got = new double[2][];
        final int[][] q = new int[2][];
        FeedParser.parse(b.array(), new FeedParser.Handler() {
            public void onPrice(int s, String id, double ltp, double bid, double ask, double oi) {}
            public void onDisconnect(int r) {}
            public void onParseError(String m) { fail(m); }
            public void onDepth(int s, String id, double[] bp, int[] bq, double[] ap, int[] aq) {
                assertEquals("43210", id);
                got[0] = bp; got[1] = ap; q[0] = bq; q[1] = aq;
            }
        });
        assertEquals(101, got[0][0], 0.001);
        assertEquals(97, got[0][4], 0.001);
        assertEquals(106, got[1][4], 0.001);
        assertEquals(500, q[0][4]);
        assertEquals(200, q[1][0]);
    }
}
