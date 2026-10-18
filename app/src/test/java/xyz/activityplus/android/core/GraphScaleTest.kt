package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.GraphScale.Sample

class GraphScaleTest {
    private val min = 60_000L

    private fun at(minute: Int, value: Double, tag: Int = 0) = Sample(minute * min, value, tag)

    private fun scale(
        samples: List<Sample>,
        includeZero: Boolean = false,
        bounds: ClosedFloatingPointRange<Double>? = null,
        minSpan: Double = 0.1,
        maxGapMs: Long = GraphScale.MAX_GAP_MS,
    ) = GraphScale.scale(
        samples, 0, 100 * min, 200f, 100f, minSpan, pad = 10f, includeZero = includeZero, bounds = bounds, maxGapMs = maxGapMs,
    )

    @Test fun timeRunsLeftToRightAndHighValuesAreAtTheTop() {
        val s = scale(listOf(at(0, 0.2), at(50, 0.6), at(100, 1.0)), maxGapMs = 60 * min)
        val p = s.segments.single().points
        assertEquals(10f, p[0].x, 0.01f)
        assertEquals(100f, p[1].x, 0.01f)
        assertEquals(190f, p[2].x, 0.01f)
        assertEquals(90f, p[0].y, 0.01f) // lowest value: bottom of the plot
        assertEquals(10f, p[2].y, 0.01f) // highest: top
        assertEquals(0.2, s.min, 1e-9)
        assertEquals(1.0, s.max, 1e-9)
    }

    @Test fun aFlatLineGetsTheMinimumSpanAroundIt() {
        val (lo, hi) = GraphScale.range(listOf(0.5, 0.5), 0.1)
        assertEquals(0.45, lo, 1e-9)
        assertEquals(0.55, hi, 1e-9)
        val p = scale(listOf(at(0, 0.5), at(10, 0.5))).segments.single().points
        assertEquals(50f, p[0].y, 0.01f) // in the middle, not on an edge
    }

    @Test fun boundsMoveTheRangeInsteadOfCuttingIt() {
        val (lo, hi) = GraphScale.range(listOf(0.98, 1.0), 0.1, bounds = 0.0..1.0)
        assertEquals(0.9, lo, 1e-9)
        assertEquals(1.0, hi, 1e-9)
        val (lo2, hi2) = GraphScale.range(listOf(0.0, 0.01), 0.1, bounds = 0.0..1.0)
        assertEquals(0.0, lo2, 1e-9)
        assertEquals(0.1, hi2, 1e-9)
    }

    @Test fun powerGraphKeepsZeroInsideAndReportsItsLine() {
        val s = scale(listOf(at(0, 2.0), at(10, 4.0)), includeZero = true, minSpan = 1.0)
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(4.0, s.max, 1e-9)
        assertEquals(90f, s.zeroY!!, 0.01f)
    }

    @Test fun noZeroLineWhenNotAsked() {
        assertNull(scale(listOf(at(0, 2.0), at(10, 4.0))).zeroY)
    }

    @Test fun nearZeroPowerStillGetsARange() {
        val (lo, hi) = GraphScale.range(listOf(0.0, 0.0), 0.5, includeZero = true)
        assertEquals(0.0, lo, 1e-9)
        assertEquals(0.5, hi, 1e-9)
        val (lo2, hi2) = GraphScale.range(listOf(-0.1), 0.5, includeZero = true)
        assertEquals(-0.5, lo2, 1e-9)
        assertEquals(0.0, hi2, 1e-9)
    }

    @Test fun aHoleInTheHistoryBreaksTheLine() {
        val s = scale(listOf(at(0, 0.5), at(1, 0.5), at(2, 0.5), at(40, 0.4), at(41, 0.4)))
        assertEquals(2, s.segments.size)
        assertEquals(3, s.segments[0].points.size)
        assertEquals(2, s.segments[1].points.size)
    }

    @Test fun aChangedTagStartsASegmentThatMeetsTheOldOne() {
        val s = scale(listOf(at(0, 0.5, 0), at(1, 0.5, 0), at(2, 0.6, 1), at(3, 0.7, 1)))
        assertEquals(2, s.segments.size)
        assertEquals(0, s.segments[0].tag)
        assertEquals(1, s.segments[1].tag)
        // The charging segment begins where the discharging one ended.
        assertEquals(s.segments[0].points.last(), s.segments[1].points.first())
        assertEquals(3, s.segments[1].points.size)
    }

    @Test fun samplesOutsideTheWindowAndBadValuesAreDropped() {
        val s = scale(listOf(at(-5, 0.1), at(10, Double.NaN), at(20, 0.5), at(30, 0.6), at(120, 0.9)))
        assertEquals(2, s.segments.single().points.size)
        assertEquals(0.5, s.min, 1e-6) // the range comes from the two samples inside only
    }

    @Test fun unsortedSamplesAreSorted() {
        val p = scale(listOf(at(10, 0.5), at(0, 0.4), at(5, 0.45))).segments.single().points
        assertTrue(p[0].x < p[1].x && p[1].x < p[2].x)
    }

    @Test fun noSamplesNoSegments() {
        val s = scale(emptyList())
        assertTrue(s.segments.isEmpty())
        assertNotNull(s.max)
    }
}
