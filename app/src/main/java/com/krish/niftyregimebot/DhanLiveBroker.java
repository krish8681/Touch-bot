package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.Broker;
import com.krish.niftyregimebot.core.Json;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Dhan orders (LIMIT only — exchanges do not allow market orders from retail algos).
 * Used only when you switch Live on in Settings.
 *
 * Since 1 Apr 2026 Dhan accepts API orders only from a static IP you have whitelisted
 * (web.dhan.co → Profile → Trading & Data APIs → Add IP). From a phone on mobile data the
 * IP keeps changing, so Dhan answers "DH-905 Invalid IP" and the order is rejected.
 */
public final class DhanLiveBroker implements Broker {
    private final DhanApi api;
    private final String productType;   // INTRADAY or MARGIN (carry forward)

    public DhanLiveBroker(DhanApi api, boolean carryForward) {
        this.api = api;
        this.productType = carryForward ? "MARGIN" : "INTRADAY";
    }

    @Override public OrderUpdate submit(OrderRequest r) throws IOException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dhanClientId", api.clientId());
        b.put("correlationId", r.correlationId);
        b.put("transactionType", r.buy ? "BUY" : "SELL");
        b.put("exchangeSegment", "NSE_FNO");
        b.put("productType", productType);
        b.put("orderType", "LIMIT");
        b.put("validity", "DAY");
        b.put("securityId", r.securityId);
        b.put("quantity", r.qty);
        b.put("price", r.limitPrice);
        try {
            return toUpdate(api.placeOrder(b));
        } catch (DhanApi.DhanException e) {
            // A clear refusal (bad IP, margin, etc.) is a rejection, not an unknown outcome.
            if (e.code >= 400 && e.code < 500) {
                String why = e.getMessage();
                if (why.contains("DH-905")) why = "Dhan refused the order: this phone's IP is not whitelisted (DH-905). " + why;
                return new OrderUpdate("", Status.REJECTED, 0, 0, why);
            }
            throw e;
        }
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
