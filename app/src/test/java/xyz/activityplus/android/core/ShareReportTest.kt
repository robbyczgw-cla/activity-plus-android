package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.ShareReport.Row
import xyz.activityplus.android.core.ShareReport.Section

class ShareReportTest {
    private val sections = listOf(
        Section("Battery", listOf(Row("Level", "64 %"), Row("Cycles", null), Row("Health", "  "))),
        Section("Wakeups today", emptyList()),
        Section("Most battery today", listOf(Row("Chrome", "0.7 Wh"), Row("", "12 more"))),
    )

    @Test fun textHasTitleDeviceAndSections() {
        val text = ShareReport.text("Activity+ report", "Pixel 8 · Android 15", sections)
        assertEquals(
            "Activity+ report\nPixel 8 · Android 15\n\nBattery\n  Level: 64 %\n\nMost battery today\n  Chrome: 0.7 Wh\n  12 more\n",
            text,
        )
    }

    @Test fun rowsWithoutValueAndEmptySectionsAreLeftOut() {
        val text = ShareReport.text("T", "", sections)
        assertFalse(text.contains("Cycles"))
        assertFalse(text.contains("Health"))
        assertFalse(text.contains("Wakeups today"))
    }

    @Test fun noSubtitleNoBlankLine() {
        assertEquals("T\n\nBattery\n  Level: 64 %\n", ShareReport.text("T", "", sections.take(1)))
    }

    @Test fun linesFeedTheImageCard() {
        val lines = ShareReport.lines(sections)
        assertEquals(5, lines.size)
        assertTrue(lines[0].heading)
        assertEquals("Battery", lines[0].left)
        assertEquals(ShareReport.Line(false, "Level", "64 %"), lines[1])
    }

    @Test fun nothingToShowGivesOnlyTheTitle() {
        assertEquals("T\n", ShareReport.text("T", "", listOf(Section("A", listOf(Row("x", null))))))
    }

    @Test fun deviceNameAddsTheMakerOnlyWhenMissing() {
        assertEquals("Google Pixel 8", ShareReport.deviceName("Google", "Pixel 8"))
        assertEquals("Samsung SM-S911B", ShareReport.deviceName("samsung", "SM-S911B"))
        assertEquals("OnePlus 12", ShareReport.deviceName("OnePlus", "OnePlus 12"))
        assertEquals("Pixel 8", ShareReport.deviceName("", "Pixel 8"))
    }
}
