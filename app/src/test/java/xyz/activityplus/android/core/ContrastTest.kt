package xyz.activityplus.android.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.ui.theme.BannerEnd
import xyz.activityplus.android.ui.theme.BannerStart
import xyz.activityplus.android.ui.theme.DarkSurfaces
import xyz.activityplus.android.ui.theme.LightPrimary
import xyz.activityplus.android.ui.theme.LightSurfaces
import xyz.activityplus.android.ui.theme.Metric
import xyz.activityplus.android.ui.theme.Surfaces

class ContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private val metrics = mapOf(
        "Cpu" to Metric.Cpu, "Memory" to Metric.Memory, "Gpu" to Metric.Gpu, "Disk" to Metric.Disk,
        "Network" to Metric.Network, "Energy" to Metric.Energy, "Heat" to Metric.Heat, "Warn" to Metric.Warn,
    )
    private val themes = mapOf("dark" to DarkSurfaces, "light" to LightSurfaces)

    private fun Color.argb() = toArgb()

    // The formula ------------------------------------------------------------------------------

    @Test fun blackOnWhiteIsTheMaximum() = assertEquals(21.0, Contrast.ratio(black, white), 1e-6)

    @Test fun sameColourIsOne() = assertEquals(1.0, Contrast.ratio(0xFF3E6BFF.toInt(), 0xFF3E6BFF.toInt()), 1e-9)

    @Test fun orderDoesNotMatter() {
        val a = 0xFF3E6BFF.toInt()
        assertEquals(Contrast.ratio(a, white), Contrast.ratio(white, a), 1e-12)
    }

    @Test fun knownValueFromTheWcagExamples() {
        // #777 on white is the textbook 4.48:1, just below AA for text.
        assertEquals(4.48, Contrast.ratio(0xFF777777.toInt(), white), 0.01)
    }

    @Test fun overBlendsPerChannel() {
        assertEquals(0xFF808080.toInt(), Contrast.over(white, black, 0.5f))
        assertEquals(0xFF123456.toInt(), Contrast.over(0xFF123456.toInt(), black, 1f))
        assertEquals(black, Contrast.over(white, black, 0f))
    }

    // ensure() -----------------------------------------------------------------------------------

    @Test fun aColourThatPassesIsLeftAlone() {
        val c = 0xFF26A862.toInt()
        assertEquals(c, Contrast.ensure(c, Contrast.TEXT, 0xFF171A33.toInt()))
    }

    @Test fun onLightBackgroundsItDarkensUntilItPasses() {
        val warn = Metric.Warn.argb()
        val fixed = Contrast.ensure(warn, Contrast.TEXT, white)
        assertTrue(Contrast.ratio(fixed, white) >= Contrast.TEXT)
        assertTrue(Contrast.luminance(fixed) < Contrast.luminance(warn))
    }

    @Test fun onDarkBackgroundsItLightensUntilItPasses() {
        val cpu = Metric.Cpu.argb()
        val fixed = Contrast.ensure(cpu, Contrast.TEXT, DarkSurfaces.card.argb())
        assertTrue(Contrast.ratio(fixed, DarkSurfaces.card.argb()) >= Contrast.TEXT)
        assertTrue(Contrast.luminance(fixed) > Contrast.luminance(cpu))
    }

    @Test fun theHueSurvives() {
        // Amber stays amber: red still leads, blue stays the weakest channel.
        val fixed = Contrast.ensure(Metric.Warn.argb(), Contrast.TEXT, white)
        val r = fixed shr 16 and 0xFF
        val g = fixed shr 8 and 0xFF
        val b = fixed and 0xFF
        assertTrue(r > g && g > b)
    }

    @Test fun itPassesOnEveryGivenBackgroundAtOnce() {
        val s = LightSurfaces
        val fixed = Contrast.ensure(Metric.Network.argb(), Contrast.TEXT, s.card.argb(), s.cardRaised.argb(), s.background.argb())
        for (bg in listOf(s.card, s.cardRaised, s.background)) assertTrue(Contrast.ratio(fixed, bg.argb()) >= Contrast.TEXT)
    }

    @Test fun theFixIsMinimalNotJustBlack() {
        // Stopped at the first step that passes: slightly more than needed at most, never plain black.
        val fixed = Contrast.ensure(Metric.Disk.argb(), Contrast.TEXT, white)
        assertTrue(Contrast.ratio(fixed, white) < Contrast.TEXT + 0.6)
    }

    // The app's palette ------------------------------------------------------------------------

    /** What the Compose side computes: text must pass on the card and the raised card, graphics on the card. */
    private fun text(c: Color, s: Surfaces) = Contrast.ensure(c.argb(), Contrast.TEXT, s.card.argb(), s.cardRaised.argb())
    private fun graphic(c: Color, s: Surfaces) = Contrast.ensure(c.argb(), Contrast.GRAPHIC, s.card.argb(), s.cardRaised.argb())

    @Test fun everyMetricColourIsReadableAsTextOnBothThemes() {
        for ((theme, s) in themes) for ((name, c) in metrics) {
            val shown = text(c, s)
            assertTrue("$name text on $theme card", Contrast.ratio(shown, s.card.argb()) >= Contrast.TEXT)
            assertTrue("$name text on $theme raised card", Contrast.ratio(shown, s.cardRaised.argb()) >= Contrast.TEXT)
        }
    }

    @Test fun everyMetricColourIsVisibleAsAGraphicOnBothThemes() {
        for ((theme, s) in themes) for ((name, c) in metrics) {
            assertTrue("$name graphic on $theme card", Contrast.ratio(graphic(c, s), s.card.argb()) >= Contrast.GRAPHIC)
        }
    }

    @Test fun coloursThatAlreadyPassAreNotTouched() {
        // Dark theme: amber, orange and green were fine as text, so they look exactly as before.
        for (c in listOf(Metric.Warn, Metric.Disk, Metric.Energy, Metric.Network)) {
            assertEquals(c.argb(), text(c, DarkSurfaces))
        }
        // Dark graphics: all eight were fine (3:1), so no chart changes there.
        for ((_, c) in metrics) assertEquals(c.argb(), graphic(c, DarkSurfaces))
    }

    @Test fun theBadgeTintAlsoPasses() {
        // A badge text sits on 16 % of its own colour over the card.
        for ((theme, s) in themes) for ((name, c) in metrics) {
            val tint = Contrast.over(c.argb(), s.card.argb(), 0.16f)
            val shown = Contrast.ensure(c.argb(), Contrast.TEXT, tint)
            assertTrue("$name badge on $theme", Contrast.ratio(shown, tint) >= Contrast.TEXT)
        }
    }

    @Test fun theMutedTextIsReadableOnEverySurface() {
        for ((theme, s) in themes) for (bg in listOf(s.background, s.card, s.cardRaised)) {
            assertTrue("muted on $theme", Contrast.ratio(s.muted.argb(), bg.argb()) >= Contrast.TEXT)
        }
    }

    @Test fun lightButtonTextReadsOnWhiteAndOnThePageBackground() {
        assertTrue(Contrast.ratio(LightPrimary.argb(), white) >= Contrast.TEXT)
        assertTrue(Contrast.ratio(LightPrimary.argb(), LightSurfaces.background.argb()) >= Contrast.TEXT)
        // ... and a filled button's white label on it.
        assertTrue(Contrast.ratio(white, LightPrimary.argb()) >= Contrast.TEXT)
    }

    @Test fun whiteTextOnTheBannerGradientReadsAtBothEnds() {
        assertTrue(Contrast.ratio(white, BannerStart.argb()) >= Contrast.TEXT)
        assertTrue(Contrast.ratio(white, BannerEnd.argb()) >= Contrast.TEXT)
    }
}
