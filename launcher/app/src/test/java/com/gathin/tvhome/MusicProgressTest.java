package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MusicProgressTest {
    @Test public void playingAdvancesFromSessionTimestampButPausedDoesNot() {
        assertEquals(92000, MusicProgress.position(90000, 10000, 1f, true, 12000, 228000));
        assertEquals(90000, MusicProgress.position(90000, 10000, 1f, false, 12000, 228000));
        assertEquals(91000, MusicProgress.position(90000, 10000, 0.5f, true, 12000, 228000));
    }

    @Test public void missingTimesAreHiddenAndProgressNeverExceedsTrackBounds() {
        assertEquals(-1, MusicProgress.position(-1, 10000, 1f, true, 12000, 228000));
        assertEquals(-1, MusicProgress.position(5000, 10000, 1f, true, 12000, 0));
        assertEquals(228000, MusicProgress.position(227000, 10000, 1f, true, 20000, 228000));
        assertEquals(0, MusicProgress.position(1000, 10000, -1f, true, 20000, 228000));
        assertEquals(5000, MusicProgress.position(5000, 20000, 1f, true, 10000, 228000));
    }

    @Test public void labelsUseMediaTimeIncludingLongTracks() {
        assertEquals("1:32", MusicProgress.time(92000));
        assertEquals("3:48", MusicProgress.time(228000));
        assertEquals("1:01:01", MusicProgress.time(3661000));
    }
}
