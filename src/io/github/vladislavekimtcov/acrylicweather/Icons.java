package io.github.vladislavekimtcov.acrylicweather;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Flat, colourful weather icons drawn in code, in the spirit of Windows 10's Weather app: yellow
 * sun, white clouds, blue rain. Sharp at any size. {@code phase} (0..1, looping) animates them:
 * the sun's rays turn, rain and snow fall, clouds drift a little.
 */
final class Icons {
    private static final int LIGHT = 0, GREY = 1, DARK = 2, FADED = 3;

    /** Built once at unit size and placed with canvas transforms: Skia caches a path's mask
     *  only while the path object stays the same, and concave paths are drawn on the CPU. */
    private static final Path MOON = moonPath(), BOLT = boltPath();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icons() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Draws {@code kind} ({@link Wmo} icon constants) centred on cx, cy in a box {@code s} wide. */
    void draw(Canvas c, int kind, float cx, float cy, float s, float phase) {
        switch (kind) {
            case Wmo.SUN:
                sun(c, cx, cy, s * 0.24f, phase);
                break;
            case Wmo.MOON:
                moon(c, cx, cy, s * 0.3f);
                break;
            case Wmo.PARTLY_SUN:
                sun(c, cx - s * 0.14f, cy - s * 0.14f, s * 0.17f, phase);
                cloud(c, cx + s * 0.07f, cy + s * 0.1f, s * 0.74f, LIGHT, phase);
                break;
            case Wmo.PARTLY_MOON:
                moon(c, cx - s * 0.15f, cy - s * 0.16f, s * 0.21f);
                cloud(c, cx + s * 0.07f, cy + s * 0.1f, s * 0.74f, LIGHT, phase);
                break;
            case Wmo.CLOUD:
                cloud(c, cx, cy, s * 0.9f, LIGHT, phase);
                break;
            case Wmo.OVERCAST:
                cloud(c, cx - s * 0.12f, cy - s * 0.1f, s * 0.66f, GREY, phase + 0.5f);
                cloud(c, cx + s * 0.07f, cy + s * 0.07f, s * 0.82f, LIGHT, phase);
                break;
            case Wmo.FOG:
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, FADED, phase);
                fog(c, cx, cy + s * 0.2f, s, phase);
                break;
            case Wmo.DRIZZLE:
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, LIGHT, phase);
                drops(c, cx, cy + s * 0.18f, s, 3, 0.1f, phase);
                break;
            case Wmo.RAIN:
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, GREY, phase);
                drops(c, cx, cy + s * 0.18f, s, 4, 0.16f, phase);
                break;
            case Wmo.HEAVY_RAIN:
                cloud(c, cx, cy - s * 0.12f, s * 0.86f, DARK, phase);
                drops(c, cx, cy + s * 0.16f, s, 5, 0.22f, phase);
                break;
            case Wmo.SNOW:
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, LIGHT, phase);
                flakes(c, cx, cy + s * 0.2f, s, 3, phase);
                break;
            case Wmo.SLEET:
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, GREY, phase);
                drops(c, cx - s * 0.12f, cy + s * 0.18f, s, 2, 0.14f, phase);
                flakes(c, cx + s * 0.16f, cy + s * 0.2f, s, 1, phase);
                break;
            case Wmo.STORM:
                cloud(c, cx, cy - s * 0.14f, s * 0.86f, DARK, phase);
                bolt(c, cx + s * 0.02f, cy + s * 0.08f, s, phase);
                drops(c, cx - s * 0.2f, cy + s * 0.18f, s, 1, 0.14f, phase);
                drops(c, cx + s * 0.24f, cy + s * 0.18f, s, 1, 0.14f, phase + 0.4f);
                break;
            case Wmo.SHOWERS_SUN:
                sun(c, cx - s * 0.15f, cy - s * 0.2f, s * 0.15f, phase);
                cloud(c, cx + s * 0.06f, cy - s * 0.04f, s * 0.74f, LIGHT, phase);
                drops(c, cx + s * 0.06f, cy + s * 0.24f, s, 3, 0.12f, phase);
                break;
            case Wmo.SHOWERS_MOON:
                moon(c, cx - s * 0.16f, cy - s * 0.22f, s * 0.18f);
                cloud(c, cx + s * 0.06f, cy - s * 0.04f, s * 0.74f, LIGHT, phase);
                drops(c, cx + s * 0.06f, cy + s * 0.24f, s, 3, 0.12f, phase);
                break;
            default:
                cloud(c, cx, cy, s * 0.9f, LIGHT, phase);
        }
    }

    private void sun(Canvas c, float cx, float cy, float r, float phase) {
        line.setShader(null);
        line.setColor(0xFFFFC83D);
        line.setStrokeWidth(r * 0.2f);
        double turn = phase * Math.PI / 4;
        for (int i = 0; i < 8; i++) {
            double a = turn + i * Math.PI / 4;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 1.38f, cy + sin * r * 1.38f, cx + cos * r * 1.74f, cy + sin * r * 1.74f, line);
        }
        fill.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                0xFFFFE680, 0xFFFFB000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, fill);
        fill.setShader(null);
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        fill.setShader(new LinearGradient(-1, -1, 1, 1, 0xFFFFF7DA, 0xFFE8DDB0, Shader.TileMode.CLAMP));
        c.save();
        c.translate(cx, cy);
        c.scale(r, r);
        c.drawPath(MOON, fill);
        c.restore();
        fill.setShader(null);
    }

    private static Path moonPath() {
        Path p = new Path(), cut = new Path();
        p.addCircle(0, 0, 1, Path.Direction.CW);
        cut.addCircle(0.5f, -0.32f, 0.84f, Path.Direction.CW);
        p.op(cut, Path.Op.DIFFERENCE);
        return p;
    }

    private static Path boltPath() {
        Path p = new Path();
        float[] pts = {0.03f, -0.06f, -0.09f, 0.13f, -0.01f, 0.13f, -0.07f, 0.3f, 0.1f, 0.07f, 0.02f, 0.07f, 0.09f, -0.06f};
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.close();
        return p;
    }

    /** Three puffs and a rounded base, each a shape the GPU draws natively; opaque, so the
     *  overlaps read as one cloud. */
    private void cloud(Canvas c, float cx, float cy, float w, int tone, float phase) {
        float bob = (float) Math.sin(phase * Math.PI * 2) * w * 0.012f;
        cy += w * 0.055f + bob;
        int top, bottom;
        switch (tone) {
            case GREY: top = 0xFFE3E8EE; bottom = 0xFFA9B4C0; break;
            case DARK: top = 0xFFA7B0BC; bottom = 0xFF5E6878; break;
            case FADED: top = 0xFFD9DEE4; bottom = 0xFFAAB3BD; break;
            default: top = 0xFFFFFFFF; bottom = 0xFFD5DFEA;
        }
        fill.setShader(new LinearGradient(0, cy - 0.35f * w, 0, cy + 0.24f * w, top, bottom, Shader.TileMode.CLAMP));
        c.drawCircle(cx - 0.2f * w, cy + 0.02f * w, 0.2f * w, fill);
        c.drawCircle(cx + 0.05f * w, cy - 0.08f * w, 0.27f * w, fill);
        c.drawCircle(cx + 0.27f * w, cy + 0.06f * w, 0.18f * w, fill);
        c.drawRoundRect(cx - 0.46f * w, cy, cx + 0.46f * w, cy + 0.24f * w, 0.12f * w, 0.12f * w, fill);
        fill.setShader(null);
    }

    private void drops(Canvas c, float cx, float top, float s, int n, float len, float phase) {
        line.setShader(null);
        line.setStrokeWidth(s * 0.045f);
        float band = s * 0.2f, gap = s * 0.15f;
        float x0 = cx - gap * (n - 1) / 2f;
        for (int i = 0; i < n; i++) {
            float p = (phase + i * 0.37f) % 1f;
            float y = top + p * band;
            int a = Math.round(255 * (p < 0.75f ? 1f : (1f - p) / 0.25f));
            line.setColor((a << 24) | 0x4FA8FF);
            float x = x0 + i * gap - p * s * 0.03f;
            c.drawLine(x, y, x - s * len * 0.25f, y + s * len, line);
        }
    }

    private void flakes(Canvas c, float cx, float top, float s, int n, float phase) {
        line.setShader(null);
        line.setStrokeWidth(s * 0.028f);
        float gap = s * 0.2f, r = s * 0.055f;
        float x0 = cx - gap * (n - 1) / 2f;
        for (int i = 0; i < n; i++) {
            float p = (phase + i * 0.33f) % 1f;
            float y = top + p * s * 0.18f;
            float x = x0 + i * gap + (float) Math.sin((p + i) * Math.PI * 2) * s * 0.02f;
            int a = Math.round(255 * (p < 0.7f ? 1f : (1f - p) / 0.3f));
            line.setColor((a << 24) | 0xFFFFFF);
            for (int k = 0; k < 3; k++) {
                double ang = p * Math.PI + k * Math.PI / 3;
                float dx = (float) Math.cos(ang) * r, dy = (float) Math.sin(ang) * r;
                c.drawLine(x - dx, y - dy, x + dx, y + dy, line);
            }
        }
    }

    private void bolt(Canvas c, float cx, float cy, float s, float phase) {
        float flicker = phase > 0.82f && phase < 0.9f ? 0.55f : 1f;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFFFFD43B, flicker));
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        c.drawPath(BOLT, fill);
        c.restore();
    }

    private void fog(Canvas c, float cx, float cy, float s, float phase) {
        line.setShader(null);
        line.setStrokeWidth(s * 0.05f);
        line.setColor(0xE6E4E9EF);
        float shift = (float) Math.sin(phase * Math.PI * 2) * s * 0.04f;
        c.drawLine(cx - s * 0.3f + shift, cy, cx + s * 0.26f + shift, cy, line);
        c.drawLine(cx - s * 0.24f - shift, cy + s * 0.11f, cx + s * 0.32f - shift, cy + s * 0.11f, line);
        c.drawLine(cx - s * 0.32f + shift, cy + s * 0.22f, cx + s * 0.2f + shift, cy + s * 0.22f, line);
    }
}
