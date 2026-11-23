package io.github.vladislavekimtcov.acrylicweather;

import java.util.Locale;

/**
 * Times are kept as "local epoch seconds": the wall-clock time at the place, counted as if it
 * were UTC. Open-Meteo sends every time that way (timezone=auto), so comparing and formatting
 * them needs no time zone database, and each place shows its own clock.
 */
final class Time {
    static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};

    static boolean clock24;

    private Time() {}

    /** "2026-11-22T15:15" or "2026-11-22" as local epoch seconds; -1 when unreadable. */
    static long parse(String s) {
        if (s == null || s.length() < 10) return -1;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
            int hh = 0, mm = 0;
            if (s.length() >= 16) {
                hh = Integer.parseInt(s.substring(11, 13));
                mm = Integer.parseInt(s.substring(14, 16));
            }
            return days(y, m, d) * 86400L + hh * 3600L + mm * 60L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
    static long days(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    static int weekday(long local) {
        long days = Math.floorDiv(local, 86400L);
        return (int) Math.floorMod(days + 4, 7L); // 1970-01-01 was a Thursday
    }

    /** {year, month 1-12, day}. */
    static int[] date(long local) {
        long z = Math.floorDiv(local, 86400L) + 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        return new int[] {(int) (y + (m <= 2 ? 1 : 0)), (int) m, (int) d};
    }

    static int hour(long local) {
        return (int) (Math.floorMod(local, 86400L) / 3600);
    }

    static int minute(long local) {
        return (int) (Math.floorMod(local, 3600L) / 60);
    }

    static String clock(long local) {
        int h = hour(local), m = minute(local);
        if (clock24) return String.format(Locale.US, "%02d:%02d", h, m);
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
    }

    /** "3 PM" or "15:00" for the hourly graph. */
    static String hourLabel(long local) {
        int h = hour(local);
        if (clock24) return String.format(Locale.US, "%02d:00", h);
        if (h == 0) return "12 AM";
        if (h == 12) return "Noon";
        return (h % 12) + (h < 12 ? " AM" : " PM");
    }

    static String shortDay(long local) {
        return DAYS[weekday(local)].substring(0, 3);
    }

    static String longDate(long local) {
        int[] d = date(local);
        return DAYS[weekday(local)] + ", " + MONTHS[d[1] - 1] + " " + d[2];
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
