package com.krish.niftyregimebot.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Money bookkeeping: builds the open {@link Position} from entry fills, the {@link TradeRecord}
 * from exit fills (after charges), the cost of an undone entry, and rolls {@link DayStats}
 * into a new day. Pure functions — the engine applies the results.
 */
final class Ledger {
    private Ledger() {}

    static Position open(OrderRunner.Job j, Config cfg, Sizer sizer, boolean live, long now) {
        Position p = new Position();
        p.type = j.plan.type;
        p.qty = j.size.qty;
        p.expiry = j.plan.expiry;
        p.holding = cfg.holding;
        p.entryMillis = now;
        p.live = live;
        for (OrderRunner.LegOrder o : j.orders) {
            Leg l = o.leg;
            p.legs.add(new Leg(l.securityId, l.right, l.strike, l.expiry, l.buy, l.ratio, o.avgFill()));
        }
        Payoff po = Payoff.of(p.legs);
        p.netPremium = po.netPremium;
        p.maxProfit = po.maxProfit;
        p.maxLoss = po.maxLoss;
        p.targetRs = Double.isInfinite(p.maxProfit) ? cfg.riskPerTrade : EntryGate.targetPct(p.type, cfg) * p.maxProfitRs();
        p.stopRs = cfg.stopPctOfMaxLoss * p.maxLossRs();
        p.entryCharges = sizer.chargesFor(j.plan, p.qty) / 2;
        return p;
    }

    static TradeRecord close(Position p, OrderRunner.Job j, Charges charges, Config cfg,
                             String regimeText, String ivText, long now) {
        double gross = 0, exitNet = 0, chargesRs = 0;
        for (OrderRunner.LegOrder o : j.orders) {
            double px = o.avgFill();
            Leg l = o.leg;
            gross += (l.buy ? px - l.price : l.price - px) * o.doneQty;
            exitNet += (l.buy ? px : -px) * l.ratio;          // what closing gives back per unit
            chargesRs += charges.roundTrip(l.buy ? l.price : px, l.buy ? px : l.price, o.doneQty);
        }
        TradeRecord t = new TradeRecord();
        t.strategy = p.type.title;
        t.legs = p.summary();
        t.regime = regimeText;
        t.iv = ivText;
        t.mode = p.live ? "LIVE" : "PAPER";
        t.holding = p.holding.name();
        t.qty = p.qty;
        t.lots = p.qty / Math.max(1, cfg.lotSize);
        t.entryNet = p.netPremium;
        t.exitNet = exitNet;
        t.gross = gross;
        t.charges = chargesRs;
        t.net = gross - chargesRs;
        t.maxLossRs = p.maxLossRs();
        t.entryMillis = p.entryMillis;
        t.exitMillis = now;
        t.exitReason = j.reason == null ? "" : j.reason.name();
        return t;
    }

    /** Net cost (negative) of closing a half-built entry, after charges. */
    static double unwindCost(OrderRunner.Job j, Charges charges) {
        double pnl = 0;
        for (OrderRunner.LegOrder o : j.orders) {
            double px = o.avgFill();
            Leg l = o.leg;   // l.buy = original direction, price = original fill
            pnl += (l.buy ? px - l.price : l.price - px) * o.doneQty;
            pnl -= charges.roundTrip(l.buy ? l.price : px, l.buy ? px : l.price, o.doneQty);
        }
        return pnl;
    }

    /** Book a closed trade into today's stats. */
    static void record(DayStats day, double net, long now) {
        day.realizedPnl += net;
        if (net >= 0) { day.wins++; day.consecutiveLosses = 0; } else { day.losses++; day.consecutiveLosses++; }
        day.lastExitMillis = now;
    }

    /** A fresh DayStats for {@code today}, carrying week and month totals. */
    static DayStats nextDay(DayStats prevDay, String today, LocalDate cur) {
        LocalDate prev = LocalDate.parse(prevDay.date);
        DayStats next = new DayStats();
        next.date = today;
        boolean sameWeek = prev.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .equals(cur.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        boolean sameMonth = prev.getYear() == cur.getYear() && prev.getMonth() == cur.getMonth();
        next.weekPnlBefore = sameWeek ? prevDay.weekPnlBefore + prevDay.realizedPnl : 0;
        next.monthPnlBefore = sameMonth ? prevDay.monthPnlBefore + prevDay.realizedPnl : 0;
        return next;
    }
}
