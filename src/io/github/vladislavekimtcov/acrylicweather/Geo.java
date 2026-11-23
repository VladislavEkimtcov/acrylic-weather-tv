package io.github.vladislavekimtcov.acrylicweather;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** City search for the "+" button: Open-Meteo's geocoding API (GeoNames data), no key. */
final class Geo {
    private static final String SEARCH = "https://geocoding-api.open-meteo.com/v1/search";

    private Geo() {}

    static final class Hit {
        final String name, admin1, country, countryCode;
        final double lat, lon;

        Hit(String name, String admin1, String country, String countryCode, double lat, double lon) {
            this.name = name;
            this.admin1 = admin1;
            this.country = country;
            this.countryCode = countryCode;
            this.lat = lat;
            this.lon = lon;
        }

        /** What the search list shows under the name: state or province, country. */
        String where() {
            if (admin1.isEmpty() || admin1.equals(name) && !country.isEmpty()) return country;
            return country.isEmpty() ? admin1 : admin1 + ", " + country;
        }

        /** As it goes in the sidebar: the state for US places (like Windows 10), else the country. */
        Places.Place toPlace() {
            String region = "US".equals(countryCode) && !admin1.isEmpty() ? admin1 : country.isEmpty() ? admin1 : country;
            return new Places.Place(Places.clean(name), Places.clean(region),
                    Math.round(lat * 1e4) / 1e4, Math.round(lon * 1e4) / 1e4);
        }
    }

    /** Up to eight matches, biggest first as Open-Meteo ranks them. Network thread only. */
    static List<Hit> search(String query) throws IOException, JSONException {
        List<Hit> out = new ArrayList<>();
        String q = query.trim();
        if (q.length() < 2) return out;
        String json = Api.get(SEARCH + "?name=" + URLEncoder.encode(q, "UTF-8") + "&count=8&language=en&format=json");
        JSONArray a = new JSONObject(json).optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (!o.has("latitude") || !o.has("longitude")) continue;
            out.add(new Hit(o.optString("name"), o.optString("admin1"), o.optString("country"),
                    o.optString("country_code").toUpperCase(Locale.US), o.getDouble("latitude"), o.getDouble("longitude")));
        }
        return out;
    }
}
