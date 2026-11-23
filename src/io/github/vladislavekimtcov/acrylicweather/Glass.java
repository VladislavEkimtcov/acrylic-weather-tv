package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A translucent ("acrylic") tile, square-cornered like Windows 10. Focus lifts it, lightens it
 * and lights the border with a Reveal-style highlight that sweeps in from the side focus came from.
 */
abstract class Glass extends View {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    protected final RectF box = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Shown in the hourly graph, the selected place: lighter even without focus. */
    private boolean current;
    private float reveal = 0.5f;
    private float focusAmount;
    protected float scaleOnFocus = 1.06f;

    Glass(Context c) {
        super(c);
        setFocusable(true);
        edge.setStyle(Paint.Style.STROKE);
    }

    void setCurrent(boolean on) {
        if (current != on) {
            current = on;
            invalidate();
        }
    }

    boolean isCurrent() {
        return current;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focused) reveal = direction == FOCUS_LEFT ? 1f : direction == FOCUS_RIGHT ? 0f : 0.5f;
        animate().scaleX(focused ? scaleOnFocus : 1f).scaleY(focused ? scaleOnFocus : 1f)
                .setDuration(180).setInterpolator(EASE).start();
        focusAmount = focused ? 0f : 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        boolean f = isFocused();
        if (f && focusAmount < 1f) {
            focusAmount = Math.min(1f, focusAmount + 0.12f);
            reveal += (0.5f - reveal) * 0.12f;
            postInvalidateOnAnimation();
        }
        fill.setShader(null);
        fill.setColor(f ? 0x52FFFFFF : current ? 0x38FFFFFF : 0x33000000);
        c.drawRect(box, fill);
        if (f) {
            // Reveal: a bright spot on the border that settles at the top centre.
            float cx = w * reveal;
            edge.setShader(new RadialGradient(cx, 0, Math.max(w, h) * 1.1f,
                    new int[] {0xFFFFFFFF, Ui.alpha(0xFFFFFFFF, 0.35f + 0.4f * focusAmount)}, null, Shader.TileMode.CLAMP));
            edge.setStrokeWidth(Ui.px(2));
            float i = Ui.px(1);
            c.drawRect(i, i, w - i, h - i, edge);
            edge.setShader(null);
        } else {
            edge.setShader(null);
            edge.setStrokeWidth(Ui.px(1));
            edge.setColor(current ? 0x59FFFFFF : 0x1FFFFFFF);
            float i = Ui.px(0.5f);
            c.drawRect(i, i, w - i, h - i, edge);
        }
        drawContent(c, w, h);
    }

    abstract void drawContent(Canvas c, float w, float h);
}
