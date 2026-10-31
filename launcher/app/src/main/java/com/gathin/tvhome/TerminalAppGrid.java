package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/** One visible app row; the padding above it belongs to the terminal panel header. */
public final class TerminalAppGrid extends RecyclerView {
    public TerminalAppGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int save = canvas.save();
        canvas.clipRect(0, getPaddingTop(), getWidth(), getHeight());
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return result;
    }
}
