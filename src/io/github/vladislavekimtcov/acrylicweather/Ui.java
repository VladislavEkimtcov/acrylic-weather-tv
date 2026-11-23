package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

/** Sizes, type and colours shared by every view. The look is Windows 10's: square, light, translucent. */
final class Ui {
    static final int WHITE = 0xFFFFFFFF;
    static final int MUTED = 0xC8FFFFFF;
    static final int FAINT = 0x8CFFFFFF;
    /** Windows 10's default accent blue. */
    static final int ACCENT = 0xFF0078D7;
    static final int RAIN = 0xFF6CB8FF;

    static Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface semibold = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private static float density = 2f, scaled = 2f;

    private Ui() {}

    /**
     * Selawik, Microsoft's open-source stand-in for Segoe UI, when the APK carries it
     * (assets/fonts); Roboto otherwise.
     */
    static void init(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        density = m.density;
        scaled = m.scaledDensity;
        thin = asset(c, "fonts/selawkl.ttf", thin);
        light = asset(c, "fonts/selawksl.ttf", light);
        regular = asset(c, "fonts/selawk.ttf", regular);
        semibold = asset(c, "fonts/selawksb.ttf", semibold);
    }

    private static Typeface asset(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static float px(float dp) {
        return dp * density;
    }

    static float sp(float v) {
        return v * scaled;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        return v;
    }

    static TextPaint paint(float sp, int color, Typeface face) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(sp(sp));
        p.setColor(color);
        p.setTypeface(face);
        return p;
    }

    static int lerpColor(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** Shows the system keyboard for a text box, or hides it. */
    static void keyboard(View field, boolean show) {
        InputMethodManager imm = (InputMethodManager) field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(field, 0);
        else imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    static int alpha(int color, float a) {
        int base = color >>> 24;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }
}
