package io.github.vladislavekimtcov.acrylicweather;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One place's forecast from Open-Meteo, metric, with times in local epoch seconds ({@link Time}). */
final class Forecast {
    int utcOffset;
    /** When the data was fetched, real epoch seconds. */
    long fetched;

    // Now
    long time;
    double temp, feels, humidity, dew, pressure, wind, windDir, gusts, visibility, uv, cloud, precip;
    int code;
    boolean day;
    int aqi = -1;

    // Hourly
    long[] hTime = new long[0];
    double[] hTemp = new double[0];
    int[] hPop = new int[0], hCode = new int[0];
    boolean[] hDay = new boolean[0];

    // Daily
    long[] dTime = new long[0], sunrise = new long[0], sunset = new long[0];
    double[] dMax = new double[0], dMin = new double[0], dPrecip = new double[0], dUv = new double[0],
        dWind = new double[0], dWindDir = new double[0];
    int[] dCode = new int[0], dPop = new int[0];

    static final String CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
            + "precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,"
            + "wind_gusts_10m,visibility,dew_point_2m,uv_index";
    static final String HOURLY = "temperature_2m,precipitation_probability,weather_code,is_day";
    static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
            + "precipitation_probability_max,precipitation_sum,uv_index_max,wind_speed_10m_max,"
            + "wind_direction_10m_dominant";

    static Forecast parse(JSONObject o, long fetched) throws JSONException {
        Forecast f = new Forecast();
        f.fetched = fetched;
        f.utcOffset = o.optInt("utc_offset_seconds");
        JSONObject c = o.getJSONObject("current");
        f.time = Time.parse(c.optString("time"));
        f.temp = num(c, "temperature_2m");
        f.feels = num(c, "apparent_temperature");
        f.humidity = num(c, "relative_humidity_2m");
        f.dew = num(c, "dew_point_2m");
        f.pressure = num(c, "pressure_msl");
        f.wind = num(c, "wind_speed_10m");
        f.windDir = num(c, "wind_direction_10m");
        f.gusts = num(c, "wind_gusts_10m");
        f.visibility = num(c, "visibility");
        f.uv = num(c, "uv_index");
        f.cloud = num(c, "cloud_cover");
        f.precip = num(c, "precipitation");
        f.code = c.optInt("weather_code", -1);
        f.day = c.optInt("is_day", 1) == 1;

        JSONObject h = o.getJSONObject("hourly");
        f.hTime = times(h.getJSONArray("time"));
        int n = f.hTime.length;
        f.hTemp = nums(h.optJSONArray("temperature_2m"), n);
        f.hPop = ints(h.optJSONArray("precipitation_probability"), n, 0);
        f.hCode = ints(h.optJSONArray("weather_code"), n, -1);
        int[] day = ints(h.optJSONArray("is_day"), n, 1);
        f.hDay = new boolean[n];
        for (int i = 0; i < n; i++) f.hDay[i] = day[i] == 1;

        JSONObject d = o.getJSONObject("daily");
        f.dTime = times(d.getJSONArray("time"));
        int m = f.dTime.length;
        f.dCode = ints(d.optJSONArray("weather_code"), m, -1);
        f.dMax = nums(d.optJSONArray("temperature_2m_max"), m);
        f.dMin = nums(d.optJSONArray("temperature_2m_min"), m);
        f.sunrise = times(d.optJSONArray("sunrise"), m);
        f.sunset = times(d.optJSONArray("sunset"), m);
        f.dPop = ints(d.optJSONArray("precipitation_probability_max"), m, 0);
        f.dPrecip = nums(d.optJSONArray("precipitation_sum"), m);
        f.dUv = nums(d.optJSONArray("uv_index_max"), m);
        f.dWind = nums(d.optJSONArray("wind_speed_10m_max"), m);
        f.dWindDir = nums(d.optJSONArray("wind_direction_10m_dominant"), m);
        return f;
    }

    /** The place's wall clock right now, in local epoch seconds. */
    long now() {
        return Time.now() + utcOffset;
    }

    /** Index of today in the daily arrays (0 when the data is fresh). */
    int today() {
        long now = now();
        for (int i = dTime.length - 1; i >= 0; i--) if (dTime[i] <= now) return i;
        return 0;
    }

    /** Index of the current hour in the hourly arrays. */
    int hourIndex(long at) {
        for (int i = hTime.length - 1; i >= 0; i--) if (hTime[i] <= at) return i;
        return 0;
    }

    /** 0 at night, 1 at noon-ish, between at dawn and dusk; for the sky's colours. */
    boolean isDayAt(long at) {
        int di = dayOf(at);
        if (di < 0 || di >= sunrise.length || sunrise[di] < 0) return day;
        return at >= sunrise[di] && at < sunset[di];
    }

    int dayOf(long at) {
        for (int i = dTime.length - 1; i >= 0; i--) if (dTime[i] <= at) return i;
        return -1;
    }

    private static double num(JSONObject o, String k) {
        return o.isNull(k) ? Double.NaN : o.optDouble(k, Double.NaN);
    }

    private static long[] times(JSONArray a) {
        return times(a, a == null ? 0 : a.length());
    }

    private static long[] times(JSONArray a, int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) r[i] = a == null || i >= a.length() ? -1 : Time.parse(a.optString(i));
        return r;
    }

    private static double[] nums(JSONArray a, int n) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = a == null || i >= a.length() || a.isNull(i) ? Double.NaN : a.optDouble(i);
        return r;
    }

    private static int[] ints(JSONArray a, int n, int dflt) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = a == null || i >= a.length() || a.isNull(i) ? dflt : a.optInt(i, dflt);
        return r;
    }
}
