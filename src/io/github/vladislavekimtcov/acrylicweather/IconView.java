package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * The big icon next to the temperature. Still on purpose: all motion lives in the sky video,
 * because anything animating inside the translucent window redraws all of it every frame.
 */
final class IconView extends View {
    private final Icons icons;
    private int kind = Wmo.CLOUD;

    IconView(Context c, Icons icons) {
        super(c);
        this.icons = icons;
    }

    void setKind(int kind) {
        if (this.kind != kind) {
            this.kind = kind;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        icons.draw(c, kind, getWidth() / 2f, getHeight() / 2f, s, 0f);
    }
}
