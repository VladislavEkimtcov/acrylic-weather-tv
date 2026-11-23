package io.github.vladislavekimtcov.acrylicweather;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * With no file the app shows {@link #DEFAULTS}. Places added, deleted or moved on the TV are
 * written back to the same file ({@link #format}). Nothing here ever asks where the TV is.
 */
final class Places {
    static final String FILE = "places.conf";
    /** A sidebar, not a database. */
    static final int MAX = 12;

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
            if (p.list.size() == MAX) break;
        }
        return p;
    }

    /** Names go between '|' on one line: no pipes, no line breaks, no leading '#'. */
    static String clean(String s) {
        String t = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
        while (t.startsWith("#")) t = t.substring(1).trim();
        return t.isEmpty() ? "?" : t;
    }

    int indexOf(Place p) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).key().equals(p.key())) return i;
        return -1;
    }

    /** The file as the app writes it back: places in order, then the settings. */
    String format() {
        StringBuilder b = new StringBuilder();
        b.append("# Acrylic Weather places: Name | Region | latitude | longitude. The first one opens first.\n");
        b.append("# The app rewrites this file when places are added, deleted or moved on the TV.\n");
        for (Place p : list) {
            b.append(clean(p.name)).append(" | ").append(clean(p.region)).append(" | ")
                    .append(String.format(Locale.US, "%.4f | %.4f", p.lat, p.lon)).append('\n');
        }
        for (Map.Entry<String, String> e : settings.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return b.toString();
    }

    /** Writes {@link #format} to dir/places.conf; false if it couldn't. */
    boolean save(File dir) {
        if (dir == null) return false;
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        byte[] bytes = format().getBytes(StandardCharsets.UTF_8);
        File f = new File(dir, FILE), tmp = new File(dir, FILE + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            return false;
        }
        if (tmp.renameTo(f)) {
            source = FILE;
            return true;
        }
        tmp.delete();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
            source = FILE;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Same places in the same order: the cached forecasts still line up. */
    String key() {
        StringBuilder b = new StringBuilder();
        for (Place pl : list) b.append(pl.key()).append(';');
        return b.toString();
    }
}
