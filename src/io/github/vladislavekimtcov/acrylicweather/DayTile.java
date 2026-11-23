package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;

/** One day in the daily strip: weekday and date, icon, high, low, chance of rain. */
final class DayTile extends Glass implements Scroller.Top {
    private final Icons icons;
    private final TextPaint day = Ui.paint(14, Ui.WHITE, Ui.semibold);
    private final TextPaint date = Ui.paint(11, Ui.MUTED, Ui.regular);
    private final TextPaint hi = Ui.paint(19, Ui.WHITE, Ui.light);
    private final TextPaint lo = Ui.paint(14, Ui.MUTED, Ui.light);
    private final TextPaint pop = Ui.paint(11, Ui.RAIN, Ui.semibold);
    private String dayText = "", dateText = "", hiText = "", loText = "", popText = "";
    private int icon = Wmo.CLOUD;

    DayTile(Context c, Icons icons) {
        super(c);
        this.icons = icons;
        for (Paint p : new Paint[] {day, date, hi, lo, pop}) p.setTextAlign(Paint.Align.CENTER);
    }

    void bind(String dayText, String dateText, int icon, String hi, String lo, int popPercent) {
        this.dayText = dayText;
        this.dateText = dateText;
        this.icon = icon;
        this.hiText = hi;
        this.loText = lo;
        this.popText = popPercent >= 10 ? popPercent + "%" : "";
        invalidate();
    }

    @Override
    void drawContent(Canvas c, float w, float h) {
        float cx = w / 2;
        c.drawText(dayText, cx, Ui.px(22), day);
        c.drawText(dateText, cx, Ui.px(36), date);
        icons.draw(c, icon, cx, Ui.px(62), Ui.px(38), 0f);
        c.drawText(hiText, cx, Ui.px(98), hi);
        c.drawText(loText, cx, Ui.px(116), lo);
        if (!popText.isEmpty()) c.drawText(popText, cx, h - Ui.px(9), pop);
    }
}
