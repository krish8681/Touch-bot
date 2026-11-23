import com.krish.niftydhanbot.core.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.*;

/**
 * Replays NIFTY 1-minute history through the bot's real Engine (same code as the app).
 * Option prices are MODELLED with Black-Scholes from the NIFTY price, because historical
 * option quotes are not available here. Each 1-min candle becomes 4 ticks: open, then the
 * extreme against the candle's direction first (cautious), the other extreme, then close.
 *
 * Usage: java Backtest <dataDir/1min> [ivMult] [halfSpread] [fromDate] [toDate]
 */
public class Backtest {
    static final ZoneId IST = Engine.IST;
    static final double RATE = 0.065;

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a[0]);
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < a.length; i++) { String[] kv = a[i].split("=", 2); opt.put(kv[0], kv.length > 1 ? kv[1] : "true"); }
        double ivMult = Double.parseDouble(opt.getOrDefault("iv", "1.25"));
        double halfSpread = Double.parseDouble(opt.getOrDefault("half", "0.10"));
        LocalDate from = LocalDate.parse(opt.getOrDefault("from", "2019-03-01"));
        LocalDate to = LocalDate.parse(opt.getOrDefault("to", "2099-01-01"));
        boolean quiet = opt.containsKey("quiet");
        boolean favorable = "favorable".equals(opt.get("path"));
        boolean noLimits = opt.containsKey("nolimits");

        TreeMap<LocalDate, List<Candle>> days = load(dir);
        days.entrySet().removeIf(e -> e.getValue().size() < 370); // skip special/short sessions
        TreeSet<LocalDate> tradingDays = new TreeSet<>(days.keySet());
        Map<LocalDate, Double> iv = impliedVolProxy(days, ivMult);

        BotConfig cfg = new BotConfig();
        if (noLimits) { cfg.maxConsecutiveLosses = 99; cfg.dailyLossLimit = 1e9; cfg.weeklyLossLimit = 1e9; cfg.monthlyLossLimit = 1e9; }
        for (Map.Entry<String, String> o : opt.entrySet()) {
            try {
                java.lang.reflect.Field f = BotConfig.class.getField(o.getKey());
                if (f.getType() == int.class) f.setInt(cfg, Integer.parseInt(o.getValue()));
                else if (f.getType() == double.class) f.setDouble(cfg, Double.parseDouble(o.getValue()));
                else if (f.getType() == boolean.class) f.setBoolean(cfg, Boolean.parseBoolean(o.getValue()));
                else if (f.getType() == String.class) f.set(cfg, o.getValue());
            } catch (NoSuchFieldException ignored) {}
        }
        List<TradeRecord> trades = new ArrayList<>();
        Map<String, Integer> blocks = new TreeMap<>();
        int[] signals = new int[3];
        Engine[] holder = new Engine[1];
        Engine.Listener l = new Engine.Listener() {
            public void onLog(String level, String m) {
                int i = m.indexOf("signal blocked: ");
                if (i >= 0) for (String r : m.substring(i + 16).split("; ")) blocks.merge(r.replaceAll("[0-9₹().,-]+", "#").trim(), 1, Integer::sum);
            }
            public void onSignal(Signal s) { signals[s.side.ordinal()]++; }
            public void onStateChanged(BotState s, String w) {}
            public void onPositionChanged(Position p) {}
            public void onTradeClosed(TradeRecord t) { trades.add(t); }
            public void onStatsChanged(DayStats d) {}
            public void onAlert(String m) {}
        };
        PaperBroker broker = new PaperBroker(id -> holder[0].quote(id), cfg.paperSlippagePts);
        Engine e = new Engine(cfg, new Charges(), broker, l);
        holder[0] = e;

        boolean started = false;
        int nDays = 0;
        for (Map.Entry<LocalDate, List<Candle>> d : days.entrySet()) {
            LocalDate date = d.getKey();
            if (date.isBefore(from) || date.isAfter(to)) continue;
            Double vol = iv.get(date);
            if (vol == null) continue;
            nDays++;
            LocalDate expiry = expiryFor(date, tradingDays, cfg.avoidExpiryDayContract);
            long expiryMillis = expiry.atTime(15, 30).atZone(IST).toInstant().toEpochMilli();
            for (Candle c : d.getValue()) {
                boolean up = c.close >= c.open;
                if (favorable) up = !up;
                double[] path = up
                        ? new double[]{c.open, c.low, c.high, c.close}
                        : new double[]{c.open, c.high, c.low, c.close};
                long[] at = {1_000, 15_000, 30_000, 50_000};
                for (int k = 0; k < 4; k++) {
                    long now = c.startMillis + at[k];
                    double s = path[k];
                    quoteChain(e, s, now, expiry, expiryMillis, vol, halfSpread);
                    e.onSpot(s, now);
                    e.onTimer(now);
                    if (!started) { e.setState(BotState.READY, "backtest"); e.startTrading(); started = true; }
                }
            }
        }
        report(trades, nDays, signals, blocks, ivMult, halfSpread, quiet);
    }

    // ------------------------------------------------------------------ option model

    static void quoteChain(Engine e, double s, long now, LocalDate expiry, long expiryMillis, double vol, double half) {
        double t = Math.max(1.0 / (365 * 24), (expiryMillis - now) / (365.0 * 86_400_000L));
        double atm = Math.round(s / 50.0) * 50;
        for (int j = -6; j <= 6; j++) {
            double k = atm + j * 50;
            for (int side = 0; side < 2; side++) {
                boolean call = side == 0;
                double mid = bs(s, k, t, vol, call);
                double delta = bsDelta(s, k, t, vol, call);
                double bid = Math.max(0.05, tick(mid - half));
                double ask = Math.max(bid + 0.05, tick(mid + half));
                String id = expiry + (call ? "C" : "P") + (int) k;
                e.upsertContract(id, call ? Signal.Side.CE : Signal.Side.PE, k, delta, vol, 1e6, mid, bid, ask, now);
            }
        }
    }

    static double tick(double p) { return Math.round(p * 20) / 20.0; }

    static double bs(double s, double k, double t, double v, boolean call) {
        double sq = v * Math.sqrt(t);
        double d1 = (Math.log(s / k) + (RATE + v * v / 2) * t) / sq, d2 = d1 - sq;
        double disc = Math.exp(-RATE * t);
        return call ? s * cnd(d1) - k * disc * cnd(d2) : k * disc * cnd(-d2) - s * cnd(-d1);
    }

    static double bsDelta(double s, double k, double t, double v, boolean call) {
        double d1 = (Math.log(s / k) + (RATE + v * v / 2) * t) / (v * Math.sqrt(t));
        return call ? cnd(d1) : cnd(d1) - 1;
    }

    static double cnd(double x) { // Abramowitz-Stegun
        double k = 1 / (1 + 0.2316419 * Math.abs(x));
        double w = 1 - 1 / Math.sqrt(2 * Math.PI) * Math.exp(-x * x / 2)
                * (k * (0.319381530 + k * (-0.356563782 + k * (1.781477937 + k * (-1.821255978 + k * 1.330274429)))));
        return x < 0 ? 1 - w : w;
    }

    /** Volatility proxy: last 20 days' intraday realised volatility × multiplier, clamped 9%–60%. */
    static Map<LocalDate, Double> impliedVolProxy(TreeMap<LocalDate, List<Candle>> days, double mult) {
        Map<LocalDate, Double> out = new HashMap<>();
        Deque<Double> window = new ArrayDeque<>();
        for (Map.Entry<LocalDate, List<Candle>> d : days.entrySet()) {
            if (window.size() == 20) {
                double mean = window.stream().mapToDouble(x -> x).average().orElse(0);
                out.put(d.getKey(), Math.max(0.09, Math.min(0.60, Math.sqrt(252 * mean) * mult)));
            }
            double sum = 0;
            List<Candle> c = d.getValue();
            for (int i = 1; i < c.size(); i++) { double r = Math.log(c.get(i).close / c.get(i - 1).close); sum += r * r; }
            window.addLast(sum);
            if (window.size() > 20) window.removeFirst();
        }
        return out;
    }

    /** Weekly NIFTY expiry: Thursday until Aug 2025, Tuesday from Sep 2025; moved earlier if a holiday. */
    static LocalDate expiryFor(LocalDate d, TreeSet<LocalDate> trading, boolean avoidToday) {
        LocalDate e = nominalExpiry(d, trading);
        if (avoidToday && e.equals(d)) e = nominalExpiry(d.plusDays(1), trading);
        return e;
    }

    static LocalDate nominalExpiry(LocalDate d, TreeSet<LocalDate> trading) {
        DayOfWeek wd = d.isBefore(LocalDate.of(2025, 9, 1)) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY;
        LocalDate e = d.with(TemporalAdjusters.nextOrSame(wd));
        LocalDate last = trading.last();
        while (e.isAfter(d) && !e.isAfter(last) && !trading.contains(e)) e = e.minusDays(1);
        return e;
    }

    // ------------------------------------------------------------------ data

    static TreeMap<LocalDate, List<Candle>> load(Path dir) throws IOException {
        TreeMap<LocalDate, List<Candle>> days = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) { files = s.filter(p -> p.toString().endsWith(".csv")).sorted().collect(Collectors.toList()); }
        for (Path f : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(f.toFile()))) {
                String line = r.readLine(); // header
                while ((line = r.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length < 6) continue;
                    long ms = Long.parseLong(p[0]) * 1000;
                    Candle c = new Candle(ms, Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]));
                    days.computeIfAbsent(Engine.istDate(ms), k -> new ArrayList<>()).add(c);
                }
            }
        }
        return days;
    }

    // ------------------------------------------------------------------ report

    static void report(List<TradeRecord> trades, int nDays, int[] signals, Map<String, Integer> blocks, double ivMult, double half, boolean quiet) {
        System.out.printf(Locale.US, "Settings: IV = 20-day realised vol × %.2f, spread %.2f pts, slippage 0.5/side, charges on (STT 0.15%%)%n", ivMult, half * 2);
        System.out.printf(Locale.US, "Days tested %d | CE signals %d, PE signals %d | trades %d%n", nDays, signals[0], signals[1], trades.size());
        System.out.println("\nYear   Trades  Win%   Net ₹      Avg win  Avg loss  PF    MaxDD ₹   Charges ₹");
        Map<Integer, List<TradeRecord>> byYear = new TreeMap<>();
        for (TradeRecord t : trades) byYear.computeIfAbsent(Engine.istDate(t.exitMillis).getYear(), k -> new ArrayList<>()).add(t);
        for (Map.Entry<Integer, List<TradeRecord>> y : byYear.entrySet()) line(String.valueOf(y.getKey()), y.getValue());
        line("ALL", trades);
        if (quiet) return;

        Map<String, int[]> reasons = new TreeMap<>();
        Map<String, double[]> reasonPnl = new TreeMap<>();
        for (TradeRecord t : trades) {
            reasons.computeIfAbsent(t.exitReason, k -> new int[1])[0]++;
            reasonPnl.computeIfAbsent(t.exitReason, k -> new double[1])[0] += t.net;
        }
        System.out.println("\nExit reasons:");
        for (String r : reasons.keySet()) System.out.printf(Locale.US, "  %-11s %5d trades  net ₹%,.0f%n", r, reasons.get(r)[0], reasonPnl.get(r)[0]);

        double gross = trades.stream().mapToDouble(t -> t.gross).sum();
        double ch = trades.stream().mapToDouble(t -> t.charges).sum();
        System.out.printf(Locale.US, "%nBefore charges ₹%,.0f | charges ₹%,.0f | after ₹%,.0f%n", gross, ch, gross - ch);

        Map<LocalDate, Double> byDay = new TreeMap<>();
        for (TradeRecord t : trades) byDay.merge(Engine.istDate(t.exitMillis), t.net, Double::sum);
        long green = byDay.values().stream().filter(v -> v > 0).count();
        System.out.printf(Locale.US, "Days with trades %d (%.0f%% of days) | green days %d | worst day ₹%,.0f | best day ₹%,.0f%n",
                byDay.size(), 100.0 * byDay.size() / Math.max(1, nDays), green,
                byDay.values().stream().mapToDouble(x -> x).min().orElse(0), byDay.values().stream().mapToDouble(x -> x).max().orElse(0));

        System.out.println("\nTop reasons a signal was blocked:");
        blocks.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                .forEach(b -> System.out.printf("  %6d  %s%n", b.getValue(), b.getKey()));
    }

    static void line(String label, List<TradeRecord> t) {
        int n = t.size();
        if (n == 0) { System.out.println(label + "  no trades"); return; }
        double net = 0, winSum = 0, lossSum = 0, peak = 0, eq = 0, dd = 0, ch = 0;
        int wins = 0;
        for (TradeRecord r : t) {
            net += r.net; ch += r.charges;
            if (r.net > 0) { wins++; winSum += r.net; } else lossSum += r.net;
            eq += r.net; peak = Math.max(peak, eq); dd = Math.min(dd, eq - peak);
        }
        int losses = n - wins;
        System.out.printf(Locale.US, "%-5s %7d  %4.0f%%  %,10.0f  %7.0f  %8.0f  %4.2f  %,9.0f  %,9.0f%n",
                label, n, 100.0 * wins / n, net, wins == 0 ? 0 : winSum / wins, losses == 0 ? 0 : lossSum / losses,
                lossSum == 0 ? 99 : winSum / -lossSum, dd, ch);
    }
}
