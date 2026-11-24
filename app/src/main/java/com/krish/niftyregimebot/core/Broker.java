package com.krish.niftyregimebot.core;

import java.io.IOException;

/**
 * What the engine needs from a broker. PaperBroker simulates fills; DhanLiveBroker
 * (Android side) talks to Dhan. Calls happen on the engine thread.
 */
public interface Broker {
    enum Status { PENDING, PART_TRADED, TRADED, REJECTED, CANCELLED, NOT_FOUND, UNKNOWN }

    final class OrderRequest {
        public final String correlationId;
        public final String securityId;
        public final String label;
        public final boolean buy;
        public final int qty;
        public final double limitPrice;

        public OrderRequest(String correlationId, String securityId, String label, boolean buy, int qty, double limitPrice) {
            this.correlationId = correlationId;
            this.securityId = securityId;
            this.label = label;
            this.buy = buy;
            this.qty = qty;
            this.limitPrice = limitPrice;
        }
    }

    final class OrderUpdate {
        public final String orderId;
        public final Status status;
        public final int filledQty;
        public final double avgPrice;
        public final String message;

        public OrderUpdate(String orderId, Status status, int filledQty, double avgPrice, String message) {
            this.orderId = orderId;
            this.status = status;
            this.filledQty = filledQty;
            this.avgPrice = avgPrice;
            this.message = message == null ? "" : message;
        }

        public boolean isFinal() {
            return status == Status.TRADED || status == Status.REJECTED || status == Status.CANCELLED;
        }
    }

    /** Place an order. Throws IOException when the outcome is unknown (timeout etc.) — never assume failure. */
    OrderUpdate submit(OrderRequest r) throws IOException;

    /** Look up an order by its correlation id. NOT_FOUND means the broker never received it. */
    OrderUpdate status(String correlationId) throws IOException;

    void cancel(String orderId) throws IOException;

    boolean isLive();
}
