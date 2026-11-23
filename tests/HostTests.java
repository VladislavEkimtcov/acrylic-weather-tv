package io.github.vladislavekimtcov.acrylicweather;

import java.io.StringReader;
import java.util.Locale;

/** Plain-JVM checks of the logic that needs no Android: run tests/run.sh. */
public final class HostTests {
    private static int failures;

    private static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] args) throws Exception {
        // Local epoch seconds round-trip through the civil-date maths.
        long t = Time.parse("2026-11-22T15:15");
        eq(Long.valueOf(Time.days(2026, 11, 22) * 86400 + 15 * 3600 + 15 * 60), t, "parse");
        int[] d = Time.date(t);
        eq("2026-11-22", String.format(Locale.US, "%04d-%02d-%02d", d[0], d[1], d[2]), "date");
        eq("Sunday", Time.DAYS[Time.weekday(t)], "weekday");
        eq("Wednesday", Time.DAYS[Time.weekday(Time.parse("2000-03-01"))], "weekday 2000-03-01");
        eq(Long.valueOf(-1), Time.parse("garbage"), "unreadable time");
        Time.clock24 = false;
        eq("3:15 PM", Time.clock(t), "12 h clock");
        eq("Noon", Time.hourLabel(Time.parse("2026-11-22T12:00")), "noon label");
        Time.clock24 = true;
        eq("15:15", Time.clock(t), "24 h clock");

        Units.imperial = true;
        eq("50°", Units.temp(10), "10 C in F");
        eq("6 mph", Units.speed(10), "10 km/h in mph");
        eq("NW", Units.compass(315), "compass");
        eq("N", Units.compass(359), "compass wraps");
        Units.imperial = false;
        eq("-3°", Units.temp(-3.4), "metric temp");
        eq("1013 hPa", Units.pressure(1013.2), "pressure");
        eq(Boolean.TRUE, Units.defaultImperial(Locale.US), "US is imperial");
        eq(Boolean.FALSE, Units.defaultImperial(Locale.UK), "UK is metric");

        eq("Thunderstorms", Wmo.text(95, true), "storm text");
        eq(Integer.valueOf(Wmo.MOON), Integer.valueOf(Wmo.icon(0, false)), "clear night icon");
        eq(Integer.valueOf(Wmo.SKY_SNOW), Integer.valueOf(Wmo.sky(73)), "snow sky");

        Places p = Places.parse(new StringReader("# comment\nunits=metric\nHome | Somewhere | 40.1 | -88.2\n"
                + "Bad | line | x | y\nFar | Away | 95 | 0\nTwo | Place | -33.9 | 151.2\n"));
        eq(Integer.valueOf(2), Integer.valueOf(p.list.size()), "places kept");
        eq("Home", p.list.get(0).name, "first place");
        eq("metric", p.settings.get("units"), "setting");
        eq("40.1000,-88.2000;-33.9000,151.2000;", p.key(), "key");
        eq(Integer.valueOf(6), Integer.valueOf(Places.parse(new StringReader(Places.DEFAULTS)).list.size()), "defaults parse");

        // What the app writes back after an edit on the TV reads back the same.
        p.list.add(new Places.Place(Places.clean("Odd | name\n"), Places.clean("# Region"), 1.23456, -4.5));
        Places back = Places.parse(new StringReader(p.format()));
        eq(p.key(), back.key(), "format round-trip keys");
        eq("Odd / name", back.list.get(2).name, "pipe cleaned");
        eq("Region", back.list.get(2).region, "leading # cleaned");
        eq("metric", back.settings.get("units"), "settings kept");
        eq(Integer.valueOf(2), Integer.valueOf(back.indexOf(new Places.Place("x", "y", 1.2346, -4.5))), "indexOf by coordinates");

        System.out.println(failures == 0 ? "all host tests passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
