package xyz.activityplus.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ChartTextTest {
    @Before fun english() { Format.locale = Locale.US }

    @After fun reset() { Format.locale = null }

    /** English sentences like the resources, so the tests read as what a user hears. */
    private val words = object : ChartText.Words {
        override fun empty(subject: String) = "$subject: no data yet"
        override fun flat(subject: String, value: String) = "$subject, steady at $value"
        override fun range(subject: String, low: String, high: String, now: String?) =
            "$subject, $low to $high" + (now?.let { ", now $it" } ?: "")
        override fun bars(subject: String, low: String, high: String) = "$subject, lowest $low, highest $high"
    }

    private val watts = { v: Double -> Format.number(v, 1) + " W" }

    @Test fun aCourseNamesLowHighAndNow() {
        assertEquals(
            "Power over the last few minutes, 2.1 W to 4.5 W, now 3.2 W",
            ChartText.describe("Power over the last few minutes", listOf(2.1, 4.5, 3.0, 3.2), watts, words),
        )
    }

    @Test fun gapsAreSkippedAndNowIsTheLastRealPoint() {
        assertEquals(
            "Power, 1.0 W to 3.0 W, now 2.0 W",
            ChartText.describe("Power", listOf(1.0, null, 3.0, 2.0, null), watts, words),
        )
    }

    @Test fun nothingToDrawSaysSoInsteadOfStayingSilent() {
        assertEquals("Power: no data yet", ChartText.describe("Power", emptyList(), watts, words))
        assertEquals("Power: no data yet", ChartText.describe("Power", listOf(null, null, null), watts, words))
    }

    @Test fun onePointIsNotACourse() {
        // The charts draw nothing below two points, and the description must match what is on screen.
        assertEquals("Power: no data yet", ChartText.describe("Power", listOf(null, 2.0, null), watts, words))
    }

    @Test fun equalValuesAreSteady() {
        assertEquals("Memory, steady at 3.0 W", ChartText.describe("Memory", listOf(3.0, 3.0, 3.0), watts, words))
    }

    @Test fun valuesThatReadTheSameAreSteadyToo() {
        // 3.01 and 3.04 both print as 3.0: "3.0 W to 3.0 W" would be nonsense.
        assertEquals("Memory, steady at 3.0 W", ChartText.describe("Memory", listOf(3.01, 3.04, 3.02), watts, words))
    }

    @Test fun aTypicalDayHasNoNow() {
        assertEquals(
            "Typical day, 0.5 W to 2.0 W",
            ChartText.describe("Typical day", listOf(0.5, 2.0, 1.0), watts, words, latest = false),
        )
    }

    @Test fun notANumberOrInfinityIsNeverSpoken() {
        assertEquals(
            "Power, 1.0 W to 2.0 W, now 2.0 W",
            ChartText.describe("Power", listOf(1.0, Double.NaN, Double.POSITIVE_INFINITY, 2.0), watts, words),
        )
    }

    @Test fun negativeValuesKeepTheirSign() {
        assertEquals(
            "Temperature, -3.0 W to 4.0 W, now 4.0 W",
            ChartText.describe("Temperature", listOf(-3.0, 4.0), watts, words),
        )
    }

    @Test fun summaryOfNothingIsNull() {
        assertEquals(null, ChartText.summary(listOf(null, null)))
        assertEquals(null, ChartText.summary(emptyList()))
    }

    @Test fun summaryCountsMinMaxLastAndAverage() {
        val s = ChartText.summary(listOf(4.0, null, 2.0, 6.0))!!
        assertEquals(3, s.count)
        assertEquals(2.0, s.min, 0.0)
        assertEquals(6.0, s.max, 0.0)
        assertEquals(6.0, s.last, 0.0)
        assertEquals(4.0, s.average, 1e-9)
    }

    @Test fun barsNameLowestAndHighestWithoutNow() {
        assertEquals(
            "CPU clock per core, lowest 20 %, highest 100 %",
            ChartText.describeBars("CPU clock per core", listOf(1.0, 0.2, null, 0.5), { Format.percent(it) }, words),
        )
    }

    @Test fun barsWithoutAnyValueSaySo() {
        assertEquals("CPU clock per core: no data yet", ChartText.describeBars("CPU clock per core", listOf(null, null), { Format.percent(it) }, words))
        assertEquals("CPU clock per core: no data yet", ChartText.describeBars("CPU clock per core", emptyList(), { Format.percent(it) }, words))
    }

    @Test fun oneBarIsStillDescribed() {
        // A phone with one reporting core still has a chart on screen, unlike a line from one point.
        assertEquals(
            "CPU clock per core, lowest 50 %, highest 50 %",
            ChartText.describeBars("CPU clock per core", listOf(0.5), { Format.percent(it) }, words),
        )
    }
}
