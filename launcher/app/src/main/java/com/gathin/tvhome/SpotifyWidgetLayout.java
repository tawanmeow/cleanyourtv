package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** A compact preferred width that still respects a narrower parent. */
public final class SpotifyWidgetLayout extends LinearLayout {
    public SpotifyWidgetLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleX(0.93f);
        setScaleY(0.93f);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setPivotX(w);
        setPivotY(0f);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int preferred = getResources().getDimensionPixelSize(R.dimen.spotify_widget_width);
        int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? preferred : MeasureSpec.getSize(widthSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(preferred, available), MeasureSpec.EXACTLY), heightSpec);
    }
}
