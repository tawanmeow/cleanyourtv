package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dock band's corners are concentric with its tiles' corners. */
public class DockCornersTest {
    private static final float DENSITY = 2f;   // 1080p TV, 320 dpi
    /** activity_main.xml: 56dp root padding and 10dp grid padding on each side of a 1920 px screen. */
    private static final int INNER = 1920 - 2 * 56 * 2 - 2 * 10 * 2;

    @Test public void bandRadiusIsTileRadiusPlusPadding() {
        assertEquals(77f, DockDecoration.radiusPx(25f, 52f), 0f);
    }

    @Test public void evenPaddingIsTheGapBetweenTilesUnlessTheEdgeIsTighter() {
        assertEquals(50f, DockDecoration.evenPaddingPx(1000, 4, 200, 80f), 0.001f);
        assertEquals(30f, DockDecoration.evenPaddingPx(1000, 4, 200, 30f), 0.001f);
        assertEquals(0f, DockDecoration.evenPaddingPx(1000, 0, 200, 30f), 0.001f);
    }

    @Test public void evenPaddingStaysPositiveAndBelowTheCellFromFourToSevenColumns() {
        for (int columns = 4; columns <= 7; columns++) {
            int tile = AppAdapter.tileWidthPx(INNER, columns, DENSITY);
            float gap = INNER / (float) columns - tile;
            float pad = DockDecoration.evenPaddingPx(INNER, columns, tile, Float.MAX_VALUE);
            assertEquals("columns " + columns, gap, pad, 0.001f);
            float radius = DockDecoration.radiusPx(AppAdapter.cornerRadius(tile, DENSITY), pad);
            assertEquals("columns " + columns, pad, radius - AppAdapter.cornerRadius(tile, DENSITY), 0.001f);
        }
    }

    @Test public void sixTilesHaveSevenEqualVisibleGaps() {
        int tile = 196, columns = 6, edge = 2;
        int gap = DockDecoration.equalGapPx(1536, columns, tile, edge);
        int width = columns * tile + (columns + 1) * gap + 2 * edge;
        int padding = gap / 2 + edge;
        int cell = (width - 2 * padding) / columns;
        assertEquals(50, gap);
        assertEquals(1530, width);
        int previousRight = edge;
        for (int i = 0; i < columns; i++) {
            int left = padding + i * cell + (cell - tile) / 2;
            assertEquals(gap, left - previousRight);
            previousRight = left + tile;
        }
        assertEquals(gap, width - edge - previousRight);
    }

    @Test public void equalGapsFitAvailableWidthAcrossColumnCounts() {
        for (int columns = 4; columns <= 7; columns++) {
            int gap = DockDecoration.equalGapPx(1536, columns, 196, 2);
            int width = columns * 196 + (columns + 1) * gap + 4;
            assertTrue(gap >= 0);
            assertEquals(0, gap % 2);
            assertTrue(width <= 1536);
        }
        assertEquals(36f, DockDecoration.headerCenterOffsetPx(2f), 0f);
    }
}
