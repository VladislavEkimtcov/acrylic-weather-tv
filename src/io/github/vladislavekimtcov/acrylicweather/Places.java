package io.github.vladislavekimtcov.acrylicweather;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fixed list of places in the sidebar, and the two settings that go with it. Read from
 * places.conf in the app's media folder (/sdcard/Android/media/&lt;package&gt;/), which adb can
 * write without root on every Fire OS and Android TV version:
 *
 * <pre>
 * # Name | Region | latitude | longitude      the first place opens first
 * Paris | France | 48.8566 | 2.3522
 * units=imperial                               or metric; default follows the TV's region
 * clock=24h                                    or 12h; default follows the TV's setting
 * </pre>
 *
 * With no file the app shows {@link #DEFAULTS}. Nothing here ever asks where the TV is.
 */
final class Places {
    static final String FILE = "places.conf";

    static final String DEFAULTS = ""
            + "New York | New York | 40.7128 | -74.0060\n"
            + "London | United Kingdom | 51.5074 | -0.1278\n"
            + "Tokyo | Japan | 35.6762 | 139.6503\n"
            + "Sydney | Australia | -33.8688 | 151.2093\n"
            + "Reykjavik | Iceland | 64.1466 | -21.9426\n"
            + "Dubai | United Arab Emirates | 25.2048 | 55.2708\n";

    static final class Place {
        final String name, region;
        final double lat, lon;

        Place(String name, String region, double lat, double lon) {
            this.name = name;
            this.region = region;
            this.lat = lat;
            this.lon = lon;
        }

        String key() {
            return String.format(Locale.US, "%.4f,%.4f", lat, lon);
        }
    }

    final List<Place> list = new ArrayList<>();
    final Map<String, String> settings = new LinkedHashMap<>();
    /** Where the list came from, for the sidebar's footer. */
    String source = "built-in list";

    static Places load(File mediaDir) {
        File f = mediaDir == null ? null : new File(mediaDir, FILE);
        if (f != null && f.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Places p = parse(r);
                if (!p.list.isEmpty()) {
                    p.source = FILE;
                    return p;
                }
            } catch (IOException e) {
                // fall through to the defaults
            }
        }
        try {
            return parse(new StringReader(DEFAULTS));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Places parse(Reader in) throws IOException {
        Places p = new Places();
        BufferedReader r = new BufferedReader(in);
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.indexOf('|') < 0) {
                int eq = line.indexOf('=');
                if (eq > 0) p.settings.put(line.substring(0, eq).trim().toLowerCase(Locale.US), line.substring(eq + 1).trim());
                continue;
            }
            String[] f = line.split("\\|");
            if (f.length < 4) continue;
            try {
                double lat = Double.parseDouble(f[2].trim()), lon = Double.parseDouble(f[3].trim());
                if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
                p.list.add(new Place(f[0].trim(), f[1].trim(), lat, lon));
            } catch (NumberFormatException e) {
                // skip the line
            }
            if (p.list.size() == 12) break; // a sidebar, not a database
        }
        return p;
    }

    /** Same places in the same order: the cached forecasts still line up. */
    String key() {
        StringBuilder b = new StringBuilder();
        for (Place pl : list) b.append(pl.key()).append(';');
        return b.toString();
    }
}
