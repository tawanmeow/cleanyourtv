package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/** Only overflowing song titles scroll; short titles and all other metadata remain static. */
public final class ScrollingSongTitle extends TextView {
    private AnimatorSet scrolling;
    private final Runnable refresh = this::refreshOverflow;
    private final Runnable startScroll = this::startScrolling;

    public ScrollingSongTitle(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSingleLine(true);
        setHorizontallyScrolling(true);
        setEllipsize(null);
    }

    @Override protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        // TextView can call this before our callbacks are initialized.
        if (refresh != null) scheduleRefresh();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scheduleRefresh();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) scheduleRefresh();
        else stopScrolling();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleRefresh();
    }

    @Override protected void onDetachedFromWindow() {
        stopScrolling();
        super.onDetachedFromWindow();
    }

    private int overflow() {
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        return getLayout() == null || available <= 0 ? 0
                : Math.max(0, (int) Math.ceil(getLayout().getLineWidth(0) - available));
    }

    private void scheduleRefresh() {
        removeCallbacks(refresh);
        post(refresh);
    }

    private void refreshOverflow() {
        stopScrolling();
        if (isShown() && overflow() > 0 && ValueAnimator.areAnimatorsEnabled()) postDelayed(startScroll, 1500);
    }

    private void stopScrolling() {
        removeCallbacks(refresh);
        removeCallbacks(startScroll);
        AnimatorSet old = scrolling;
        scrolling = null;
        if (old != null) old.cancel();
        scrollTo(0, 0);
    }

    private void startScrolling() {
        int distance = overflow();
        if (!isShown() || distance <= 0 || !ValueAnimator.areAnimatorsEnabled()) return;
        long travel = Math.max(2500L, Math.round(distance * 1000f / (18f * getResources().getDisplayMetrics().density)));
        ValueAnimator forward = ValueAnimator.ofInt(0, distance);
        ValueAnimator backward = ValueAnimator.ofInt(distance, 0);
        for (ValueAnimator movement : new ValueAnimator[] {forward, backward}) {
            movement.setDuration(travel);
            movement.setInterpolator(new LinearInterpolator());
            movement.addUpdateListener(a -> scrollTo((Integer) a.getAnimatedValue(), 0));
        }
        backward.setStartDelay(1200);
        AnimatorSet sequence = new AnimatorSet();
        sequence.playSequentially(forward, backward);
        sequence.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (scrolling != sequence) return;
                scrolling = null;
                if (isShown()) postDelayed(startScroll, 1500);
            }
        });
        scrolling = sequence;
        sequence.start();
    }
}
