package com.krish.niftyregimebot.core;

/** One open F&O position as Dhan reports it (used to check the bot and the account agree). */
public final class BrokerPosition {
    public final String securityId, symbol;
    public final int netQty;

    public BrokerPosition(String securityId, String symbol, int netQty) {
        this.securityId = securityId;
        this.symbol = symbol;
        this.netQty = netQty;
    }
}
