package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.widget.LinearLayout;

/**
 * A block of the page that skips drawing while it is well outside the screen. Unlike INVISIBLE
 * its tiles stay focusable, so the remote can still move into it (which scrolls it into view).
 */
final class Section extends LinearLayout {
    private boolean culled;

    Section(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setCulled(boolean on) {
        if (culled != on) {
            culled = on;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (!culled) super.dispatchDraw(c);
    }
}
