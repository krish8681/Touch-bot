package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.FeedParser;
import com.krish.niftyregimebot.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Dhan live market feed (WebSocket, binary). Subscribes NIFTY index as ticker and option
 * contracts as full packets (for best bid/ask). Reconnects with back-off and re-subscribes.
 */
public final class DhanFeed {
    public interface Callback extends FeedParser.Handler {
        void onConnected();
        void onClosed(String why);
    }

    private static final int REQ_TICKER = 15, REQ_FULL = 21, UNSUB_FULL = 22;

    private final OkHttpClient http;
    private final String url;
    private final Callback cb;
    private final Set<String> options = new LinkedHashSet<>();
    private final List<String> indexIds = new ArrayList<>();
    private WebSocket ws;
    private volatile boolean wanted;
    private int failures;
    private final java.util.concurrent.ScheduledExecutorService retry =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    /** {@code extraIndexIds}: more IDX_I tickers to stream with NIFTY (e.g. India VIX). */
    public DhanFeed(OkHttpClient http, String clientId, String token, Callback cb, String... extraIndexIds) {
        indexIds.add(DhanApi.NIFTY_ID);
        for (String id : extraIndexIds) if (id != null && !id.isEmpty() && !indexIds.contains(id)) indexIds.add(id);
        this.http = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build();
        this.url = "wss://api-feed.dhan.co?version=2&token=" + token + "&clientId=" + clientId + "&authType=2";
        this.cb = cb;
    }

    public synchronized void start() {
        wanted = true;
        connect();
    }

    public synchronized void stop() {
        wanted = false;
        if (ws != null) {
            ws.send("{\"RequestCode\":12}");   // polite disconnect
            ws.close(1000, "bye");
            ws = null;
        }
        retry.shutdownNow();
    }

    /** Replace the set of option contracts we want full depth for. */
    public synchronized void setOptions(Set<String> ids) {
        Set<String> removed = new LinkedHashSet<>(options);
        removed.removeAll(ids);
        Set<String> added = new LinkedHashSet<>(ids);
        added.removeAll(options);
        options.clear();
        options.addAll(ids);
        if (ws != null) {
            if (!removed.isEmpty()) send(UNSUB_FULL, "NSE_FNO", removed);
            if (!added.isEmpty()) send(REQ_FULL, "NSE_FNO", added);
        }
    }

    private synchronized void connect() {
        if (!wanted) return;
        Request r = new Request.Builder().url(url).build();
        ws = http.newWebSocket(r, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response resp) {
                synchronized (DhanFeed.this) {
                    failures = 0;
                    send(REQ_TICKER, "IDX_I", indexIds);
                    if (!options.isEmpty()) send(REQ_FULL, "NSE_FNO", options);
                }
                cb.onConnected();
            }

            @Override public void onMessage(WebSocket s, ByteString bytes) {
                FeedParser.parse(bytes.toByteArray(), cb);
            }

            @Override public void onMessage(WebSocket s, String text) {
                cb.onParseError("text message from feed: " + (text.length() > 200 ? text.substring(0, 200) : text));
            }

            @Override public void onClosed(WebSocket s, int code, String reason) { lost("closed " + code + " " + reason); }

            @Override public void onFailure(WebSocket s, Throwable t, Response resp) {
                lost("failed: " + t.getMessage() + (resp != null ? " (HTTP " + resp.code() + ")" : ""));
            }
        });
    }

    private void lost(String why) {
        cb.onClosed(why);
        synchronized (this) {
            ws = null;
            if (!wanted) return;
            failures++;
            long delay = Math.min(60, (long) Math.pow(2, Math.min(failures, 6)));
            try { retry.schedule(this::connect, delay, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }

    /** Dhan accepts at most 100 instruments per subscribe message. */
    private void send(int code, String segment, java.util.Collection<String> ids) {
        List<String> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 100) {
            List<Object> list = new ArrayList<>();
            for (String id : all.subList(i, Math.min(all.size(), i + 100))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ExchangeSegment", segment);
                m.put("SecurityId", id);
                list.add(m);
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("RequestCode", code);
            msg.put("InstrumentCount", list.size());
            msg.put("InstrumentList", list);
            String json = Json.write(msg);
            if (ws != null) ws.send(json);
        }
    }
}
