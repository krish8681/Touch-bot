package com.krish.niftydhanbot.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes Dhan v2 live market feed binary packets (little-endian).
 * Header (8 bytes): code(1) | length(2) | exchangeSegment(1) | securityId(4).
 * Layouts follow Dhan's live-market-feed docs; verify against a real session before trusting
 * depth prices (the Log screen shows raw parse errors if a layout is off).
 */
public final class FeedParser {
    public static final int TICKER = 2, QUOTE = 4, OI = 5, PREV_CLOSE = 6, MARKET_STATUS = 7, FULL = 8, DISCONNECT = 50;

    /** Exchange segment numbers used in the binary header. */
    public static final int SEG_IDX_I = 0, SEG_NSE_EQ = 1, SEG_NSE_FNO = 2;

    public interface Handler {
        void onPrice(int segment, String securityId, double ltp, double bid, double ask, double oi);
        void onDisconnect(int reasonCode);
        void onParseError(String message);
    }

    static int fixedSize(int code) {
        switch (code) {
            case TICKER: return 16;
            case QUOTE: return 50;
            case OI: return 12;
            case PREV_CLOSE: return 16;
            case MARKET_STATUS: return 8;
            case FULL: return 162;
            case DISCONNECT: return 10;
            default: return -1;
        }
    }

    public static void parse(byte[] data, Handler h) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        while (data.length - pos >= 8) {
            int code = b.get(pos) & 0xFF;
            int declared = b.getShort(pos + 1) & 0xFFFF;
            int seg = b.get(pos + 3) & 0xFF;
            String sid = Long.toString(b.getInt(pos + 4) & 0xFFFFFFFFL);
            int size = fixedSize(code);
            if (size < 0) {
                if (declared >= 8 && pos + declared <= data.length) { pos += declared; continue; }
                h.onParseError("unknown packet code " + code);
                return;
            }
            if (pos + size > data.length) {
                h.onParseError("short packet code " + code + " (" + (data.length - pos) + " < " + size + ")");
                return;
            }
            switch (code) {
                case TICKER:
                    h.onPrice(seg, sid, b.getFloat(pos + 8), 0, 0, -1);
                    break;
                case QUOTE:
                    h.onPrice(seg, sid, b.getFloat(pos + 8), 0, 0, -1);
                    break;
                case OI:
                    h.onPrice(seg, sid, 0, 0, 0, b.getInt(pos + 8));
                    break;
                case FULL: {
                    double ltp = b.getFloat(pos + 8);
                    double oi = b.getInt(pos + 34);
                    int d = pos + 62;  // first (best) depth level
                    double bid = b.getFloat(d + 12);
                    double ask = b.getFloat(d + 16);
                    h.onPrice(seg, sid, ltp, bid, ask, oi);
                    break;
                }
                case DISCONNECT:
                    h.onDisconnect(b.getShort(pos + 8));
                    break;
                default:
                    break; // prev close / market status not needed
            }
            pos += size;
        }
    }
}
