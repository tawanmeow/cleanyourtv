package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.style.ReplacementSpan;

/** Monochrome weather glyph, bold and fitted to the temperature digits' visible height. */
final class WeatherIconSpan extends ReplacementSpan {
    private Paint iconPaint(Paint source) {
        Paint icon = new Paint(source);
        icon.setFakeBoldText(true);
        return icon;
    }

    private Rect bounds(Paint paint, String text) {
        Rect rect = new Rect();
        paint.getTextBounds(text, 0, text.length(), rect);
        return rect;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                 Paint.FontMetricsInt fm) {
        // Preserve the original text line's metrics so the status row does not move.
        if (fm != null) paint.getFontMetricsInt(fm);
        Rect digits = bounds(paint, "28");
        Rect glyph = bounds(iconPaint(paint), text.subSequence(start, end).toString());
        if (glyph.height() == 0) return 0;
        float scale = (float) digits.height() / glyph.height();
        return (int) Math.ceil(glyph.width() * scale) + 2;
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                float x, int top, int y, int bottom, Paint paint) {
        Paint icon = iconPaint(paint);
        String symbol = text.subSequence(start, end).toString();
        Rect glyph = bounds(icon, symbol);
        Rect digits = bounds(paint, "28");
        if (glyph.height() == 0) return;
        float scale = (float) digits.height() / glyph.height();
        int save = canvas.save();
        canvas.translate(x + 1 - glyph.left * scale, y + digits.top - glyph.top * scale);
        canvas.scale(scale, scale);
        canvas.drawText(symbol, 0, 0, icon);
        canvas.restoreToCount(save);
    }
}
