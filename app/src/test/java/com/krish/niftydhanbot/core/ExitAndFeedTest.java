package com.krish.niftydhanbot.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ExitAndFeedTest {
    private final BotConfig cfg = new BotConfig();
    private static final int TEN_AM = 10 * 60;

    private Position pos() { return new Position("1", "NIFTY 25000 CE", Signal.Side.CE, 65, 100, 0, 92, 116); }

    @Test public void stopLossFires() {
        ExitManager x = new ExitManager(cfg);
        Position p = pos();
        x.onQuote(p, 91.5, 1000, 0.6);
        assertEquals(ExitManager.Reason.STOP, x.check(p, 1000, TEN_AM));
    }

    @Test public void breakEvenThenTrailingLetsWinnerRun() {
        ExitManager x = new ExitManager(cfg);
        Position p = pos();
        x.onQuote(p, 108, 1000, 0.6);              // +1R
        assertEquals(100.6, p.stop, 1e-9);         // break-even + costs
        x.onQuote(p, 130, 2000, 0.6);              // well past 2R target: no sell, trail
        assertEquals(ExitManager.Reason.NONE, x.check(p, 2000, TEN_AM));
        assertEquals(130 - 0.8 * 8, p.stop, 1e-9);
        x.onQuote(p, 125, 3000, 0.6);              // pullback doesn't lower the stop
        assertEquals(130 - 0.8 * 8, p.stop, 1e-9);
        x.onQuote(p, 123, 4000, 0.6);
        assertEquals(ExitManager.Reason.TRAIL_STOP, x.check(p, 4000, TEN_AM));
    }

    @Test public void timeExitAndSquareOff() {
        ExitManager x = new ExitManager(cfg);
        Position p = pos();
        x.onQuote(p, 101, 1000, 0.6);
        assertEquals(ExitManager.Reason.NONE, x.check(p, 14 * 60_000L, TEN_AM));
        assertEquals(ExitManager.Reason.TIME, x.check(p, 15 * 60_000L, TEN_AM));
        assertEquals(ExitManager.Reason.SQUARE_OFF, x.check(p, 1000, 15 * 60 + 15));
    }

    @Test public void feedParsesTickerAndFullDepth() {
        ByteBuffer b = ByteBuffer.allocate(16 + 162).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 2).putShort((short) 16).put((byte) 0).putInt(13).putFloat(25012.5f).putInt(0);
        int start = b.position();
        b.put((byte) 8).putShort((short) 162).put((byte) 2).putInt(45678);
        b.putFloat(101.25f);                 // ltp
        b.position(start + 34); b.putInt(123456);  // oi
        b.position(start + 62);
        b.putInt(650).putInt(975).putShort((short) 3).putShort((short) 4).putFloat(101.0f).putFloat(101.5f);
        List<String> got = new ArrayList<>();
        FeedParser.parse(b.array(), new FeedParser.Handler() {
            public void onPrice(int seg, String id, double ltp, double bid, double ask, double oi) {
                got.add(seg + "|" + id + "|" + ltp + "|" + bid + "|" + ask + "|" + oi);
            }
            public void onDisconnect(int r) { got.add("disc " + r); }
            public void onParseError(String m) { got.add("err " + m); }
        });
        assertEquals(2, got.size());
        assertEquals("0|13|25012.5|0.0|0.0|-1.0", got.get(0));
        assertEquals("2|45678|101.25|101.0|101.5|123456.0", got.get(1));
    }

    @Test public void feedReportsShortPacket() {
        List<String> got = new ArrayList<>();
        FeedParser.parse(new byte[]{8, 0, 0, 2, 1, 0, 0, 0, 1, 2}, new FeedParser.Handler() {
            public void onPrice(int s, String i, double l, double b, double a, double o) { got.add("price"); }
            public void onDisconnect(int r) {}
            public void onParseError(String m) { got.add("err"); }
        });
        assertEquals(List.of("err"), got);
    }

    @Test public void jsonRoundTrip() {
        Object o = Json.parse("{\"a\":[1,2.5,\"x\\\"y\"],\"b\":{\"c\":null,\"d\":true},\"e\":\"\\u20b9\"}");
        Map<String, Object> m = Json.obj(o);
        assertEquals(2.5, (Double) Json.arr(m.get("a")).get(1), 0);
        assertEquals("x\"y", Json.arr(m.get("a")).get(2));
        assertEquals("₹", m.get("e"));
        assertEquals(o, Json.parse(Json.write(o)));
        assertEquals("1000000009", Json.str(Json.obj(Json.parse("{\"id\":1000000009}")), "id", ""));
    }
}
