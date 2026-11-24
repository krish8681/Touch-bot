package com.krish.niftyregimebot.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Simulated broker using live quotes. Never sends anything to Dhan.
 *
 * With fresh market depth (5 levels from the live feed) a buy takes only the quantity shown at
 * ask levels at or below its limit, walking up the levels (worse prices as it goes), plus
 * slippage; a sell does the same down the bid levels. What is not available stays open and may
 * fill later from the next depth update — so big orders can fill in parts, like the real market.
 * The same depth snapshot is never used twice by one order.
 *
 * Without depth it falls back to: buy fills fully at ask + slippage if ask ≤ limit;
 * sell fills fully at bid − slippage if bid ≥ limit.
 */
public final class PaperBroker implements Broker {
    public interface QuoteSource { OptionQuote quote(String securityId); }
    public interface Clock { long now(); }

    private static final class Working {
        final OrderRequest req;
        final String id;
        int filled;
        double value;
        long usedDepthMillis;
        Working(OrderRequest r, String id) { this.req = r; this.id = id; }
    }

    private final QuoteSource quotes;
    private final double slippage;
    private final long depthMaxAge;
    private final Clock clock;
    private final Map<String, Working> open = new HashMap<>();
    private final Map<String, OrderUpdate> done = new HashMap<>();
    private int seq;

    public PaperBroker(QuoteSource quotes, double slippage) {
        this(quotes, slippage, 10_000, System::currentTimeMillis);
    }

    public PaperBroker(QuoteSource quotes, double slippage, long depthMaxAgeMillis, Clock clock) {
        this.quotes = quotes;
        this.slippage = slippage;
        this.depthMaxAge = depthMaxAgeMillis;
        this.clock = clock;
    }

    @Override public OrderUpdate submit(OrderRequest r) {
        Working w = new Working(r, "PAPER-" + (++seq));
        OrderUpdate u = tryFill(w);
        if (u.isFinal()) done.put(r.correlationId, u);
        else open.put(r.correlationId, w);
        return u;
    }

    @Override public OrderUpdate status(String correlationId) {
        OrderUpdate d = done.get(correlationId);
        if (d != null) return d;
        Working w = open.get(correlationId);
        if (w == null) return new OrderUpdate("", Status.NOT_FOUND, 0, 0, "unknown order");
        OrderUpdate u = tryFill(w);
        if (u.isFinal()) { open.remove(correlationId); done.put(correlationId, u); }
        return u;
    }

    @Override public void cancel(String orderId) {
        for (Map.Entry<String, Working> e : new HashMap<>(open).entrySet()) {
            Working w = e.getValue();
            if (!w.id.equals(orderId)) continue;
            open.remove(e.getKey());
            done.put(e.getKey(), new OrderUpdate(orderId, Status.CANCELLED, w.filled, avg(w), "cancelled"));
        }
    }

    @Override public boolean isLive() { return false; }

    private static double avg(Working w) { return w.filled == 0 ? 0 : round(w.value / w.filled); }

    private OrderUpdate pending(Working w, String why) {
        return w.filled > 0
                ? new OrderUpdate(w.id, Status.PART_TRADED, w.filled, avg(w), "paper part fill; " + why)
                : new OrderUpdate(w.id, Status.PENDING, 0, 0, why);
    }

    private OrderUpdate tryFill(Working w) {
        OrderRequest r = w.req;
        OptionQuote q = quotes.quote(r.securityId);
        if (q == null || q.bid <= 0 || q.ask <= 0) return pending(w, "waiting for quote");
        long now = clock.now();
        int need = r.qty - w.filled;

        if (q.hasDepth(now, depthMaxAge)) {
            if (q.depthMillis == w.usedDepthMillis) return pending(w, "waiting for new market depth");
            w.usedDepthMillis = q.depthMillis;
            double[] px = r.buy ? q.askPx : q.bidPx;
            int[] qty = r.buy ? q.askQty : q.bidQty;
            for (int i = 0; i < px.length && need > 0; i++) {
                if (px[i] <= 0 || qty[i] <= 0) continue;
                if (r.buy ? px[i] > r.limitPrice : px[i] < r.limitPrice) break;
                int take = Math.min(need, qty[i]);
                double fill = r.buy ? Math.min(r.limitPrice, px[i] + slippage) : Math.max(r.limitPrice, px[i] - slippage);
                w.filled += take;
                w.value += take * fill;
                need -= take;
            }
            if (need <= 0) return new OrderUpdate(w.id, Status.TRADED, w.filled, avg(w), "paper fill (from market depth)");
            return pending(w, r.buy ? "not enough quantity at or below limit" : "not enough quantity at or above limit");
        }

        if (r.buy) {
            if (q.ask > r.limitPrice) return pending(w, "ask above limit");
            w.value += need * Math.min(r.limitPrice, q.ask + slippage);
        } else {
            if (q.bid < r.limitPrice) return pending(w, "bid below limit");
            w.value += need * Math.max(r.limitPrice, q.bid - slippage);
        }
        w.filled = r.qty;
        return new OrderUpdate(w.id, Status.TRADED, w.filled, avg(w), "paper fill (no depth data)");
    }

    static double round(double p) { return Math.round(p * 20) / 20.0; }  // 0.05 tick
}
