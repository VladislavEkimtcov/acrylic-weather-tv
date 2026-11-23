package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The context menu that slides out to the right of a place (☰ Menu): a Windows 10 style flyout,
 * dark acrylic with square corners. Gone (not drawn at all) while closed.
 */
final class Flyout extends LinearLayout {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);
    private Item[] items = new Item[0];
    /** The text box while renaming, else null. */
    private Finder.SearchBox field;

    interface Edit {
        void done(String text);
    }

    Flyout(Context c) {
        super(c);
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF262A31);
        bg.setStroke(Ui.dp(1), 0x40FFFFFF);
        setBackground(bg);
        setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        setMinimumWidth(Ui.dp(190));
        setVisibility(GONE);
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Shows the items with their top-left at (x, y) in the parent, nudged up to stay on screen,
     * and focuses item {@code focus}. A title, if any, sits above the items and can't be focused.
     */
    void open(float x, float y, String title, String[] labels, Runnable[] actions, int focus) {
        build(title, null, labels, actions);
        place(x, y);
        items[Math.max(0, Math.min(items.length - 1, focus))].requestFocus();
    }

    /**
     * A text box filled with {@code text} and the keyboard up, Save and Cancel below. The
     * keyboard's own action key (or closing it) moves to Save.
     */
    void openEditor(float x, float y, String title, String text, Edit save, Runnable cancel) {
        Finder.SearchBox box = new Finder.SearchBox(getContext(), "Name", false);
        box.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        box.setText(text);
        box.setSelection(box.length());
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        build(title, box, new String[] {"Save", "Cancel"},
                new Runnable[] {() -> save.done(box.getText().toString()), cancel});
        box.setOnEditorActionListener((v, action, event) -> {
            if (event != null && event.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
            Ui.keyboard(box, false);
            items[0].requestFocus();
            return true;
        });
        place(x, y);
        box.requestFocus();
        postDelayed(() -> {
            if (field == box && box.isFocused()) Ui.keyboard(box, true);
        }, 120);
    }

    private void build(String title, Finder.SearchBox box, String[] labels, Runnable[] actions) {
        if (field != null) Ui.keyboard(field, false);
        removeAllViews();
        field = box;
        if (title != null) {
            TextView t = Ui.text(getContext(), 14, Ui.MUTED, Ui.semibold);
            t.setText(title);
            t.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(6));
            addView(t);
        }
        if (box != null) {
            LayoutParams lp = new LayoutParams(Ui.dp(300), Ui.dp(46));
            lp.leftMargin = lp.rightMargin = Ui.dp(14);
            lp.topMargin = Ui.dp(2);
            lp.bottomMargin = Ui.dp(8);
            addView(box, lp);
        }
        items = new Item[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            Item it = new Item(getContext(), labels[i]);
            it.setOnClickListener(v -> action.run());
            items[i] = it;
            addView(it, new LayoutParams(-1, Ui.dp(46)));
        }
        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            it.setNextFocusUpId(i == 0 && box != null ? box.getId() : items[Math.max(0, i - 1)].getId());
            it.setNextFocusDownId(items[Math.min(items.length - 1, i + 1)].getId());
            it.setNextFocusLeftId(it.getId());
            it.setNextFocusRightId(it.getId());
        }
        if (box != null) box.setNextFocusDownId(items[0].getId());
    }

    private void place(float x, float y) {
        View parent = (View) getParent();
        measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        float maxY = parent.getHeight() - getMeasuredHeight() - Ui.dp(12);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        p.leftMargin = Math.round(x);
        p.topMargin = Math.round(Math.max(Ui.dp(12), Math.min(y, maxY)));
        setLayoutParams(p);
        boolean wasOpen = isOpen();
        setVisibility(VISIBLE);
        if (!wasOpen) {
            setAlpha(0f);
            setTranslationX(-Ui.px(16));
            animate().alpha(1f).translationX(0).setDuration(170).setInterpolator(EASE).start();
        }
    }

    /** True if the view is one of the flyout's own (an item or the text box). */
    boolean owns(View v) {
        return v != null && v.getParent() == this;
    }

    void close() {
        if (field != null) Ui.keyboard(field, false);
        field = null;
        animate().cancel();
        setVisibility(GONE);
        removeAllViews();
        items = new Item[0];
    }

    /** One line of the menu: lighter, with the white focus rectangle, when focused. */
    static final class Item extends TextView {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Item(Context c, String label) {
            super(c);
            setId(View.generateViewId());
            setText(label);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
            setTextColor(Ui.WHITE);
            setTypeface(Ui.regular);
            setIncludeFontPadding(false);
            setSingleLine(true);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(18), 0, Ui.dp(28), 0);
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (isFocused()) {
                float w = getWidth(), h = getHeight();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x33FFFFFF);
                c.drawRect(0, 0, w, h, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.px(2));
                p.setColor(0xE6FFFFFF);
                float i = Ui.px(1);
                c.drawRect(i, i, w - i, h - i, p);
            }
            super.onDraw(c);
        }
    }
}
