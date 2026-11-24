package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.Candle;
import com.krish.niftyregimebot.core.BrokerPosition;
import com.krish.niftyregimebot.core.Json;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Dhan REST API v2 (https://api.dhan.co/v2). Read calls only, plus the order calls used
 * by {@link DhanLiveBroker} (which is locked in this version). Blocking — call off the UI thread.
 */
public final class DhanApi {
    public static final String BASE = "https://api.dhan.co/v2";
    public static final String NIFTY_ID = "13";       // NIFTY 50 index in segment IDX_I
    private static final MediaType JSON = MediaType.get("application/json");

    /** HTTP error from Dhan, with the status code (401 usually = token expired). */
    public static final class DhanException extends IOException {
        public final int code;
        public DhanException(int code, String body) {
            super("Dhan HTTP " + code + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
            this.code = code;
        }
    }

    public static final class Funds { public double available, utilized, withdrawable; }

    public static final class ChainRow {
        public String securityId;
        public boolean call;
        public double strike, ltp, bid, ask, delta, iv, oi;
        public String expiry = "";
    }

    public static final class Chain { public double spot; public String expiry = ""; public final List<ChainRow> rows = new ArrayList<>(); }

    private final OkHttpClient http;
    private final String clientId, token;
    private long lastChainMillis;

    public DhanApi(OkHttpClient http, String clientId, String token) {
        this.http = http.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
        this.clientId = clientId;
        this.token = token;
    }

    // ------------------------------------------------------------ account

    public Funds funds() throws IOException {
        Map<String, Object> m = Json.obj(get("/fundlimit"));
        Funds f = new Funds();
        // Dhan spells this field "availabelBalance".
        f.available = Json.num(m, "availabelBalance", Json.num(m, "availableBalance", 0));
        f.utilized = Json.num(m, "utilizedAmount", 0);
        f.withdrawable = Json.num(m, "withdrawableBalance", 0);
        return f;
    }

    /** Open F&O positions (netQty ≠ 0). */
    public List<BrokerPosition> foPositions() throws IOException {
        List<BrokerPosition> out = new ArrayList<>();
        for (Object o : Json.arr(get("/positions"))) {
            Map<String, Object> m = Json.obj(o);
            String seg = Json.str(m, "exchangeSegment", "");
            if (!seg.contains("FNO")) continue;
            int net = (int) Json.num(m, "netQty", 0);
            out.add(new BrokerPosition(Json.str(m, "securityId", ""), Json.str(m, "tradingSymbol", "?"), net));
        }
        return out;
    }

    // ------------------------------------------------------------ market data

    public List<String> expiries() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("UnderlyingScrip", 13);
        body.put("UnderlyingSeg", "IDX_I");
        Map<String, Object> r = Json.obj(post("/optionchain/expirylist", body, true));
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(r.get("data"))) out.add(String.valueOf(o));
        return out;
    }

    /** Option chain for NIFTY. Dhan allows one call per 3 seconds; this waits if needed. */
    public Chain optionChain(String expiry) throws IOException {
        long wait = 3_100 - (System.currentTimeMillis() - lastChainMillis);
        if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); } }
        lastChainMillis = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("UnderlyingScrip", 13);
        body.put("UnderlyingSeg", "IDX_I");
        body.put("Expiry", expiry);
        Map<String, Object> data = Json.obj(Json.obj(post("/optionchain", body, true)).get("data"));
        Chain c = new Chain();
        c.expiry = expiry;
        c.spot = Json.num(data, "last_price", 0);
        for (Map.Entry<String, Object> e : Json.obj(data.get("oc")).entrySet()) {
            double strike;
            try { strike = Double.parseDouble(e.getKey()); } catch (NumberFormatException ex) { continue; }
            Map<String, Object> both = Json.obj(e.getValue());
            addRow(c, strike, Json.obj(both.get("ce")), true);
            addRow(c, strike, Json.obj(both.get("pe")), false);
        }
        for (ChainRow r : c.rows) r.expiry = expiry;
        return c;
    }

    private static void addRow(Chain c, double strike, Map<String, Object> m, boolean call) {
        if (m.isEmpty()) return;
        ChainRow r = new ChainRow();
        r.securityId = Json.str(m, "security_id", "");
        if (r.securityId.isEmpty()) return;  // can't trade or subscribe without an id
        r.call = call;
        r.strike = strike;
        r.ltp = Json.num(m, "last_price", 0);
        r.bid = Json.num(m, "top_bid_price", 0);
        r.ask = Json.num(m, "top_ask_price", 0);
        r.iv = Json.num(m, "implied_volatility", 0);
        r.oi = Json.num(m, "oi", 0);
        r.delta = Json.num(Json.obj(m.get("greeks")), "delta", 0);
        c.rows.add(r);
    }

    /** NIFTY bars of {@code interval} minutes (1, 5, 15, 25 or 60) from {@code from} to now. Skips the still-forming bar. */
    public List<Candle> niftyBars(int interval, LocalDate from, LocalDate to) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", NIFTY_ID);
        body.put("exchangeSegment", "IDX_I");
        body.put("instrument", "INDEX");
        body.put("interval", String.valueOf(interval));
        body.put("oi", false);
        body.put("fromDate", from + " 09:15:00");
        body.put("toDate", to + " 15:30:00");
        Map<String, Object> r = Json.obj(post("/charts/intraday", body, false));
        List<Object> o = Json.arr(r.get("open")), h = Json.arr(r.get("high")), l = Json.arr(r.get("low")),
                c = Json.arr(r.get("close")), t = Json.arr(r.get("timestamp"));
        List<Candle> out = new ArrayList<>();
        int n = Math.min(Math.min(o.size(), h.size()), Math.min(Math.min(l.size(), c.size()), t.size()));
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            long start = (long) (((Number) t.get(i)).doubleValue() * 1000);
            if (start + interval * 60_000L > now) continue;   // skip the still-forming bar
            out.add(new Candle(start, ((Number) o.get(i)).doubleValue(), ((Number) h.get(i)).doubleValue(),
                    ((Number) l.get(i)).doubleValue(), ((Number) c.get(i)).doubleValue()));
        }
        return out;
    }

    /** Daily closes of an index (used for India VIX), oldest first. */
    public List<Double> indexDailyCloses(String securityId, LocalDate from, LocalDate to) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", securityId);
        body.put("exchangeSegment", "IDX_I");
        body.put("instrument", "INDEX");
        body.put("oi", false);
        body.put("fromDate", from.toString());
        body.put("toDate", to.toString());
        Map<String, Object> r = Json.obj(post("/charts/historical", body, false));
        List<Double> out = new ArrayList<>();
        for (Object x : Json.arr(r.get("close"))) if (x instanceof Number) out.add(((Number) x).doubleValue());
        return out;
    }

    // ------------------------------------------------------------ orders (used only when live is unlocked)

    public Map<String, Object> placeOrder(Map<String, Object> body) throws IOException {
        return Json.obj(post("/orders", body, false));
    }

    /** Dhan returns the order (object or one-element list); returns empty map if none. */
    public Map<String, Object> orderByCorrelation(String correlationId) throws IOException {
        try {
            Object o = get("/orders/external/" + correlationId);
            if (o instanceof List) { List<Object> l = Json.arr(o); return l.isEmpty() ? new LinkedHashMap<>() : Json.obj(l.get(0)); }
            return Json.obj(o);
        } catch (DhanException e) {
            if (e.code == 404 || e.code == 400) return new LinkedHashMap<>();
            throw e;
        }
    }

    public void cancelOrder(String orderId) throws IOException {
        Request r = base("/orders/" + orderId, false).delete().build();
        exec(r);
    }

    public String clientId() { return clientId; }

    // ------------------------------------------------------------ plumbing

    private Request.Builder base(String path, boolean needsClientId) {
        Request.Builder b = new Request.Builder()
                .url(BASE + path)
                .header("access-token", token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (needsClientId) b.header("client-id", clientId);
        return b;
    }

    private Object get(String path) throws IOException {
        return exec(base(path, false).get().build());
    }

    private Object post(String path, Map<String, Object> body, boolean needsClientId) throws IOException {
        return exec(base(path, needsClientId).post(RequestBody.create(Json.write(body), JSON)).build());
    }

    private Object exec(Request r) throws IOException {
        try (Response resp = http.newCall(r).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new DhanException(resp.code(), text);
            if (text.isEmpty()) return new LinkedHashMap<String, Object>();
            try {
                return Json.parse(text);
            } catch (IllegalArgumentException e) {
                throw new IOException("Bad JSON from Dhan: " + e.getMessage());
            }
        }
    }
}
