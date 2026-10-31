package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/** Hides only the launcher shelf after inactivity; never sleeps the TV or stops wallpaper/media. */
final class LauncherIdleMode {
    private static final long TIMEOUT_MS = 5 * 60_000L;
    private final Activity activity;
    private final View root, notice;
    private final View[] shelf;
    private final int[] visibility;
    private final BooleanSupplier canEnter;
    private final Runnable beforeEnter, focusApps;
    private WeakReference<View> previousFocus = new WeakReference<>(null);
    private boolean running, idle;
    private int wakingKey = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable timeout = this::enter;

    LauncherIdleMode(Activity activity, int dockOpacity, BooleanSupplier canEnter,
                     Runnable beforeEnter, Runnable focusApps) {
        this.activity = activity;
        this.canEnter = canEnter;
        this.beforeEnter = beforeEnter;
        this.focusApps = focusApps;
        root = activity.findViewById(R.id.root);
        notice = activity.findViewById(R.id.idleModeNotice);
        shelf = new View[] {activity.findViewById(R.id.grid), activity.findViewById(R.id.hero),
                activity.findViewById(R.id.glass)};
        visibility = new int[shelf.length];
        // Match the Apps panel's configured opacity using the existing theme's RGB and border.
        GradientDrawable background = (GradientDrawable) notice.getBackground().mutate();
        int rgb = activity.getColor(R.color.tokyo_panel) & 0x00ffffff;
        background.setColor((Math.round(dockOpacity * 1.99f) << 24) | rgb);
    }

    void start() {
        running = true;
        interaction();
    }

    void stop() {
        running = false;
        root.removeCallbacks(timeout);
        wakingKey = KeyEvent.KEYCODE_UNKNOWN;
        leave(false);
    }

    void interaction() {
        root.removeCallbacks(timeout);
        if (running && !idle) root.postDelayed(timeout, TIMEOUT_MS);
    }

    boolean isIdle() { return idle; }

    boolean enterNow() {
        enter();
        return idle;
    }

    private void enter() {
        if (!running || idle) return;
        if (!activity.hasWindowFocus() || !canEnter.getAsBoolean()) {
            interaction();
            return;
        }
        root.removeCallbacks(timeout);
        previousFocus = new WeakReference<>(activity.getCurrentFocus());
        beforeEnter.run();
        idle = true;
        // Keep all original layout measurements and scroll positions intact.
        for (int i = 0; i < shelf.length; i++) {
            visibility[i] = shelf[i].getVisibility();
            shelf[i].setVisibility(View.INVISIBLE);
        }
        notice.setVisibility(View.VISIBLE);
        notice.requestFocus();
    }

    private void leave(boolean restoreFocus) {
        if (!idle) return;
        idle = false;
        notice.setVisibility(View.GONE);
        for (int i = 0; i < shelf.length; i++) shelf[i].setVisibility(visibility[i]);
        if (restoreFocus) {
            View target = previousFocus.get();
            if (target == null || !target.isAttachedToWindow() || !target.isShown()
                    || !target.isEnabled() || !target.requestFocus()) focusApps.run();
        }
        previousFocus.clear();
        interaction();
    }

    /** Consume the whole wake gesture, including repeats and key-up, so OK cannot open an app. */
    boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == wakingKey && wakingKey != KeyEvent.KEYCODE_UNKNOWN) {
            if (event.getAction() == KeyEvent.ACTION_UP) wakingKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        boolean wake = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!idle || !wake) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            wakingKey = key;
            leave(true);
        }
        return true;
    }
}
