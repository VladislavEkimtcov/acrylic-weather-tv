package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;

/** "Day details": sun, moon, wind, rain, UV and air quality, each with a small picture. */
final class DetailTile extends Glass {
    static final int SUN = 0, MOON = 1, WIND = 2, RAIN = 3, UV = 4, AIR = 5;

    private final int kind;
    private final TextPaint title = Ui.paint(11, Ui.FAINT, Ui.semibold);
    private final TextPaint big = Ui.paint(20, Ui.WHITE, Ui.light);
    private final TextPaint small = Ui.paint(12, Ui.MUTED, Ui.regular);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private String value = "", line1 = "", line2 = "";
    /** SUN: 0..1 across the day, or <0 when not today. WIND: degrees. UV: index. MOON: phase. AIR: AQI. */
    private double v;

    DetailTile(Context c, int kind) {
        super(c);
        this.kind = kind;
        title.setLetterSpacing(0.12f);
        scaleOnFocus = 1.04f;
    }

    void bind(String value, String line1, String line2, double v) {
        this.value = value;
        this.line1 = line1;
        this.line2 = line2;
        this.v = v;
        invalidate();
    }

    private String name() {
        switch (kind) {
            case SUN: return "SUN";
            case MOON: return "MOON";
            case WIND: return "WIND";
            case RAIN: return "PRECIPITATION";
            case UV: return "UV INDEX";
            default: return "AIR QUALITY";
        }
    }

    @Override
    void drawContent(Canvas c, float w, float h) {
        float pad = Ui.px(12);
        c.drawText(name(), pad, Ui.px(20), title);
        float picCx = w - pad - Ui.px(24), picCy = Ui.px(52);
        switch (kind) {
            case SUN: sun(c, w, pad); break;
            case MOON: Moon.draw(c, picCx, picCy, Ui.px(19), v, p, path, oval); break;
            case WIND: compass(c, picCx, picCy, Ui.px(20)); break;
            case RAIN: drop(c, picCx, picCy, Ui.px(18)); break;
            case UV: uvBar(c, w, pad, h); break;
            default: airDot(c, picCx, picCy, Ui.px(14)); break;
        }
        if (kind == SUN) {
            c.drawText(line1, pad, h - Ui.px(30), small);
            c.drawText(line2, pad, h - Ui.px(12), small);
            return;
        }
        c.drawText(value, pad, Ui.px(56), big);
        c.drawText(line1, pad, h - Ui.px(30), small);
        c.drawText(line2, pad, h - Ui.px(12), small);
    }

    private void sun(Canvas c, float w, float pad) {
        float left = pad + Ui.px(4), right = w - pad - Ui.px(4);
        float base = Ui.px(62), rad = (right - left) / 2;
        float ry = Ui.px(34);
        oval.set(left, base - ry, right, base + ry);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.px(1.5f));
        p.setColor(0x80FFFFFF);
        p.setPathEffect(new DashPathEffect(new float[] {Ui.px(3), Ui.px(4)}, 0));
        c.drawArc(oval, 180, 180, false, p);
        p.setPathEffect(null);
        p.setColor(0x4DFFFFFF);
        c.drawLine(pad, base, w - pad, base, p);
        p.setStyle(Paint.Style.FILL);
        if (v >= 0 && v <= 1) {
            double a = Math.PI * (1 - v);
            float x = left + rad + (float) Math.cos(a) * rad, y = base - (float) Math.sin(a) * ry;
            p.setColor(0x55FFD54A);
            c.drawCircle(x, y, Ui.px(10), p);
            p.setColor(0xFFFFD54A);
            c.drawCircle(x, y, Ui.px(5.5f), p);
        }
    }

    private void compass(Canvas c, float cx, float cy, float r) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.px(1.5f));
        p.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, r, p);
        if (Double.isNaN(v)) return;
        // The arrow points where the wind goes: opposite of where it comes from.
        c.save();
        c.rotate((float) v + 180, cx, cy);
        path.reset();
        path.moveTo(cx, cy - r * 0.8f);
        path.lineTo(cx + r * 0.32f, cy + r * 0.45f);
        path.lineTo(cx, cy + r * 0.25f);
        path.lineTo(cx - r * 0.32f, cy + r * 0.45f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawPath(path, p);
        c.restore();
    }

    private void drop(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.moveTo(cx, cy - r * 1.15f);
        path.cubicTo(cx + r * 0.35f, cy - r * 0.55f, cx + r * 0.85f, cy - r * 0.05f, cx + r * 0.85f, cy + r * 0.3f);
        path.cubicTo(cx + r * 0.85f, cy + r * 0.8f, cx + r * 0.45f, cy + r * 1.05f, cx, cy + r * 1.05f);
        path.cubicTo(cx - r * 0.45f, cy + r * 1.05f, cx - r * 0.85f, cy + r * 0.8f, cx - r * 0.85f, cy + r * 0.3f);
        path.cubicTo(cx - r * 0.85f, cy - r * 0.05f, cx - r * 0.35f, cy - r * 0.55f, cx, cy - r * 1.15f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0xFF8FCBFF, 0xFF2E86E6, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    private void uvBar(Canvas c, float w, float pad, float h) {
        float y = h - Ui.px(48);
        float left = pad, right = w - pad;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(left, 0, right, 0,
                new int[] {0xFF5BD46A, 0xFFF5D547, 0xFFFF9B3D, 0xFFFF5A4E, 0xFFB06BE0}, null, Shader.TileMode.CLAMP));
        oval.set(left, y - Ui.px(2.5f), right, y + Ui.px(2.5f));
        c.drawRoundRect(oval, Ui.px(2.5f), Ui.px(2.5f), p);
        p.setShader(null);
        if (!Double.isNaN(v)) {
            float x = left + (right - left) * (float) Math.min(1, v / 11);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, Ui.px(5), p);
        }
    }

    private void airDot(Canvas c, float cx, float cy, float r) {
        if (v < 0) return;
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        int col = Units.aqiColor((int) v);
        p.setColor(Ui.alpha(col, 0.3f));
        c.drawCircle(cx, cy, r * 1.5f, p);
        p.setColor(col);
        c.drawCircle(cx, cy, r, p);
    }
}
