package com.krish.niftyregimebot.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Time rules. All times IST. From 7 Dec 2026 (SEBI, 15 Oct 2026) index derivatives trade to
 * 3:45 pm but expiring contracts stop at 3:30 pm; this bot exits well before either.
 */
public final class MarketClock {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MarketClock() {}

    public static int minuteOfDay(long millis) {
        ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(IST);
        return z.getHour() * 60 + z.getMinute();
    }

    public static LocalDate date(long millis) { return Instant.ofEpochMilli(millis).atZone(IST).toLocalDate(); }

    public static boolean weekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** Previous weekday before {@code d} (exchange holidays are not known here). */
    public static LocalDate previousWeekday(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!weekday(p)) p = p.minusDays(1);
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
     * square-off time on the last weekday before expiry.
     */
    public static boolean weeklyExitDue(String expiry, long now, int squareOffMinute) {
        LocalDate exp;
        try { exp = LocalDate.parse(expiry); } catch (RuntimeException e) { return false; }
        LocalDate today = date(now);
        if (!today.isBefore(exp)) return true;
        return today.equals(previousWeekday(exp)) && minuteOfDay(now) >= squareOffMinute;
    }

    /** Market open for trading decisions (cash session 09:15–15:30 on weekdays). */
    public static boolean sessionOpen(long now) {
        if (!weekday(date(now))) return false;
        int m = minuteOfDay(now);
        return m >= 9 * 60 + 15 && m < 15 * 60 + 30;
    }
}
