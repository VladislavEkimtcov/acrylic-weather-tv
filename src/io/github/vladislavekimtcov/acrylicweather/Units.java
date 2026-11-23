package io.github.vladislavekimtcov.acrylicweather;

import java.util.Locale;

/** Forecasts arrive metric; everything shown goes through here, so the °F/°C switch is instant. */
final class Units {
    private static final String[] COMPASS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    static boolean imperial = true;

    private Units() {}

    /** Fahrenheit for the US and the two other countries that use it, Celsius elsewhere. */
    static boolean defaultImperial(Locale l) {
        String c = l.getCountry();
        return "US".equals(c) || "LR".equals(c) || "MM".equals(c);
    }

    static int deg(double celsius) {
        return (int) Math.round(imperial ? celsius * 9 / 5 + 32 : celsius);
    }

    static String temp(double celsius) {
        if (Double.isNaN(celsius)) return "--";
        return deg(celsius) + "°";
    }

    static String unit() {
        return imperial ? "F" : "C";
    }

    static String speed(double kmh) {
        if (Double.isNaN(kmh)) return "--";
        return imperial ? Math.round(kmh / 1.609344) + " mph" : Math.round(kmh) + " km/h";
    }

    static String pressure(double hpa) {
        if (Double.isNaN(hpa)) return "--";
        return imperial ? String.format(Locale.US, "%.2f in", hpa * 0.0295299830714) : Math.round(hpa) + " hPa";
    }

    static String distance(double meters) {
        if (Double.isNaN(meters)) return "--";
        double v = imperial ? meters / 1609.344 : meters / 1000;
        String u = imperial ? " mi" : " km";
        return (v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v)) + u;
    }

    static String precip(double mm) {
        if (Double.isNaN(mm)) return "--";
        if (imperial) {
            double in = mm / 25.4;
            return in > 0 && in < 0.01 ? "< 0.01 in" : String.format(Locale.US, "%.2f in", in);
        }
        return mm > 0 && mm < 0.1 ? "< 0.1 mm" : String.format(Locale.US, "%.1f mm", mm);
    }

    static String compass(double deg) {
        if (Double.isNaN(deg)) return "";
        int i = (int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16;
        return COMPASS[i];
    }

    static String uvLabel(double uv) {
        if (Double.isNaN(uv)) return "";
        if (uv < 3) return "Low";
        if (uv < 6) return "Moderate";
        if (uv < 8) return "High";
        if (uv < 11) return "Very high";
        return "Extreme";
    }

    static String aqiLabel(int aqi) {
        if (aqi < 0) return "";
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for some";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    static int aqiColor(int aqi) {
        if (aqi <= 50) return 0xFF5BD46A;
        if (aqi <= 100) return 0xFFF5D547;
        if (aqi <= 150) return 0xFFFF9B3D;
        if (aqi <= 200) return 0xFFFF5A4E;
        if (aqi <= 300) return 0xFFB06BE0;
        return 0xFF9C3B4F;
    }
}
