package com.gathin.tvhome;

import java.util.Locale;

/** Presentation math for cached session timestamps; no controller calls or audio logic. */
final class MusicProgress {
    static long position(long position, long updated, float speed, boolean playing, long now, long duration) {
        if (position < 0 || duration <= 0) return -1;
        double elapsed = playing && updated > 0 && now >= updated ? (now - updated) * (double) speed : 0;
        return (long) Math.max(0, Math.min(duration, position + elapsed));
    }

    static String time(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
