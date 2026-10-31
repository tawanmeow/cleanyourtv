package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        LinearLayout header = findViewById(R.id.spotifyHeader);
        View artwork = findViewById(R.id.spotifyArtwork);
        View metadataRow = (View) artwork.getParent();
        // Center the visible glyphs in the band above the cover, preserving both horizontal anchors.
        float headerCenter = metadataRow.getTop() / 2f - header.getTop();
        Rect ink = new Rect();
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView text = (TextView) child;
            String value = text.getText().toString();
            text.getPaint().getTextBounds(value, 0, value.length(), ink);
            float paintedCenter = text.getTop() + text.getBaseline() + (ink.top + ink.bottom) / 2f;
            text.setTranslationY(headerCenter - paintedCenter);
        }
        // Equal space above and below the control icons, between the track and the panel's bottom edge.
        View progressGroup = findViewById(R.id.spotifyProgressGroup);
        View progress = findViewById(R.id.spotifyProgress);
        View controls = findViewById(R.id.spotifyControls);
        float trackBottom = progressGroup.getVisibility() == VISIBLE
                ? progressGroup.getTop() + progress.getBottom() : metadataRow.getBottom();
        float controlsCenter = (trackBottom + getHeight()) / 2f;
        controls.setTranslationY(controlsCenter - controls.getTop() - controls.getHeight() / 2f);
    }
}
