package io.github.vladislavekimtcov.acrylicweather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole app: places down the left, the selected place's weather on the right, the sky behind.
 *
 * Remote: up/down in the sidebar switches places; right goes to the forecast. In the daily strip,
 * left/right picks a day (the sky previews it, the hourly graph and day details follow). Down to
 * the hourly graph, where left/right reads out each hour. Up from the days reaches °F | °C.
 * Menu refreshes. Back from the forecast returns to the sidebar, Back from the sidebar exits.
 */
public final class Main extends Activity {
    private static final long STALE_MS = 10 * 60 * 1000L;
    private static final int DAYS = 10;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Icons icons = new Icons();
    private SharedPreferences prefs;

    private Places places;
    private Forecast[] data;
    private int current;
    private int dayIndex;
    private long lastFetchMs;
    private boolean fetching;
    private String error;

    private Backdrop backdrop;
    private View veil;
    /** Playing (or fading to) and asked for; the veil covers each switch. */
    private String scene, wantScene;
    private File mediaDir;
    private LinearLayout rowsBox;
    private final List<PlaceRow> rows = new ArrayList<>();
    private TextView updated, source;
    private Scroller scroller;
    private LinearLayout page;
    private final Section[] sections = new Section[4];
    private TextView city, region, localTime, temp, cond, feels, hilo, hourlyTitle, detailsTitle, summary;
    private UnitToggle units;
    private IconView heroIcon;
    private final TextView[] metricValue = new TextView[6];
    private final TextView[] metricLabel = new TextView[6];
    private final DayTile[] days = new DayTile[DAYS];
    private HourlyChart chart;
    private final DetailTile[] details = new DetailTile[6];
    private int shownDeg = Integer.MIN_VALUE;
    private ValueAnimator tempAnim;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshClocks();
            if (System.currentTimeMillis() - lastFetchMs > STALE_MS) fetch();
            ui.postDelayed(this, 30_000);
        }
    };

    private final Runnable pendingPlace = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).isFocused() && i != current) {
                    show(i, true);
                    return;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        try {
            Api.userAgent = "AcrylicWeather/" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (Android TV)";
        } catch (PackageManager.NameNotFoundException e) {
            // keep the default
        }
        File[] media = getExternalMediaDirs();
        mediaDir = media != null && media.length > 0 ? media[0] : null;
        setContentView(build());
        loadPlaces();
        data = Api.cached(getCacheDir(), places.key(), places.list.size());
        if (data != null) lastFetchMs = data[0].fetched * 1000;
        buildRows();
        show(0, false);
        days[0].requestFocus();
        entrance();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String before = places.key();
        loadPlaces();
        if (!before.equals(places.key())) {
            data = Api.cached(getCacheDir(), places.key(), places.list.size());
            lastFetchMs = data == null ? 0 : data[0].fetched * 1000;
            buildRows();
            show(0, false);
        } else {
            show(current, false);
        }
        backdrop.setRunning(true);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Give the video decoder back while away; on return the veil fades the sky in again.
        backdrop.setRunning(false);
        veil.animate().cancel();
        veil.setVisibility(View.VISIBLE);
        veil.setAlpha(1f);
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- settings and data -------------------------------------------------------------------

    private void loadPlaces() {
        File[] media = getExternalMediaDirs();
        File dir = media != null && media.length > 0 ? media[0] : null;
        places = Places.load(dir);
        if (dir != null && !new File(dir, Places.FILE).isFile()) writeExample(dir);
        String u = prefs.getString("units", places.settings.get("units"));
        Units.imperial = u == null ? Units.defaultImperial(Locale.getDefault()) : !u.startsWith("m");
        String clock = places.settings.get("clock");
        Time.clock24 = clock == null ? android.text.format.DateFormat.is24HourFormat(this) : clock.startsWith("24");
        if (current >= places.list.size()) current = 0;
    }

    /** Leaves a commented example next to where places.conf goes, for adb pull / edit / push. */
    private static void writeExample(File dir) {
        File f = new File(dir, Places.FILE + ".example");
        if (f.isFile()) return;
        String s = "# Acrylic Weather places. Edit, then: adb push places.conf " + dir.getPath() + "/places.conf\n"
                + "# One place per line: Name | Region | latitude | longitude. The first one opens first.\n"
                + "# Find coordinates: https://geocoding-api.open-meteo.com/v1/search?name=Paris\n"
                + "# units=imperial or metric, clock=12h or 24h (both optional)\n"
                + Places.DEFAULTS;
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // only a convenience
        }
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        final Places ps = places;
        final String key = ps.key();
        final File cache = getCacheDir();
        io.execute(() -> {
            Forecast[] got = null;
            String err = null;
            try {
                got = Api.fetch(ps.list, cache, key);
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            final Forecast[] result = got;
            final String failure = err;
            ui.post(() -> {
                fetching = false;
                if (!key.equals(places.key())) return;
                if (result != null) {
                    data = result;
                    error = null;
                    lastFetchMs = System.currentTimeMillis();
                    show(current, false);
                } else {
                    error = failure;
                    // Try again sooner than the usual ten minutes.
                    lastFetchMs = System.currentTimeMillis() - STALE_MS + 45_000;
                    refreshClocks();
                    if (data == null) show(current, false);
                }
            });
        });
    }

    // ---- layout ------------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this, mediaDir);
        backdrop.setListener(new Backdrop.Listener() {
            @Override
            public void onShown(String shown) {
                if (veil.getVisibility() != View.VISIBLE) return; // a crossfade, done in the backdrop
                veil.animate().cancel();
                veil.animate().alpha(0f).setDuration(700).setInterpolator(EASE)
                        .withEndAction(() -> veil.setVisibility(View.INVISIBLE)).start();
            }

            @Override
            public void onFailed(String failed, String why) {
                veil.animate().cancel();
                veil.setVisibility(View.VISIBLE);
                veil.setAlpha(1f); // the still gradient stays
            }
        });
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        // Covers the switch between two skies with the new one's colours; also the still
        // fallback if a clip can't play. Fully transparent (and not drawn) the rest of the time.
        veil = new View(this);
        setVeil(Scenes.colors(""));
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        row.addView(sidebar(), new LinearLayout.LayoutParams(Ui.dp(232), -1));

        scroller = new Scroller(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(Ui.dp(38), Ui.dp(24), Ui.dp(38), Ui.dp(36));
        scroller.addView(page, new FrameLayout.LayoutParams(-1, -2));
        row.addView(scroller, new LinearLayout.LayoutParams(0, -1, 1));
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new Section(this);
            page.addView(sections[i], new LinearLayout.LayoutParams(-1, -2));
        }
        // Blocks more than ~120 dp off screen are not drawn at all.
        scroller.setWatcher((top, bottom) -> {
            int margin = Ui.dp(120);
            for (Section sec : sections) {
                sec.setCulled(sec.getBottom() < top - margin || sec.getTop() > bottom + margin);
            }
        });

        hero(sections[0]);
        Section dailyBox = sections[1], hourlyBox = sections[2], detailsBox = sections[3];
        dailyBox.addView(sectionLabel("Daily"), lp(-1, -2, 0, 24));
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        for (int i = 0; i < DAYS; i++) {
            final int k = i;
            days[i] = new DayTile(this, icons);
            days[i].setId(View.generateViewId());
            days[i].setOnFocusChangeListener((v, has) -> {
                if (has) selectDay(k, true);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(140), 1);
            if (i < DAYS - 1) p.rightMargin = Ui.dp(6);
            strip.addView(days[i], p);
        }
        dailyBox.addView(strip, lp(-1, -2, 0, 10));

        hourlyTitle = sectionLabel("Hourly");
        hourlyBox.addView(hourlyTitle, lp(-1, -2, 0, 26));
        chart = new HourlyChart(this, icons);
        chart.setId(View.generateViewId());
        chart.setListener(this::hourReadout);
        hourlyBox.addView(chart, lp(-1, Ui.dp(172), 0, 10));

        detailsTitle = sectionLabel("Day details");
        detailsBox.addView(detailsTitle, lp(-1, -2, 0, 26));
        summary = Ui.text(this, 16, Ui.MUTED, Ui.light);
        summary.setSingleLine(false);
        summary.setLineSpacing(0, 1.25f);
        detailsBox.addView(summary, lp(-1, -2, 0, 8));
        int[] kinds = {DetailTile.SUN, DetailTile.MOON, DetailTile.WIND, DetailTile.RAIN, DetailTile.UV, DetailTile.AIR};
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setClipChildren(false);
            for (int i = 0; i < 3; i++) {
                int k = r * 3 + i;
                details[k] = new DetailTile(this, kinds[k]);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(108), 1);
                if (i < 2) p.rightMargin = Ui.dp(8);
                line.addView(details[k], p);
            }
            detailsBox.addView(line, lp(-1, -2, 0, r == 0 ? 12 : 8));
        }
        TextView credit = Ui.text(this, 11, Ui.FAINT, Ui.regular);
        credit.setText("Weather data by Open-Meteo.com (CC BY 4.0). No location is ever read: places come from places.conf.");
        credit.setSingleLine(false);
        detailsBox.addView(credit, lp(-1, -2, 0, 20));
        return root;
    }

    private View sidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setClipChildren(false);
        side.setPadding(0, Ui.dp(22), 0, Ui.dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x8C0A0F18, 0x7A0A0F18});
        side.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(20), 0, Ui.dp(16), 0);
        head.addView(new Hamburger(this), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(16)));
        TextView title = Ui.text(this, 20, Ui.WHITE, Ui.light);
        title.setText("Weather");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = Ui.dp(16);
        head.addView(title, tp);
        side.addView(head);

        TextView label = sectionLabel("Places");
        label.setPadding(Ui.dp(20), 0, 0, 0);
        side.addView(label, lp(-1, -2, 0, 26));

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        side.addView(rowsBox, lp(-1, -2, 0, 8));

        side.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        updated = Ui.text(this, 12, Ui.MUTED, Ui.regular);
        updated.setPadding(Ui.dp(20), 0, Ui.dp(12), 0);
        updated.setSingleLine(false);
        side.addView(updated);
        source = Ui.text(this, 11, Ui.FAINT, Ui.regular);
        source.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(12), 0);
        side.addView(source);
        return side;
    }

    private void hero(LinearLayout box) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        city = Ui.text(this, 30, Ui.WHITE, Ui.light);
        region = Ui.text(this, 16, Ui.MUTED, Ui.light);
        localTime = Ui.text(this, 16, Ui.MUTED, Ui.light);
        top.addView(city);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = Ui.dp(12);
        rp.bottomMargin = Ui.dp(4);
        top.addView(region, rp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.bottomMargin = Ui.dp(4);
        top.addView(localTime, tp);
        box.addView(top, lp(-1, -2, 0, 0));

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setClipChildren(false);
        heroIcon = new IconView(this, icons);
        now.addView(heroIcon, new LinearLayout.LayoutParams(Ui.dp(118), Ui.dp(118)));
        temp = Ui.text(this, 104, Ui.WHITE, Ui.thin);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Ui.dp(10);
        now.addView(temp, tlp);
        units = new UnitToggle(this);
        units.setOnClickListener(v -> toggleUnits());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.gravity = Gravity.TOP;
        ulp.topMargin = Ui.dp(18);
        ulp.leftMargin = Ui.dp(6);
        now.addView(units, ulp);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        cond = Ui.text(this, 28, Ui.WHITE, Ui.light);
        feels = Ui.text(this, 16, Ui.MUTED, Ui.regular);
        hilo = Ui.text(this, 16, Ui.MUTED, Ui.regular);
        words.addView(cond);
        words.addView(feels, lp(-2, -2, 0, 8));
        words.addView(hilo, lp(-2, -2, 0, 4));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1);
        wlp.leftMargin = Ui.dp(30);
        now.addView(words, wlp);
        box.addView(now, lp(-1, -2, 0, 6));

        LinearLayout metrics = new LinearLayout(this);
        for (int i = 0; i < metricValue.length; i++) {
            LinearLayout m = new LinearLayout(this);
            m.setOrientation(LinearLayout.VERTICAL);
            metricLabel[i] = Ui.text(this, 12, Ui.FAINT, Ui.regular);
            metricValue[i] = Ui.text(this, 17, Ui.WHITE, Ui.light);
            m.addView(metricLabel[i]);
            m.addView(metricValue[i], lp(-2, -2, 0, 4));
            metrics.addView(m, new LinearLayout.LayoutParams(0, -2, 1));
        }
        box.addView(metrics, lp(-1, -2, 0, 10));
    }

    private void buildRows() {
        rowsBox.removeAllViews();
        rows.clear();
        for (int i = 0; i < places.list.size(); i++) {
            PlaceRow r = new PlaceRow(this, icons);
            r.setId(View.generateViewId());
            final int k = i;
            r.setOnFocusChangeListener((v, has) -> {
                if (has && k != current) {
                    ui.removeCallbacks(pendingPlace);
                    ui.postDelayed(pendingPlace, 220);
                }
            });
            r.setOnClickListener(v -> {
                ui.removeCallbacks(pendingPlace);
                if (k != current) show(k, true);
                days[dayIndex].requestFocus();
            });
            rows.add(r);
            rowsBox.addView(r, new LinearLayout.LayoutParams(-1, Ui.dp(62)));
        }
        if (!rows.isEmpty()) {
            rows.get(0).setNextFocusUpId(rows.get(0).getId());
            rows.get(rows.size() - 1).setNextFocusDownId(rows.get(rows.size() - 1).getId());
        }
        source.setText("Open-Meteo · " + places.source);
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, 13, Ui.MUTED, Ui.semibold);
        t.setText(s);
        t.setLetterSpacing(0.04f);
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }

    // ---- binding -----------------------------------------------------------------------------

    private void show(int index, boolean animate) {
        current = Math.max(0, Math.min(index, places.list.size() - 1));
        Places.Place pl = places.list.get(current);
        for (int i = 0; i < rows.size(); i++) rows.get(i).setCurrent(i == current);
        int leftTarget = rows.isEmpty() ? View.NO_ID : rows.get(current).getId();
        days[0].setNextFocusLeftId(leftTarget);
        chart.setNextFocusLeftId(leftTarget);
        details[0].setNextFocusLeftId(leftTarget);
        details[3].setNextFocusLeftId(leftTarget);
        units.setNextFocusLeftId(leftTarget);
        city.setText(pl.name);
        region.setText(pl.region);
        refreshRows();

        Forecast f = data == null || current >= data.length ? null : data[current];
        if (f == null) {
            temp.setText("--");
            shownDeg = Integer.MIN_VALUE;
            cond.setText(error == null ? "Loading…" : "Can't reach Open-Meteo");
            feels.setText(error == null ? "" : "Retrying shortly");
            hilo.setText("");
            localTime.setText("");
            units.render();
            if (data == null) fetch();
            return;
        }
        long now = f.now();
        int today = f.today();
        heroIcon.setKind(Wmo.icon(f.code, f.isDayAt(now)));
        setTemp(Double.isNaN(f.temp) ? Integer.MIN_VALUE : Units.deg(f.temp), animate || shownDeg != Integer.MIN_VALUE);
        units.render();
        cond.setText(Wmo.text(f.code, f.isDayAt(now)));
        feels.setText("Feels like " + Units.temp(f.feels));
        hilo.setText(today < f.dMax.length ? "High " + Units.temp(f.dMax[today]) + "   Low " + Units.temp(f.dMin[today]) : "");
        localTime.setText(Time.longDate(now).split(",")[0] + "  " + Time.clock(now));

        metric(0, "Wind", Units.speed(f.wind) + " " + Units.compass(f.windDir));
        metric(1, "Humidity", Double.isNaN(f.humidity) ? "--" : Math.round(f.humidity) + "%");
        metric(2, "Visibility", Units.distance(f.visibility));
        metric(3, "Pressure", Units.pressure(f.pressure));
        metric(4, "Dew point", Units.temp(f.dew));
        metric(5, "Air quality", f.aqi < 0 ? "--" : f.aqi + " " + Units.aqiLabel(f.aqi));
        if (f.aqi >= 0) {
            SpannableStringBuilder b = new SpannableStringBuilder(metricValue[5].getText());
            String n = String.valueOf(f.aqi);
            b.setSpan(new ForegroundColorSpan(Units.aqiColor(f.aqi)), 0, n.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            metricValue[5].setText(b);
        }

        for (int k = 0; k < DAYS; k++) {
            int di = today + k;
            if (di >= f.dTime.length) {
                days[k].setVisibility(View.INVISIBLE);
                continue;
            }
            days[k].setVisibility(View.VISIBLE);
            int[] d = Time.date(f.dTime[di]);
            days[k].bind(k == 0 ? "Today" : Time.shortDay(f.dTime[di]), Time.MONTHS[d[1] - 1].substring(0, 3) + " " + d[2],
                    Wmo.icon(f.dCode[di], true), Units.temp(f.dMax[di]), Units.temp(f.dMin[di]), f.dPop[di]);
            days[k].setNextFocusUpId(units.getId());
        }
        for (PlaceRow r : rows) r.setNextFocusRightId(days[dayIndex].getId());
        selectDay(dayIndex, animate);
        if (animate) {
            scroller.smoothScrollTo(0, 0);
            entrance();
        }
        refreshClocks();
    }

    private void metric(int i, String label, String value) {
        metricLabel[i].setText(label);
        metricValue[i].setText(value);
    }

    private void setTemp(int deg, boolean animate) {
        if (tempAnim != null) tempAnim.cancel();
        if (deg == Integer.MIN_VALUE) {
            temp.setText("--");
            shownDeg = deg;
            return;
        }
        if (!animate || shownDeg == Integer.MIN_VALUE || shownDeg == deg) {
            temp.setText(deg + "°");
            shownDeg = deg;
            return;
        }
        tempAnim = ValueAnimator.ofInt(shownDeg, deg);
        tempAnim.setDuration(Math.min(900, 120 + Math.abs(deg - shownDeg) * 25L));
        tempAnim.setInterpolator(EASE);
        tempAnim.addUpdateListener(a -> temp.setText(a.getAnimatedValue() + "°"));
        tempAnim.start();
        shownDeg = deg;
    }

    private void selectDay(int k, boolean animate) {
        Forecast f = data == null || current >= data.length ? null : data[current];
        boolean changed = k != dayIndex;
        dayIndex = k;
        for (int i = 0; i < DAYS; i++) days[i].setCurrent(i == k);
        for (PlaceRow r : rows) r.setNextFocusRightId(days[k].getId());
        if (f == null) return;
        int today = f.today();
        int di = Math.min(f.dTime.length - 1, today + k);
        long now = f.now();
        int start = k == 0 ? f.hourIndex(now) : f.hourIndex(f.dTime[di]);
        chart.bind(f, start, 24, animate || changed);
        hourReadout(-1);

        // The sky previews the day: now for today, early afternoon for the days ahead.
        if (k == 0) setScene(Scenes.pick(f, now, f.code));
        else setScene(Scenes.pick(f, f.dTime[di] + 13 * 3600, f.dCode[di]));

        String dayName = k == 0 ? "Today" : Time.DAYS[Time.weekday(f.dTime[di])];
        detailsTitle.setText("Day details · " + Time.longDate(f.dTime[di]));
        String rise = f.sunrise[di] > 0 ? Time.clock(f.sunrise[di]) : "--";
        String set = f.sunset[di] > 0 ? Time.clock(f.sunset[di]) : "--";
        summary.setText(dayName + ": " + Wmo.text(f.dCode[di], true) + ". High " + Units.temp(f.dMax[di])
                + ", low " + Units.temp(f.dMin[di]) + ". " + f.dPop[di] + "% chance of precipitation"
                + (Double.isNaN(f.dWind[di]) ? "." : ", wind " + Units.compass(f.dWindDir[di]) + " up to "
                + Units.speed(f.dWind[di]) + "."));

        long daylight = f.sunset[di] - f.sunrise[di];
        double frac = k == 0 && daylight > 0 ? (double) (now - f.sunrise[di]) / daylight : -1;
        details[0].bind("", "Sunrise " + rise, "Sunset " + set + (daylight > 0
                ? String.format(Locale.US, " · %dh %02dm", daylight / 3600, daylight % 3600 / 60) : ""), frac);
        double phase = Moon.phase(f.dTime[di] + 12 * 3600 - f.utcOffset);
        details[1].bind(Moon.name(phase), Math.round(Moon.illumination(phase) * 100) + "% lit", "", phase);
        details[2].bind(Units.speed(f.dWind[di]), "From the " + Units.compass(f.dWindDir[di]),
                k == 0 && !Double.isNaN(f.gusts) ? "Gusts now " + Units.speed(f.gusts) : "Strongest of the day", f.dWindDir[di]);
        details[3].bind(Units.precip(f.dPrecip[di]), f.dPop[di] + "% chance", "Total for the day", 0);
        details[4].bind(Double.isNaN(f.dUv[di]) ? "--" : String.valueOf(Math.round(f.dUv[di])), Units.uvLabel(f.dUv[di]),
                "", f.dUv[di]);
        if (k == 0 && f.aqi >= 0) details[5].bind(String.valueOf(f.aqi), Units.aqiLabel(f.aqi), "US AQI, now", f.aqi);
        else details[5].bind("--", k == 0 ? "Not available" : "Today only", "US AQI", -1);
    }

    private void hourReadout(int index) {
        Forecast f = data == null || current >= data.length ? null : data[current];
        if (f == null) return;
        if (index < 0 || index >= f.hTime.length) {
            hourlyTitle.setText(dayIndex == 0 ? "Hourly · next 24 hours" : "Hourly · " + Time.DAYS[Time.weekday(f.hTime[Math.max(0, Math.min(f.hTime.length - 1, chart.hour()))])]);
            return;
        }
        hourlyTitle.setText("Hourly · " + Time.shortDay(f.hTime[index]) + " " + Time.clock(f.hTime[index]) + "  —  "
                + Units.temp(f.hTemp[index]) + ", " + Wmo.text(f.hCode[index], f.hDay[index]).toLowerCase(Locale.US)
                + (f.hPop[index] > 0 ? ", " + f.hPop[index] + "% chance of precipitation" : ""));
    }

    private void refreshRows() {
        for (int i = 0; i < rows.size(); i++) {
            Places.Place pl = places.list.get(i);
            Forecast f = data == null || i >= data.length ? null : data[i];
            if (f == null) {
                rows.get(i).bind(pl.name, pl.region, "", -1);
            } else {
                long now = f.now();
                rows.get(i).bind(pl.name, Time.clock(now) + " · " + Wmo.text(f.code, f.isDayAt(now)),
                        Units.temp(f.temp), Wmo.icon(f.code, f.isDayAt(now)));
            }
        }
    }

    private void refreshClocks() {
        refreshRows();
        Forecast f = data == null || current >= data.length ? null : data[current];
        if (f != null) {
            long now = f.now();
            localTime.setText(Time.DAYS[Time.weekday(now)] + "  " + Time.clock(now));
            if (dayIndex == 0) setScene(Scenes.pick(f, now, f.code));
        }
        String when = "";
        if (lastFetchMs > 0) {
            long ms = lastFetchMs;
            long local = ms / 1000 + TimeZone.getDefault().getOffset(ms) / 1000;
            when = "Updated " + Time.clock(local);
        }
        if (error != null) when = (when.isEmpty() ? "" : when + "\n") + "Offline: " + error;
        updated.setText(fetching && when.isEmpty() ? "Updating…" : when);
    }

    private void toggleUnits() {
        Units.imperial = !Units.imperial;
        prefs.edit().putString("units", Units.imperial ? "imperial" : "metric").apply();
        shownDeg = Integer.MIN_VALUE;
        show(current, false);
    }

    private void entrance() {
        page.setAlpha(0f);
        page.setTranslationY(Ui.px(14));
        page.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(EASE).start();
        for (int k = 0; k < DAYS; k++) {
            DayTile d = days[k];
            d.setAlpha(0f);
            d.setTranslationY(Ui.px(12));
            d.animate().alpha(1f).translationY(0).setStartDelay(80 + 35L * k).setDuration(320).setInterpolator(EASE).start();
        }
    }

    // ---- sky ---------------------------------------------------------------------------------

    private final Runnable applyScene = new Runnable() {
        @Override
        public void run() {
            final String next = wantScene;
            if (next == null || next.equals(scene)) return;
            // While the veil is up (first sky, or back from pause) it takes the new sky's colours;
            // otherwise the backdrop crossfades from one sky to the next by itself.
            if (veil.getVisibility() == View.VISIBLE) setVeil(Scenes.colors(next));
            scene = next;
            backdrop.play(next, places.list.get(current).lat < 0);
        }
    };

    /** Asks for a sky; quick changes (scrolling through days) settle before the crossfade. */
    private void setScene(String next) {
        if (next.equals(wantScene)) return;
        wantScene = next;
        ui.removeCallbacks(applyScene);
        ui.postDelayed(applyScene, scene == null ? 0 : 380);
    }

    private void setVeil(int[] c) {
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c));
    }

    // ---- keys --------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_MENU) {
                lastFetchMs = 0;
                updated.setText("Updating…");
                fetch();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK && e.getRepeatCount() == 0) {
                View f = getCurrentFocus();
                if (f != null && !(f instanceof PlaceRow) && !rows.isEmpty()) {
                    rows.get(current).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- small views -------------------------------------------------------------------------

    /** "°F | °C", Windows 10 style: the active unit bright, the other dim. OK switches. */
    final class UnitToggle extends TextView implements Scroller.Top {
        UnitToggle(Context c) {
            super(c);
            setId(View.generateViewId());
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
            setTypeface(Ui.light);
            setIncludeFontPadding(false);
            setFocusable(true);
            setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(6));
            render();
        }

        void render() {
            String f = "°F", c = "°C";
            SpannableStringBuilder b = new SpannableStringBuilder(f + "  |  " + c);
            int on = Ui.WHITE, off = 0x80FFFFFF;
            b.setSpan(new ForegroundColorSpan(Units.imperial ? on : off), 0, f.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(0x59FFFFFF), f.length(), f.length() + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Units.imperial ? off : on), f.length() + 5, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            setText(b);
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            if (focused) {
                ShapeDrawable d = new ShapeDrawable(new RectShape());
                d.getPaint().setColor(0x40FFFFFF);
                setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {d, border()}));
            } else {
                setBackground(null);
            }
        }

        private GradientDrawable border() {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.TRANSPARENT);
            g.setStroke(Ui.dp(2), 0xE6FFFFFF);
            return g;
        }
    }

    /** The three-line navigation glyph at the top of the sidebar. */
    static final class Hamburger extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Hamburger(Context c) {
            super(c);
            p.setColor(Ui.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = Ui.px(1.6f), w = getWidth();
            for (int i = 0; i < 3; i++) {
                float y = getHeight() * (0.15f + i * 0.35f);
                c.drawRect(0, y, w, y + h, p);
            }
        }
    }
}
