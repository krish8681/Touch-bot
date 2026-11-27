package com.krish.niftyregimebot.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Time rules. All times IST. From 7 Dec 2026 (SEBI, 15 Oct 2026) index derivatives trade to
 * 3:45 pm but expiring contracts stop at 3:30 pm; this bot exits well before either.
 * Knows NSE trading holidays (built-in 2026 list + any the user adds in Settings).
 */
public final class MarketClock {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** NSE equity / F&O weekday trading holidays 2026 (NSE circular, Dec 2025; 15 Jan added for the BMC election). */
    public static final String[] BUILT_IN_HOLIDAYS = {
            "2026-01-15", "2026-01-26", "2026-03-03", "2026-03-26", "2026-03-31", "2026-04-03", "2026-04-14",
            "2026-05-01", "2026-05-28", "2026-06-26", "2026-09-14", "2026-10-02", "2026-10-20",
            "2026-11-10", "2026-11-24", "2026-12-25"
    };

    private static volatile Set<LocalDate> holidays = parse(java.util.Arrays.asList(BUILT_IN_HOLIDAYS));

    /** Big scheduled event days: RBI policy decision days (RBI MPC calendar 2026-27) and Union Budget days. */
    public static final String[] BUILT_IN_EVENTS = {
            "2026-02-01", "2026-04-08", "2026-06-05", "2026-08-05", "2026-10-07", "2026-12-04",
            "2027-02-01", "2027-02-05"
    };

    private static volatile Set<LocalDate> events = parse(java.util.Arrays.asList(BUILT_IN_EVENTS));

    public static void setExtraEvents(Collection<String> extra) {
        List<String> all = new ArrayList<>(java.util.Arrays.asList(BUILT_IN_EVENTS));
        if (extra != null) all.addAll(extra);
        events = parse(all);
    }

    public static boolean eventDay(LocalDate d) { return events.contains(d); }

    /** First event day after {@code from} and up to and including {@code to}, or null. */
    public static LocalDate eventBetween(LocalDate from, LocalDate to) {
        LocalDate best = null;
        for (LocalDate e : events) if (e.isAfter(from) && !e.isAfter(to) && (best == null || e.isBefore(best))) best = e;
        return best;
    }

    private MarketClock() {}

    /** Replace the holiday list: built-in dates plus {@code extra} ("yyyy-MM-dd" strings; bad ones are ignored). */
    public static void setExtraHolidays(Collection<String> extra) {
        List<String> all = new ArrayList<>(java.util.Arrays.asList(BUILT_IN_HOLIDAYS));
        if (extra != null) all.addAll(extra);
        holidays = parse(all);
    }

    /** True if the newest known holiday is in an earlier year than {@code today} (list needs updating). */
    public static boolean holidayListOutdated(LocalDate today) {
        for (LocalDate d : holidays) if (d.getYear() >= today.getYear()) return false;
        return true;
    }

    private static Set<LocalDate> parse(Collection<String> dates) {
        Set<LocalDate> out = new HashSet<>();
        for (String s : dates) {
            if (s == null) continue;
            try { out.add(LocalDate.parse(s.trim())); } catch (RuntimeException ignored) {}
        }
        return out;
    }

    public static int minuteOfDay(long millis) {
        ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(IST);
        return z.getHour() * 60 + z.getMinute();
    }

    public static LocalDate date(long millis) { return Instant.ofEpochMilli(millis).atZone(IST).toLocalDate(); }

    public static boolean weekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static boolean holiday(LocalDate d) { return holidays.contains(d); }

    /** A weekday that is not an exchange holiday. */
    public static boolean tradingDay(LocalDate d) { return weekday(d) && !holiday(d); }

    /** Previous weekday before {@code d}, ignoring holidays. */
    public static LocalDate previousWeekday(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!weekday(p)) p = p.minusDays(1);
        return p;
    }

    /** Last trading day before {@code d} (skips weekends and holidays). */
    public static LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        for (int i = 0; i < 30 && !tradingDay(p); i++) p = p.minusDays(1);
        return p;
    }

    /** Next trading day on or after {@code d}. */
    public static LocalDate nextTradingDay(LocalDate d) {
        LocalDate p = d;
        for (int i = 0; i < 30 && !tradingDay(p); i++) p = p.plusDays(1);
        return p;
    }

    public static long daysBetween(LocalDate from, LocalDate to) { return ChronoUnit.DAYS.between(from, to); }

    /**
     * Nearest expiry at least {@code minDays} calendar days away (so never today's expiry when
     * minDays ≥ 1). Expiries are "yyyy-MM-dd" strings from Dhan. Empty if none qualifies.
     */
    public static String chooseExpiry(List<String> expiries, LocalDate today, int minDays) {
        List<String> sorted = new ArrayList<>(expiries);
        Collections.sort(sorted);
        for (String e : sorted) {
            LocalDate d;
            try { d = LocalDate.parse(e); } catch (RuntimeException ex) { continue; }
            if (daysBetween(today, d) >= minDays) return e;
        }
        return "";
    }

    /**
     * Should a weekly-held position be closed now? Yes on/after the expiry day, and from
     * square-off time on the last trading day before expiry (holidays skipped). Also yes on
     * any day after that last trading day (e.g. the bot was off that afternoon).
     */
    public static boolean weeklyExitDue(String expiry, long now, int squareOffMinute) {
        LocalDate exp;
        try { exp = LocalDate.parse(expiry); } catch (RuntimeException e) { return false; }
        LocalDate today = date(now);
        if (!today.isBefore(exp)) return true;
        LocalDate exitDay = previousTradingDay(exp);
        if (today.isAfter(exitDay)) return true;
        return today.equals(exitDay) && minuteOfDay(now) >= squareOffMinute;
    }

    /** Market open for trading decisions (09:15–15:30 on trading days). */
    public static boolean sessionOpen(long now) {
        if (!tradingDay(date(now))) return false;
        int m = minuteOfDay(now);
        return m >= 9 * 60 + 15 && m < 15 * 60 + 30;
    }
}
