package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/**
 * A ScrollView that scrolls smoothly to where the focused section should sit, instead of jumping
 * the minimum distance: the daily strip keeps the whole top of the page in view.
 */
final class Scroller extends ScrollView {
    /** Views that, when focused, scroll the page back to the top. */
    interface Top {}

    /** Told the visible band (in page coordinates) whenever it moves. */
    interface Watcher {
        void onViewport(int top, int bottom);
    }

    private final Rect tmp = new Rect();
    private Watcher watcher;

    void setWatcher(Watcher w) {
        watcher = w;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (watcher != null) watcher.onViewport(t, t + getHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (watcher != null) watcher.onViewport(getScrollY(), getScrollY() + getHeight());
    }

    Scroller(Context c) {
        super(c);
        setSmoothScrollingEnabled(true);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setFillViewport(true);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0; // scrolling is done in requestChildFocus, smoothly
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused == null || getChildCount() == 0) return;
        int target;
        if (focused instanceof Top) {
            target = 0;
        } else {
            focused.getDrawingRect(tmp);
            offsetDescendantRectToMyCoords(focused, tmp);
            int margin = Ui.dp(28);
            int y = getScrollY(), h = getHeight();
            target = y;
            if (tmp.bottom + margin > y + h) target = tmp.bottom + margin - h;
            if (tmp.top - margin * 2 < target) target = Math.min(target, tmp.top - margin * 2);
        }
        int max = Math.max(0, getChildAt(0).getHeight() - getHeight());
        smoothScrollTo(0, Math.max(0, Math.min(max, target)));
    }
}
