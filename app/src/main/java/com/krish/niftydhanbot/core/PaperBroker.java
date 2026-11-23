package com.krish.niftydhanbot.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Simulated broker using live quotes. A buy fills at ask + slippage if ask ≤ limit;
 * a sell fills at bid − slippage if bid ≥ limit. Otherwise the order waits until
 * {@link #status} sees a quote that crosses it. Never sends anything to Dhan.
 */
public final class PaperBroker implements Broker {
    public interface QuoteSource { OptionQuote quote(String securityId); }

    private final QuoteSource quotes;
    private final double slippage;
    private final Map<String, OrderRequest> open = new HashMap<>();
    private final Map<String, OrderUpdate> done = new HashMap<>();
    private int seq;

    public PaperBroker(QuoteSource quotes, double slippage) {
        this.quotes = quotes;
        this.slippage = slippage;
    }

    @Override public OrderUpdate submit(OrderRequest r) {
        String id = "PAPER-" + (++seq);
        OrderUpdate u = tryFill(id, r);
        if (u.status == Status.PENDING) open.put(r.correlationId, r);
        else done.put(r.correlationId, u);
        ids.put(r.correlationId, id);
        return u;
    }

    private final Map<String, String> ids = new HashMap<>();

    @Override public OrderUpdate status(String correlationId) {
        OrderUpdate d = done.get(correlationId);
        if (d != null) return d;
        OrderRequest r = open.get(correlationId);
        if (r == null) return new OrderUpdate("", Status.NOT_FOUND, 0, 0, "unknown order");
        OrderUpdate u = tryFill(ids.get(correlationId), r);
        if (u.status != Status.PENDING) { open.remove(correlationId); done.put(correlationId, u); }
        return u;
    }

    @Override public void cancel(String orderId) {
        for (Map.Entry<String, String> e : ids.entrySet()) {
            if (e.getValue().equals(orderId) && open.containsKey(e.getKey())) {
                open.remove(e.getKey());
                done.put(e.getKey(), new OrderUpdate(orderId, Status.CANCELLED, 0, 0, "cancelled"));
            }
        }
    }

    @Override public boolean isLive() { return false; }

    private OrderUpdate tryFill(String id, OrderRequest r) {
        OptionQuote q = quotes.quote(r.securityId);
        if (q == null || q.bid <= 0 || q.ask <= 0) return new OrderUpdate(id, Status.PENDING, 0, 0, "waiting for quote");
        if (r.buy) {
            if (q.ask > r.limitPrice) return new OrderUpdate(id, Status.PENDING, 0, 0, "ask above limit");
            double px = Math.min(r.limitPrice, q.ask + slippage);
            return new OrderUpdate(id, Status.TRADED, r.qty, round(px), "paper fill");
        } else {
            if (q.bid < r.limitPrice) return new OrderUpdate(id, Status.PENDING, 0, 0, "bid below limit");
            double px = Math.max(r.limitPrice, q.bid - slippage);
            return new OrderUpdate(id, Status.TRADED, r.qty, round(px), "paper fill");
        }
    }

    static double round(double p) { return Math.round(p * 20) / 20.0; }  // 0.05 tick
}
