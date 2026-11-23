package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** A place in the sidebar: name, its local time and sky, and the temperature there now. */
final class PlaceRow extends View {
    private final Icons icons;
    private final TextPaint name = Ui.paint(17, Ui.WHITE, Ui.regular);
    private final TextPaint sub = Ui.paint(12, Ui.MUTED, Ui.regular);
    private final TextPaint temp = Ui.paint(22, Ui.WHITE, Ui.light);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private String nameText = "", subText = "", tempText = "";
    private int icon = -1;
    private boolean current;
    /** Being moved (Rearrange): which ways it can still go. */
    private boolean moving, canUp, canDown;

    PlaceRow(Context c, Icons icons) {
        super(c);
        this.icons = icons;
        setFocusable(true);
        temp.setTextAlign(Paint.Align.RIGHT);
    }

    void bind(String name, String sub, String temp, int icon) {
        nameText = name;
        subText = sub;
        tempText = temp;
        this.icon = icon;
        invalidate();
    }

    void setCurrent(boolean on) {
        current = on;
        invalidate();
    }

    void setMoving(boolean on, boolean up, boolean down) {
        moving = on;
        canUp = up;
        canDown = down;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        boolean f = isFocused();
        if (f || current) {
            fill.setColor(f ? 0x40FFFFFF : 0x1FFFFFFF);
            c.drawRect(0, 0, w, h, fill);
        }
        if (f) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Ui.px(2));
            fill.setColor(0xE6FFFFFF);
            c.drawRect(Ui.px(1), Ui.px(1), w - Ui.px(1), h - Ui.px(1), fill);
            fill.setStyle(Paint.Style.FILL);
        }
        if (current) {
            fill.setColor(Ui.ACCENT);
            c.drawRect(0, Ui.px(8), Ui.px(4), h - Ui.px(8), fill);
        }
        if (moving) {
            // Small chevrons on the top and bottom edges: the ways it can still move.
            float cx = w / 2, s = Ui.px(5);
            fill.setColor(0xE6FFFFFF);
            if (canUp) chevron(c, cx, Ui.px(5), s, -1);
            if (canDown) chevron(c, cx, h - Ui.px(5), s, 1);
        }
        float left = Ui.px(18), right = w - Ui.px(14);
        float tempW = temp.measureText(tempText);
        float iconSize = Ui.px(28);
        float textRight = right - tempW - iconSize - Ui.px(10);
        c.drawText(TextUtils.ellipsize(nameText, name, textRight - left, TextUtils.TruncateAt.END).toString(),
                left, h / 2 - Ui.px(2), name);
        c.drawText(TextUtils.ellipsize(subText, sub, textRight - left, TextUtils.TruncateAt.END).toString(),
                left, h / 2 + Ui.px(15), sub);
        c.drawText(tempText, right, h / 2 + Ui.px(8), temp);
        if (icon >= 0) icons.draw(c, icon, right - tempW - Ui.px(4) - iconSize / 2, h / 2, iconSize, 0f);
    }

    /** A small filled triangle with its tip at (cx, tip), pointing up (dir -1) or down (dir 1). */
    private void chevron(Canvas c, float cx, float tip, float s, int dir) {
        float base = tip - dir * s * 0.9f;
        chevron.rewind();
        chevron.moveTo(cx - s * 1.4f, base);
        chevron.lineTo(cx, tip);
        chevron.lineTo(cx + s * 1.4f, base);
        chevron.close();
        c.drawPath(chevron, fill);
    }
}
