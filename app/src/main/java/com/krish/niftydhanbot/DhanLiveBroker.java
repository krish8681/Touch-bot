package com.krish.niftydhanbot;

import com.krish.niftydhanbot.core.Broker;
import com.krish.niftydhanbot.core.Json;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Dhan orders. LOCKED in this version: the constructor refuses to build unless
 * BuildConfig.LIVE_ORDERS_UNLOCKED is true, which it is not. Kept here so the live path
 * can be reviewed and tested before it is ever switched on.
 *
 * Before unlocking: written Dhan confirmation of the hosting model under the retail-algo
 * rules, static IP registered with Dhan (mobile data IPs change — orders will be refused),
 * and weeks of clean paper results.
 */
public final class DhanLiveBroker implements Broker {
    private final DhanApi api;

    public DhanLiveBroker(DhanApi api) {
        if (!BuildConfig.LIVE_ORDERS_UNLOCKED) throw new IllegalStateException("Live orders are locked in this build");
        this.api = api;
    }

    @Override public OrderUpdate submit(OrderRequest r) throws IOException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dhanClientId", api.clientId());
        b.put("correlationId", r.correlationId);
        b.put("transactionType", r.buy ? "BUY" : "SELL");
        b.put("exchangeSegment", "NSE_FNO");
        b.put("productType", "INTRADAY");
        b.put("orderType", "LIMIT");
        b.put("validity", "DAY");
        b.put("securityId", r.securityId);
        b.put("quantity", r.qty);
        b.put("price", r.limitPrice);
        Map<String, Object> resp = api.placeOrder(b);
        return toUpdate(resp);
    }

    @Override public OrderUpdate status(String correlationId) throws IOException {
        Map<String, Object> m = api.orderByCorrelation(correlationId);
        if (m.isEmpty()) return new OrderUpdate("", Status.NOT_FOUND, 0, 0, "not found");
        return toUpdate(m);
    }

    @Override public void cancel(String orderId) throws IOException { api.cancelOrder(orderId); }

    @Override public boolean isLive() { return true; }

    static OrderUpdate toUpdate(Map<String, Object> m) {
        String s = Json.str(m, "orderStatus", "UNKNOWN");
        Status st;
        switch (s) {
            case "TRADED": st = Status.TRADED; break;
            case "PART_TRADED": st = Status.PART_TRADED; break;
            case "REJECTED": st = Status.REJECTED; break;
            case "CANCELLED": case "EXPIRED": st = Status.CANCELLED; break;
            case "TRANSIT": case "PENDING": case "TRIGGERED": st = Status.PENDING; break;
            default: st = Status.UNKNOWN;
        }
        return new OrderUpdate(Json.str(m, "orderId", ""), st,
                (int) Json.num(m, "filledQty", 0), Json.num(m, "averageTradedPrice", 0),
                Json.str(m, "omsErrorDescription", ""));
    }
}
