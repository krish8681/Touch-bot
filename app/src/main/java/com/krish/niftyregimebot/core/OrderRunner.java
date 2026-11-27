package com.krish.niftyregimebot.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Places a strategy's legs one at a time and follows each order to a fill.
 *
 * ENTRY: buys first, then sells. If a leg is rejected, never fills, has no price, or NIFTY
 * jumps more than {@link Config#entryMaxSpotMove} while legging in, the entry stops and an
 * UNWIND job closes whatever already filled. EXIT: buys back short legs first and never gives
 * up (retries every 5 s; ERROR state after 3 rejections). LIMIT orders only.
 */
final class OrderRunner {

    enum Kind { ENTRY, EXIT, UNWIND }

    /** What the runner needs from the engine. */
    interface Host {
        long now();
        double spot();
        BotState state();
        void setState(BotState s, String why);
        void pause(String why);
        void log(String level, String message);
        void alert(String message);
        void finished(Job j);             // all legs filled
    }

    static final class LegOrder {
        final Leg leg;          // the strategy leg
        final boolean buy;      // direction of THIS order
        final int targetQty;
        int doneQty;            // filled in earlier attempts
        double doneValue;       // Σ price × qty of earlier fills
        Broker.OrderRequest req;
        Broker.OrderUpdate last;
        long sentMillis, firstMillis, retryAfter;
        int rejects, attempts;
        boolean outcomeUnknown, cancelRequested;

        LegOrder(Leg leg, boolean buy, int qty) { this.leg = leg; this.buy = buy; this.targetQty = qty; }

        double avgFill() { return doneValue / Math.max(1, doneQty); }
    }

    static final class Job {
        Kind kind;
        final List<LegOrder> orders = new ArrayList<>();
        int idx;
        StrategyBuilder.Plan plan;
        Sizer.Result size;
        Engine.ExitReason reason;
        long startMillis;
        double startSpot;
        String regimeText = "", ivText = "";
    }

    private final Config cfg;
    private final Broker broker;
    private final Map<String, OptionQuote> quotes;
    private final Host host;
    private Job job;
    private int corrSeq;

    OrderRunner(Config cfg, Broker broker, Map<String, OptionQuote> quotes, Host host) {
        this.cfg = cfg;
        this.broker = broker;
        this.quotes = quotes;
        this.host = host;
    }

    Job job() { return job; }
    boolean busy() { return job != null; }
    boolean entering() { return job != null && job.kind == Kind.ENTRY; }

    String text() {
        if (job == null) return "";
        return job.kind + " leg " + Math.min(job.idx + 1, job.orders.size()) + "/" + job.orders.size();
    }

    /** Start an entry for a sized plan. */
    void enter(StrategyBuilder.Plan plan, Sizer.Result size, String regimeText, String ivText) {
        Job j = new Job();
        j.kind = Kind.ENTRY;
        j.plan = plan;
        j.size = size;
        j.startMillis = host.now();
        j.startSpot = host.spot();
        j.regimeText = regimeText;
        j.ivText = ivText;
        for (Leg l : plan.legs) j.orders.add(new LegOrder(l, l.buy, size.qty * l.ratio));
        job = j;
        step(host.now());
    }

    /** Close a position: short legs bought back first, then long legs sold. */
    void exit(Position p, Engine.ExitReason why) {
        Job j = new Job();
        j.kind = Kind.EXIT;
        j.reason = why;
        j.startMillis = host.now();
        for (Leg l : p.legs) if (!l.buy) j.orders.add(new LegOrder(l, true, p.qty * l.ratio));
        for (Leg l : p.legs) if (l.buy) j.orders.add(new LegOrder(l, false, p.qty * l.ratio));
        job = j;
        step(host.now());
    }

    // ------------------------------------------------------------------ stepping

    void step(long now) {
        for (int guard = 0; guard < 10; guard++) {
            Job j = job;
            if (j == null) return;
            if (j.idx >= j.orders.size()) { job = null; host.finished(j); continue; }
            LegOrder o = j.orders.get(j.idx);
            int idxBefore = j.idx;
            if (o.req == null) {
                if (now < o.retryAfter) return;
                double moved = Math.abs(host.spot() - j.startSpot);
                if (j.kind == Kind.ENTRY && j.idx > 0 && cfg.entryMaxSpotMove > 0 && j.startSpot > 0 && moved > cfg.entryMaxSpotMove) {
                    abortEntry(String.format(Locale.US, "NIFTY moved %.0f points while placing legs", moved));
                    continue;
                }
                if (!submit(o, now)) {
                    if (j.kind == Kind.ENTRY && now - j.startMillis > 30_000) abortEntry("no usable price for " + o.leg.label());
                    return;
                }
                if (o.last != null && o.last.isFinal()) handle(j, o, o.last, now);
                else return;
            } else {
                follow(j, o, now);
            }
            if (job != j) continue;          // replaced (entry aborted → unwind)
            if (j.idx == idxBefore) return;  // waiting on this leg
        }
    }

    /** Send (or re-send) the remaining quantity of a leg. False if there is no price yet. */
    private boolean submit(LegOrder o, long now) {
        OptionQuote q = quotes.get(o.leg.securityId);
        if (q == null || !q.hasBook()) return false;
        int remaining = o.targetQty - o.doneQty;
        if (remaining <= 0) return true;
        double pad = cfg.limitBuffer * (1 + o.attempts);
        double limit = Math.max(0.05, PaperBroker.round(o.buy ? q.ask + pad : q.bid - pad));
        o.req = new Broker.OrderRequest(newCorrelationId(job.kind == Kind.ENTRY ? "E" : "X"), o.leg.securityId,
                q.label(), o.buy, remaining, limit);
        o.last = null;
        o.sentMillis = now;
        if (o.firstMillis == 0) o.firstMillis = now;
        o.attempts++;
        o.cancelRequested = false;
        host.log("INFO", String.format(Locale.US, "%s %s qty %d limit %.2f (%s, try %d)", o.buy ? "BUY" : "SELL",
                q.label(), remaining, limit, job.kind, o.attempts));
        try {
            o.last = broker.submit(o.req);
            o.outcomeUnknown = false;
        } catch (IOException e) {
            o.outcomeUnknown = true;
            host.log("WARN", "Order outcome unknown (" + e.getMessage() + ") — checking by id before any retry");
        }
        return true;
    }

    private void follow(Job j, LegOrder o, long now) {
        if (o.last != null && o.last.status == Broker.Status.TRADED) { legFilled(j, o); return; }
        if (now - o.sentMillis < 1_000) return;
        Broker.OrderUpdate u;
        try {
            u = broker.status(o.req.correlationId);
        } catch (IOException e) {
            if ((now / 1000) % 10 == 0) host.log("WARN", "Cannot check order: " + e.getMessage());
            return;
        }
        if (u.status == Broker.Status.NOT_FOUND) {
            if (o.outcomeUnknown && now - o.sentMillis < 30_000) return;
            if (j.kind == Kind.ENTRY && o.attempts >= 3) { legFailed(j, o, "broker never received the order"); return; }
            host.log("WARN", "Order " + o.req.correlationId + " never reached the broker — resending");
            o.req = null;
            return;
        }
        o.outcomeUnknown = false;
        handle(j, o, u, now);
    }

    private void handle(Job j, LegOrder o, Broker.OrderUpdate u, long now) {
        o.last = u;
        switch (u.status) {
            case TRADED:
                legFilled(j, o);
                return;
            case REJECTED:
                o.last = null;
                legFailed(j, o, "rejected: " + u.message);
                return;
            case CANCELLED:
                o.doneQty += u.filledQty;
                o.doneValue += u.filledQty * u.avgPrice;
                o.last = null;
                if (o.doneQty >= o.targetQty) { legFilled(j, o); return; }
                int maxTries = j.kind == Kind.ENTRY ? 3 : 1_000_000;
                if (o.attempts >= maxTries) { legFailed(j, o, "not filled after " + o.attempts + " tries"); return; }
                o.req = null;   // re-price on the next step
                return;
            default:
                long age = now - o.sentMillis;
                long timeout = j.kind == Kind.ENTRY ? 8_000 : 5_000;
                if (age > timeout && !o.cancelRequested && u.orderId != null && !u.orderId.isEmpty()) {
                    o.cancelRequested = true;
                    host.log("INFO", "Re-pricing " + o.req.label + " (not filled in " + age / 1000 + "s)");
                    try { broker.cancel(u.orderId); } catch (IOException e) { host.log("WARN", "Cancel failed: " + e.getMessage()); }
                }
                if (o.cancelRequested && age > 45_000 && host.state() != BotState.ERROR) {
                    host.pause("order stuck");
                    host.setState(BotState.ERROR, "order " + o.req.label + " not confirmed after cancel");
                    host.alert("Order " + o.req.label + " is stuck (no fill, no cancel confirmation). Check Dhan now.");
                }
        }
    }

    private void legFilled(Job j, LegOrder o) {
        if (o.last != null && o.last.status == Broker.Status.TRADED) {
            o.doneQty += o.last.filledQty;
            o.doneValue += o.last.filledQty * o.last.avgPrice;
            o.last = null;
        }
        if (o.doneQty < o.targetQty) { o.req = null; return; } // partly filled: send the rest
        host.log("INFO", String.format(Locale.US, "Filled %s %s qty %d @ %.2f", o.buy ? "BUY" : "SELL",
                o.leg.label(), o.doneQty, o.doneQty == 0 ? 0 : o.doneValue / o.doneQty));
        j.idx++;
    }

    private void legFailed(Job j, LegOrder o, String why) {
        host.log("WARN", "Leg " + o.leg.label() + " failed: " + why);
        if (j.kind == Kind.ENTRY) { abortEntry(o.leg.label() + " " + why); return; }
        // EXIT / UNWIND never gives up: alert and retry with a fresh price after a short wait.
        o.rejects++;
        o.req = null;
        o.last = null;
        o.retryAfter = host.now() + 5_000;
        if (o.rejects == 1 || o.rejects % 10 == 0)
            host.alert("Exit order problem on " + o.leg.label() + ": " + why + ". Retrying every 5s. Check Dhan.");
        if (o.rejects >= 3 && host.state() != BotState.ERROR) {
            host.pause("exit trouble");
            host.setState(BotState.ERROR, "exit order trouble on " + o.leg.label());
        }
    }

    /** Stop an entry part-way: close whatever legs already filled (unwind). */
    void abortEntry(String why) {
        Job j = job;
        if (j == null || j.kind != Kind.ENTRY) return;
        // Cancel the working order, counting any partial fill.
        LegOrder cur = j.idx < j.orders.size() ? j.orders.get(j.idx) : null;
        if (cur != null && cur.last != null) {
            Broker.OrderUpdate u = cur.last;
            if (!u.isFinal() && u.orderId != null && !u.orderId.isEmpty()) {
                try { broker.cancel(u.orderId); } catch (IOException e) { host.log("WARN", "Cancel failed: " + e.getMessage()); }
                try { u = broker.status(cur.req.correlationId); } catch (IOException ignored) { }
            }
            if (u.filledQty > 0) { cur.doneQty += u.filledQty; cur.doneValue += u.filledQty * u.avgPrice; }
            cur.last = null;
        }
        Job un = new Job();
        un.kind = Kind.UNWIND;
        un.reason = Engine.ExitReason.UNWIND;
        un.startMillis = host.now();
        for (int k = j.orders.size() - 1; k >= 0; k--) {
            LegOrder o = j.orders.get(k);
            if (o.doneQty > 0) {
                Leg filled = new Leg(o.leg.securityId, o.leg.right, o.leg.strike, o.leg.expiry, o.leg.buy, 1, o.avgFill());
                un.orders.add(new LegOrder(filled, !o.buy, o.doneQty));
            }
        }
        job = null;
        if (un.orders.isEmpty()) {
            host.log("INFO", "Entry cancelled (" + why + "); nothing was filled");
            return;
        }
        host.alert("Entry stopped part-way (" + why + "). Closing the " + un.orders.size() + " filled leg(s) now.");
        job = un;
    }

    private String newCorrelationId(String kind) {
        return "NRB" + kind + (host.now() / 1000 % 100_000_000) + (++corrSeq % 100);
    }
}
