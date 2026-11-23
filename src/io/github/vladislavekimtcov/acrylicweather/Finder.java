package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The "+" panel: a search box for the system keyboard and the matching places below it, searched
 * on Open-Meteo as you type. OK on a match adds it to the sidebar. Gone (not drawn) while closed.
 */
final class Finder extends LinearLayout {
    interface Listener {
        void onPick(Geo.Hit hit);
    }

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);
    private static final long TYPING_MS = 450;

    private final Handler ui;
    private final ExecutorService net;
    private final Listener listener;
    private final SearchBox field;
    private final TextView status;
    private final LinearLayout list;
    private final List<Result> results = new ArrayList<>();
    private Places places;
    /** Bumped per search, so a slow answer to an old query is dropped. */
    private int seq;
    private String shownQuery = "";
    /** The keyboard's Search key was pressed: move to the first match once it's there. */
    private boolean jump;

    private final Runnable search = this::searchNow;

    Finder(Context c, Handler ui, ExecutorService net, Listener listener) {
        super(c);
        this.ui = ui;
        this.net = net;
        this.listener = listener;
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF262A31);
        bg.setStroke(Ui.dp(1), 0x40FFFFFF);
        setBackground(bg);
        setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(12));
        setVisibility(GONE);

        field = new SearchBox(c);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isOpen()) return;
                ui.removeCallbacks(search);
                ui.postDelayed(search, TYPING_MS);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            ui.removeCallbacks(search);
            keyboard(false);
            if (!results.isEmpty() && query().equals(shownQuery)) results.get(0).requestFocus();
            else {
                jump = true;
                searchNow();
            }
            return true;
        });
        addView(field, new LayoutParams(-1, Ui.dp(46)));

        status = Ui.text(c, 14, Ui.MUTED, Ui.regular);
        status.setSingleLine(false);
        status.setPadding(Ui.dp(2), Ui.dp(10), Ui.dp(2), 0);
        addView(status, new LayoutParams(-1, -2));

        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(4);
        addView(list, lp);
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** Opens at (x, y) in the parent, empty, with the keyboard up. */
    void open(float x, float y, Places places) {
        this.places = places;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Ui.dp(440), -2, Gravity.TOP | Gravity.START);
        p.leftMargin = Math.round(x);
        p.topMargin = Math.round(y);
        setLayoutParams(p);
        field.setText("");
        setResults(new ArrayList<Geo.Hit>(), "");
        status.setText(places.list.size() >= Places.MAX
                ? "The sidebar holds " + Places.MAX + " places. Delete one to add another." : "");
        status.setVisibility(status.length() > 0 ? VISIBLE : GONE);
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationX(-Ui.px(16));
        animate().alpha(1f).translationX(0).setDuration(170).setInterpolator(EASE).start();
        field.requestFocus();
        ui.postDelayed(() -> {
            if (isOpen() && field.isFocused()) keyboard(true);
        }, 120);
    }

    void close() {
        ui.removeCallbacks(search);
        seq++;
        keyboard(false);
        animate().cancel();
        setVisibility(GONE);
        list.removeAllViews();
        results.clear();
    }

    /** True if the view is part of this panel. */
    boolean owns(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == this) return true;
        }
        return false;
    }

    void focusField() {
        field.requestFocus();
    }

    void say(String s) {
        status.setText(s);
        status.setVisibility(s.isEmpty() ? GONE : VISIBLE);
    }

    private String query() {
        return field.getText().toString().trim();
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(field, 0);
        else imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void searchNow() {
        final String q = query();
        final int mine = ++seq;
        if (q.length() < 2) {
            jump = false;
            setResults(new ArrayList<Geo.Hit>(), q);
            say("");
            return;
        }
        say("Searching…");
        net.execute(() -> {
            List<Geo.Hit> got = null;
            try {
                got = Geo.search(q);
            } catch (Exception e) {
                // shown below
            }
            final List<Geo.Hit> hits = got;
            ui.post(() -> {
                if (mine != seq || !isOpen()) return;
                if (hits == null) {
                    say("Can't reach Open-Meteo. Check the connection and try again.");
                    jump = false;
                    return;
                }
                setResults(hits, q);
                boolean full = places.list.size() >= Places.MAX;
                say(hits.isEmpty() ? "No places called “" + q + "”."
                        : full ? "The sidebar holds " + Places.MAX + " places. Delete one to add another." : "");
                if (jump && !results.isEmpty()) results.get(0).requestFocus();
                jump = false;
            });
        });
    }

    private void setResults(List<Geo.Hit> hits, String q) {
        boolean hadFocus = false;
        for (Result r : results) hadFocus |= r.isFocused();
        // Park focus in the box before its row goes, so it can't fall onto the page behind.
        if (hadFocus) field.requestFocus();
        list.removeAllViews();
        results.clear();
        shownQuery = q;
        for (Geo.Hit h : hits) {
            final Geo.Hit hit = h;
            Result r = new Result(getContext(), h, places != null && places.indexOf(h.toPlace()) >= 0);
            r.setOnClickListener(v -> listener.onPick(hit));
            results.add(r);
            list.addView(r, new LayoutParams(-1, Ui.dp(48)));
        }
        int up = field.getId();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            r.setNextFocusUpId(up);
            up = r.getId();
            r.setNextFocusDownId(results.get(Math.min(results.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        field.setNextFocusDownId(results.isEmpty() ? field.getId() : results.get(0).getId());
        if (hadFocus && !results.isEmpty()) results.get(0).requestFocus();
    }

    /** Windows 10's dark text box: black when focused with an accent border, a magnifier on the right. */
    static final class SearchBox extends EditText {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchBox(Context c) {
            super(c);
            setId(View.generateViewId());
            setBackground(null);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            setTextColor(Ui.WHITE);
            setHintTextColor(0x8CFFFFFF);
            setHint("Search for a city");
            setTypeface(Ui.regular);
            setIncludeFontPadding(false);
            setSingleLine(true);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(12), 0, Ui.dp(44), 0);
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);
            setNextFocusLeftId(getId());
            setNextFocusRightId(getId());
            setNextFocusUpId(getId());
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int sx = getScrollX();
            boolean f = isFocused();
            p.setStyle(Paint.Style.FILL);
            p.setColor(f ? 0xE6000000 : 0x40000000);
            c.drawRect(sx, 0, sx + w, h, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.px(2));
            p.setColor(f ? Ui.ACCENT : 0x8CFFFFFF);
            float i = Ui.px(1);
            c.drawRect(sx + i, i, sx + w - i, h - i, p);
            // Magnifier.
            p.setColor(Ui.WHITE);
            p.setStrokeWidth(Ui.px(1.6f));
            float r = Ui.px(6.5f), cx = sx + w - Ui.px(24), cy = h / 2 - Ui.px(2);
            c.drawCircle(cx, cy, r, p);
            float d = r * 0.7071f;
            c.drawLine(cx - d, cy + d, cx - d - Ui.px(6), cy + d + Ui.px(6), p);
            super.onDraw(c);
        }
    }

    /** A match: the name, then its state and country on the same baseline; a tick if already listed. */
    static final class Result extends View {
        private final TextPaint name = Ui.paint(18, Ui.WHITE, Ui.regular);
        private final TextPaint where = Ui.paint(14, Ui.MUTED, Ui.regular);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String nameText, whereText;
        private final boolean listed;

        Result(Context c, Geo.Hit hit, boolean listed) {
            super(c);
            setId(View.generateViewId());
            setFocusable(true);
            setClickable(true);
            nameText = hit.name;
            whereText = hit.where();
            this.listed = listed;
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
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x33FFFFFF);
                c.drawRect(0, 0, w, h, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.px(2));
                p.setColor(0xE6FFFFFF);
                float i = Ui.px(1);
                c.drawRect(i, i, w - i, h - i, p);
            }
            float left = Ui.px(12), right = w - Ui.px(listed ? 40 : 12);
            float base = h / 2 + name.getTextSize() * 0.35f;
            String n = TextUtils.ellipsize(nameText, name, (right - left) * 0.6f, TextUtils.TruncateAt.END).toString();
            c.drawText(n, left, base, name);
            float x = left + name.measureText(n) + Ui.px(10);
            if (x < right) {
                c.drawText(TextUtils.ellipsize(whereText, where, right - x, TextUtils.TruncateAt.END).toString(), x, base, where);
            }
            if (listed) {
                // A tick: this place is in the sidebar already.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.px(1.8f));
                p.setColor(Ui.WHITE);
                float cx = w - Ui.px(24), cy = h / 2;
                c.drawLine(cx - Ui.px(6), cy, cx - Ui.px(2), cy + Ui.px(4.5f), p);
                c.drawLine(cx - Ui.px(2), cy + Ui.px(4.5f), cx + Ui.px(7), cy - Ui.px(5), p);
            }
        }
    }
}
