package io.github.vladislavekimtcov.acrylicweather;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** The moon's phase from the date alone (mean synodic month), and a picture of it. */
final class Moon {
    private static final double SYNODIC = 29.530588853;
    /** A new moon: 2000-01-06 18:14 UTC, in epoch seconds. */
    private static final long EPOCH = 947182440L;

    private static final String[] NAMES = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
        "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};

    private Moon() {}

    /** 0 = new, 0.5 = full, at a real (UTC) epoch second. */
    static double phase(long utc) {
        double days = (utc - EPOCH) / 86400.0;
        double p = (days / SYNODIC) % 1.0;
        return p < 0 ? p + 1 : p;
    }

    static String name(double phase) {
        return NAMES[(int) Math.floor(phase * 8 + 0.5) % 8];
    }

    /** Lit fraction, 0..1. */
    static double illumination(double phase) {
        return (1 - Math.cos(phase * 2 * Math.PI)) / 2;
    }

    /** The lit part of the disc, light on dark, terminator as an ellipse. */
    static void draw(Canvas c, float cx, float cy, float r, double phase, Paint p, Path path, RectF oval) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, r, p);

        // Waxing: lit on the right. The terminator's half-width runs r..0..r over each half.
        boolean waxing = phase < 0.5;
        double k = Math.cos(phase * 2 * Math.PI); // 1 new, -1 full
        float tw = (float) Math.abs(k) * r;
        path.reset();
        oval.set(cx - r, cy - r, cx + r, cy + r);
        // The lit limb: a half circle on the lit side.
        path.arcTo(oval, waxing ? -90 : 90, 180, true);
        // Back along the terminator ellipse: through the lit side for a crescent (k > 0), through
        // the dark side for a gibbous moon. Either way that is -180 and +180 from where the limb ended.
        oval.set(cx - tw, cy - r, cx + tw, cy + r);
        path.arcTo(oval, waxing ? 90 : -90, k > 0 ? -180 : 180, false);
        path.close();
        p.setColor(0xFFF6F1DC);
        c.drawPath(path, p);
    }
}
