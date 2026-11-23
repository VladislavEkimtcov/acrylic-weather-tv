package io.github.vladislavekimtcov.acrylicweather;

/** WMO weather interpretation codes (what Open-Meteo returns) to words, icons and skies. */
final class Wmo {
    // Icons
    static final int SUN = 0, MOON = 1, PARTLY_SUN = 2, PARTLY_MOON = 3, CLOUD = 4, OVERCAST = 5,
        FOG = 6, DRIZZLE = 7, RAIN = 8, HEAVY_RAIN = 9, SNOW = 10, SLEET = 11, STORM = 12,
        SHOWERS_SUN = 13, SHOWERS_MOON = 14;

    // Skies
    static final int SKY_CLEAR = 0, SKY_PARTLY = 1, SKY_CLOUDY = 2, SKY_FOG = 3, SKY_DRIZZLE = 4,
        SKY_RAIN = 5, SKY_SNOW = 6, SKY_SLEET = 7, SKY_STORM = 8;

    private Wmo() {}

    static String text(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: return "Fog";
            case 48: return "Freezing fog";
            case 51: return "Light drizzle";
            case 53: return "Drizzle";
            case 55: return "Heavy drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Rain showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: return "Snow showers";
            case 86: return "Heavy snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms with hail";
            default: return code < 0 ? "" : "Unknown";
        }
    }

    static int icon(int code, boolean day) {
        switch (code) {
            case 0: return day ? SUN : MOON;
            case 1: case 2: return day ? PARTLY_SUN : PARTLY_MOON;
            case 3: return OVERCAST;
            case 45: case 48: return FOG;
            case 51: case 53: case 55: case 56: case 57: return DRIZZLE;
            case 61: case 63: return RAIN;
            case 65: return HEAVY_RAIN;
            case 66: case 67: return SLEET;
            case 71: case 73: case 75: case 77: case 85: case 86: return SNOW;
            case 80: case 81: return day ? SHOWERS_SUN : SHOWERS_MOON;
            case 82: return HEAVY_RAIN;
            case 95: case 96: case 99: return STORM;
            default: return CLOUD;
        }
    }

    static int sky(int code) {
        switch (code) {
            case 0: case 1: return SKY_CLEAR;
            case 2: return SKY_PARTLY;
            case 3: return SKY_CLOUDY;
            case 45: case 48: return SKY_FOG;
            case 51: case 53: case 55: case 56: case 57: return SKY_DRIZZLE;
            case 61: case 63: case 65: case 80: case 81: case 82: return SKY_RAIN;
            case 66: case 67: return SKY_SLEET;
            case 71: case 73: case 75: case 77: case 85: case 86: return SKY_SNOW;
            case 95: case 96: case 99: return SKY_STORM;
            default: return SKY_CLOUDY;
        }
    }

    /** 0..1: how hard it rains, for the sky's drops. */
    static float rain(int code) {
        switch (code) {
            case 51: return 0.18f;
            case 53: return 0.28f;
            case 55: return 0.38f;
            case 56: case 57: return 0.3f;
            case 61: return 0.45f;
            case 63: return 0.7f;
            case 65: return 1f;
            case 66: return 0.4f;
            case 67: return 0.65f;
            case 80: return 0.5f;
            case 81: return 0.75f;
            case 82: return 1f;
            case 95: return 0.85f;
            case 96: case 99: return 1f;
            default: return 0;
        }
    }

    /** 0..1: how hard it snows. */
    static float snow(int code) {
        switch (code) {
            case 66: case 67: return 0.3f;
            case 71: return 0.4f;
            case 73: return 0.7f;
            case 75: return 1f;
            case 77: return 0.3f;
            case 85: return 0.6f;
            case 86: return 1f;
            default: return 0;
        }
    }
}
