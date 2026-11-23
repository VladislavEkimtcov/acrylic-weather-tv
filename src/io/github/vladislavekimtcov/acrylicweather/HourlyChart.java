package io.github.vladislavekimtcov.acrylicweather;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;

/**
 * 24 hours as a smooth temperature line with icons, chance-of-rain bars and times. With focus,
 * left and right move a cursor hour by hour and the header reads that hour out.
 */
final class HourlyChart extends Glass {
    interface Listener {
        void onHour(int index);
    }

    private static final int STEP = 3;

    private final Icons icons;
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tempText = Ui.paint(13, Ui.WHITE, Ui.regular);
    private final TextPaint timeText = Ui.paint(11, Ui.MUTED, Ui.regular);
    private final TextPaint popText = Ui.paint(10, Ui.RAIN, Ui.semibold);
    private final Path line = new Path(), fillPath = new Path();
    private final RectF r = new RectF();
    private final float[] xs = new float[48], ys = new float[48];

    private Forecast f;
    private int start, count;
    private int cursor;
    private float progress = 1f;
    private ValueAnimator reveal;
    private Listener listener;

    HourlyChart(Context c, Icons icons) {
        super(c);
        this.icons = icons;
        scaleOnFocus = 1.0f;
        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(Ui.px(2.5f));
        curve.setStrokeCap(Paint.Cap.ROUND);
        curve.setStrokeJoin(Paint.Join.ROUND);
        curve.setColor(0xFFFFFFFF);
        dot.setColor(0xFFFFFFFF);
        bar.setColor(0x995FAEFF);
        cursorPaint.setColor(0x66FFFFFF);
        cursorPaint.setStrokeWidth(Ui.px(1));
        tempText.setTextAlign(Paint.Align.CENTER);
        timeText.setTextAlign(Paint.Align.CENTER);
        popText.setTextAlign(Paint.Align.CENTER);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Shows hourly entries [start, start + count) and replays the draw-in animation. */
    void bind(Forecast f, int start, int count, boolean animate) {
        this.f = f;
        this.start = Math.max(0, start);
        this.count = Math.max(0, Math.min(count, Math.min(48, f.hTime.length - this.start)));
        cursor = Math.min(cursor, Math.max(0, this.count - 1));
        if (reveal != null) reveal.cancel();
        if (animate) {
            reveal = ValueAnimator.ofFloat(0f, 1f);
            reveal.setDuration(700);
            reveal.setInterpolator(new DecelerateInterpolator(1.5f));
            reveal.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            reveal.start();
        } else {
            progress = 1f;
        }
        invalidate();
    }

    int hour() {
        return start + cursor;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focused && listener != null) listener.onHour(hour());
        if (!focused && listener != null) listener.onHour(-1);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (count > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int next = cursor + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (next < 0 || next >= count) return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT; // left edge: let focus leave
            cursor = next;
            if (listener != null) listener.onHour(hour());
            invalidate();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    void drawContent(Canvas c, float w, float h) {
        if (f == null || count < 2) return;
        float padL = Ui.px(14), padR = Ui.px(14);
        float step = (w - padL - padR) / count;
        float curveTop = Ui.px(64), curveBottom = Ui.px(104);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double t = f.hTemp[start + i];
            if (Double.isNaN(t)) continue;
            min = Math.min(min, Units.deg(t));
            max = Math.max(max, Units.deg(t));
        }
        if (min > max) return;
        double span = Math.max(4, max - min);
        for (int i = 0; i < count; i++) {
            double t = f.hTemp[start + i];
            xs[i] = padL + step * (i + 0.5f);
            ys[i] = Double.isNaN(t) ? curveBottom : (float) (curveBottom - (Units.deg(t) - min) / span * (curveBottom - curveTop));
        }

        c.save();
        c.clipRect(0, 0, padL + (w - padL - padR) * progress + Ui.px(8), h);

        // Rain chance bars along the bottom.
        float barBase = Ui.px(140), barMax = Ui.px(20);
        for (int i = 0; i < count; i++) {
            int pop = f.hPop[start + i];
            if (pop <= 0) continue;
            float bh = Math.max(Ui.px(2), barMax * pop / 100f);
            r.set(xs[i] - step * 0.32f, barBase - bh, xs[i] + step * 0.32f, barBase);
            c.drawRect(r, bar);
        }

        line.reset();
        line.moveTo(xs[0], ys[0]);
        for (int i = 0; i < count - 1; i++) {
            int i0 = Math.max(0, i - 1), i3 = Math.min(count - 1, i + 2);
            float c1x = xs[i] + (xs[i + 1] - xs[i0]) / 6, c1y = ys[i] + (ys[i + 1] - ys[i0]) / 6;
            float c2x = xs[i + 1] - (xs[i3] - xs[i]) / 6, c2y = ys[i + 1] - (ys[i3] - ys[i]) / 6;
            line.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
        fillPath.set(line);
        fillPath.lineTo(xs[count - 1], barBase - barMax - Ui.px(2));
        fillPath.lineTo(xs[0], barBase - barMax - Ui.px(2));
        fillPath.close();
        area.setShader(new LinearGradient(0, curveTop, 0, barBase - barMax, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(fillPath, area);
        c.drawPath(line, curve);

        boolean focused = isFocused();
        for (int i = 0; i < count; i++) {
            boolean label = i % STEP == 0;
            boolean here = focused && i == cursor;
            if (!label && !here) continue;
            int k = start + i;
            icons.draw(c, Wmo.icon(f.hCode[k], f.hDay[k]), xs[i], Ui.px(24), Ui.px(here ? 30 : 26), 0f);
            c.drawCircle(xs[i], ys[i], Ui.px(here ? 4.5f : 3f), dot);
            c.drawText(Units.temp(f.hTemp[k]), xs[i], ys[i] - Ui.px(9), tempText);
            c.drawText(Time.hourLabel(f.hTime[k]), xs[i], h - Ui.px(10), timeText);
            if (f.hPop[k] >= 20) c.drawText(f.hPop[k] + "%", xs[i], barBase - barMax - Ui.px(5), popText);
        }
        c.restore();

        if (focused) {
            float x = xs[cursor];
            c.drawLine(x, Ui.px(44), x, h - Ui.px(26), cursorPaint);
            r.set(x - step * 0.5f - Ui.px(6), Ui.px(4), x + step * 0.5f + Ui.px(6), h - Ui.px(4));
            area.setShader(null);
            area.setColor(0x1FFFFFFF);
            c.drawRect(r, area);
        }
    }
}
