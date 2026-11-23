package io.github.vladislavekimtcov.acrylicweather;

/**
 * Which pre-rendered sky fits a forecast at a moment, and each sky's two key colours (the veil
 * that covers a switch, and the still fallback). The colours are the skies' top and bottom in tools/sky/SkyRender.swift.
 */
final class Scenes {
    static final String[] ALL = {"clear-day", "clear-night", "partly-day", "partly-night", "cloudy-day",
        "cloudy-night", "fog-day", "fog-night", "rain-day", "rain-night", "snow-day", "snow-night", "storm", "dusk"};

    private Scenes() {}

    /** The scene for the place's weather {@code code} at local time {@code at}. */
    static String pick(Forecast f, long at, int code) {
        int kind = Wmo.sky(code);
        boolean day = f.isDayAt(at);
        int di = Math.max(0, f.dayOf(at));
        if ((kind == Wmo.SKY_CLEAR || kind == Wmo.SKY_PARTLY) && di < f.sunrise.length) {
            long rise = f.sunrise[di], set = f.sunset[di];
            // Within ~40 minutes of sunrise or sunset under a mostly clear sky: golden hour.
            if (rise > 0 && (Math.abs(at - rise) < 2400 || Math.abs(at - set) < 2400)) return "dusk";
        }
        String half = day ? "-day" : "-night";
        switch (kind) {
            case Wmo.SKY_CLEAR: return "clear" + half;
            case Wmo.SKY_PARTLY: return "partly" + half;
            case Wmo.SKY_CLOUDY: return "cloudy" + half;
            case Wmo.SKY_FOG: return "fog" + half;
            case Wmo.SKY_SNOW: return "snow" + half;
            case Wmo.SKY_STORM: return "storm";
            default: return "rain" + half; // drizzle, rain, sleet
        }
    }

    /** {top, bottom} of the scene's sky. */
    static int[] colors(String scene) {
        switch (scene) {
            case "clear-day": return new int[] {0xFF1557B0, 0xFF8DBDE8};
            case "clear-night": return new int[] {0xFF030817, 0xFF1D3160};
            case "partly-day": return new int[] {0xFF2160A8, 0xFF9CC1E3};
            case "partly-night": return new int[] {0xFF050B1B, 0xFF23355C};
            case "cloudy-day": return new int[] {0xFF3E4D5F, 0xFF84919F};
            case "cloudy-night": return new int[] {0xFF0C1119, 0xFF2A3340};
            case "fog-day": return new int[] {0xFF5D6873, 0xFF9EA7B0};
            case "fog-night": return new int[] {0xFF12171E, 0xFF384049};
            case "rain-day": return new int[] {0xFF2F3B48, 0xFF66737F};
            case "rain-night": return new int[] {0xFF080C12, 0xFF232D39};
            case "snow-day": return new int[] {0xFF4A5A6E, 0xFF93A1B2};
            case "snow-night": return new int[] {0xFF111824, 0xFF3A4860};
            case "storm": return new int[] {0xFF12161C, 0xFF39424E};
            case "dusk": return new int[] {0xFF26346E, 0xFFF09A62};
            default: return new int[] {0xFF0B1426, 0xFF1C2A44};
        }
    }
}
