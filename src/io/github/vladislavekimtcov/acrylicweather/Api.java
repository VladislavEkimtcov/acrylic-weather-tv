package io.github.vladislavekimtcov.acrylicweather;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Open-Meteo (https://open-meteo.com, CC BY 4.0): free, no key, no account. Every place goes in
 * one request, and the answer is cached on disk so the app opens on the last forecast at once.
 */
final class Api {
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private static final String AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";

    /** Set by Main from the package version. */
    static String userAgent = "AcrylicWeather (Android TV)";

    private Api() {}

    /** Fetches every place's forecast (and, best effort, air quality). Network thread only. */
    static Forecast[] fetch(List<Places.Place> places, File cacheDir, String key) throws IOException, JSONException {
        String lat = coords(places, true), lon = coords(places, false);
        String json = get(FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=" + Forecast.CURRENT + "&hourly=" + Forecast.HOURLY + "&daily=" + Forecast.DAILY
                + "&timezone=auto&forecast_days=10");
        String air = null;
        try {
            air = get(AIR + "?latitude=" + lat + "&longitude=" + lon + "&current=us_aqi&timezone=auto");
        } catch (IOException e) {
            // the forecast is enough
        }
        long now = Time.now();
        Forecast[] r = parse(json, air, places.size(), now);
        // Raw newlines in JSON can only be whitespace, so one line each keeps the file splittable.
        write(new File(cacheDir, "forecast.json"), key + "\n" + now + "\n" + oneLine(json) + "\n" + (air == null ? "" : oneLine(air)));
        return r;
    }

    /** The last forecast, if it was for the same places; null otherwise. */
    static Forecast[] cached(File cacheDir, String key, int count) {
        try {
            String s = read(new File(cacheDir, "forecast.json"));
            String[] parts = s.split("\n", 4);
            if (parts.length < 3 || !parts[0].equals(key)) return null;
            return parse(parts[2], parts.length > 3 ? parts[3] : null, count, Long.parseLong(parts[1]));
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    private static Forecast[] parse(String json, String air, int count, long fetched) throws JSONException {
        Forecast[] r = new Forecast[count];
        Object root = new JSONArray("[" + json + "]").get(0);
        for (int i = 0; i < count; i++) {
            JSONObject o = root instanceof JSONArray ? ((JSONArray) root).getJSONObject(i) : (JSONObject) root;
            r[i] = Forecast.parse(o, fetched);
        }
        if (air != null && !air.trim().isEmpty()) {
            try {
                Object a = new JSONArray("[" + air + "]").get(0);
                for (int i = 0; i < count; i++) {
                    JSONObject o = a instanceof JSONArray ? ((JSONArray) a).getJSONObject(i) : (JSONObject) a;
                    JSONObject cur = o.optJSONObject("current");
                    if (cur != null && !cur.isNull("us_aqi")) r[i].aqi = cur.optInt("us_aqi", -1);
                }
            } catch (JSONException e) {
                // no air quality then
            }
        }
        return r;
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String coords(List<Places.Place> places, boolean lat) {
        StringBuilder b = new StringBuilder();
        for (Places.Place p : places) {
            if (b.length() > 0) b.append(',');
            b.append(String.format(Locale.US, "%.4f", lat ? p.lat : p.lon));
        }
        return b.toString();
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", userAgent);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
