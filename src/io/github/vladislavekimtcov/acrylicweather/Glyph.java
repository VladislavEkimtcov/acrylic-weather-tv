package io.github.vladislavekimtcov.acrylicweather;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A square line-icon button in the sidebar's header: + (add a place) or refresh. */
final class Glyph extends View {
    static final int PLUS = 0, REFRESH = 1;

    private final int kind;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path head = new Path();
    private final RectF arc = new RectF();
    private boolean spinning;
    private float angle;
    private ValueAnimator spinner;

    Glyph(Context c, int kind) {
        super(c);
        this.kind = kind;
        setId(View.generateViewId());
        setFocusable(true);
        setClickable(true);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.px(1.6f));
        line.setColor(Ui.WHITE);
        fill.setColor(Ui.WHITE);
    }

    /** Turns while a refresh runs; stops at the next full turn. */
    void spin(boolean on) {
        if (on == spinning) return;
        spinning = on;
        if (on) turn();
    }

    private void turn() {
        if (spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 360f);
            spinner.setDuration(800);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.addUpdateListener(v -> {
                angle = (Float) v.getAnimatedValue();
                invalidate();
            });
            spinner.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator anim) {
                    angle = 0f;
                    invalidate();
                    if (spinning) post(Glyph.this::turn);
                }
            });
        }
        spinner.start();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (isFocused()) {
            fill.setColor(0x40FFFFFF);
            c.drawRect(0, 0, w, h, fill);
            line.setColor(0xE6FFFFFF);
            line.setStrokeWidth(Ui.px(2));
            float i = Ui.px(1);
            c.drawRect(i, i, w - i, h - i, line);
            line.setStrokeWidth(Ui.px(1.6f));
            line.setColor(Ui.WHITE);
            fill.setColor(Ui.WHITE);
        }
        float cx = w / 2, cy = h / 2, r = Ui.px(8);
        if (kind == PLUS) {
            c.drawLine(cx - r, cy, cx + r, cy, line);
            c.drawLine(cx, cy - r, cx, cy + r, line);
            return;
        }
        if (angle != 0f) c.rotate(angle, cx, cy);
        // Refresh: an open circle running clockwise from 1 o'clock round to 11, arrowhead at the end.
        r = Ui.px(7.5f);
        arc.set(cx - r, cy - r, cx + r, cy + r);
        float start = -55f, sweep = 290f;
        c.drawArc(arc, start, sweep, false, line);
        double a = Math.toRadians(start + sweep);
        float px = cx + r * (float) Math.cos(a), py = cy + r * (float) Math.sin(a);
        float dx = (float) -Math.sin(a), dy = (float) Math.cos(a); // clockwise tangent
        float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
        float s = Ui.px(3.6f);
        head.rewind();
        head.moveTo(px + dx * s, py + dy * s);
        head.lineTo(px - dx * s * 0.35f + nx * s, py - dy * s * 0.35f + ny * s);
        head.lineTo(px - dx * s * 0.35f - nx * s, py - dy * s * 0.35f - ny * s);
        head.close();
        c.drawPath(head, fill);
    }
}
