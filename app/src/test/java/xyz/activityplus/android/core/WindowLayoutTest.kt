package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test fun widthClassesFollowMaterialThresholds() {
        assertEquals(WindowLayout.Width.COMPACT, WindowLayout.widthClass(360f))
        assertEquals(WindowLayout.Width.COMPACT, WindowLayout.widthClass(599.9f))
        assertEquals(WindowLayout.Width.MEDIUM, WindowLayout.widthClass(600f))
        assertEquals(WindowLayout.Width.MEDIUM, WindowLayout.widthClass(839.9f))
        assertEquals(WindowLayout.Width.EXPANDED, WindowLayout.widthClass(840f))
        // The handheld in landscape: 1920 px at 2x.
        assertEquals(WindowLayout.Width.EXPANDED, WindowLayout.widthClass(960f))
    }

    @Test fun railFromMediumWidthOn() {
        assertFalse(WindowLayout.useRail(411f))
        assertTrue(WindowLayout.useRail(600f))
        assertTrue(WindowLayout.useRail(915f))
    }

    @Test fun twoLanesOnlyWhenEachLaneHasRoom() {
        // Medium window with the 80 dp rail: stays one column.
        assertEquals(1, WindowLayout.lanes(600f - 80f))
        assertEquals(1, WindowLayout.lanes(639f))
        assertEquals(2, WindowLayout.lanes(640f))
        assertEquals(2, WindowLayout.lanes(960f - 80f))
    }

    @Test fun contentIsCappedAndCentered() {
        assertEquals(0f, WindowLayout.sideMarginDp(411f, 1), 0f)
        assertEquals(0f, WindowLayout.sideMarginDp(640f, 1), 0f)
        assertEquals(0f, WindowLayout.sideMarginDp(880f, 2), 0f)
        assertEquals(140f, WindowLayout.sideMarginDp(1280f, 2), 0f)
        // A single lane never spreads past its cap, whatever the window.
        assertEquals(WindowLayout.ONE_LANE_MAX_DP, WindowLayout.maxContentDp(1), 0f)
        assertEquals(WindowLayout.TWO_LANES_MAX_DP, WindowLayout.maxContentDp(2), 0f)
    }
}
