package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Wakeups.Counters
import java.time.LocalDate

class WakeLimitTest {
    private val h = 3_600_000L
    private val days = (27 downTo 0).map { LocalDate.of(2026, 10, 18).minusDays(it.toLong()).toString() }

    private fun wl(vararg ms: Pair<Int, Long>) = ms.associate { (i, v) -> days[i] to Counters(wakelockMs = v) }

    /** Every day measured through a quiet app, so the denominator is the whole window. */
    private fun world(vararg apps: Pair<String, Map<String, Counters>>) =
        mapOf("quiet" to days.associateWith { Counters(cpuMs = 1000) }) + apps

    @Test fun twoHoursIsTheLimit() {
        assertFalse(WakeLimit.exceeds(2 * h - 1))
        assertTrue(WakeLimit.exceeds(2 * h))
    }

    @Test fun moreThanFivePercentOfTheDaysIsRegularly() {
        // 1 of 20 is exactly 5 %: not more.
        assertFalse(WakeLimit.regularly(1, 20))
        assertTrue(WakeLimit.regularly(1, 19))
        assertTrue(WakeLimit.regularly(2, 28))
        assertFalse(WakeLimit.regularly(1, 28))
        assertFalse(WakeLimit.regularly(0, 28))
    }

    @Test fun tooFewMeasuredDaysSayNothing() {
        assertFalse(WakeLimit.regularly(3, WakeLimit.MIN_MEASURED_DAYS - 1))
        assertTrue(WakeLimit.regularly(1, WakeLimit.MIN_MEASURED_DAYS))
    }

    @Test fun measuredDaysAreThoseWithAnyAppRow() {
        val all = mapOf(
            "a" to mapOf(days[3] to Counters(cpuMs = 5), days[4] to Counters(cpuMs = 5)),
            "b" to mapOf(days[4] to Counters(wakeups = 1), days[10] to Counters(wakeups = 1)),
        )
        assertEquals(setOf(days[3], days[4], days[10]), WakeLimit.measuredDays(all, days))
        // Rows outside the window do not count.
        assertEquals(setOf(days[10]), WakeLimit.measuredDays(all, days.drop(10)))
    }

    @Test fun verdictCountsDaysAtTheLimitAndTheWorstDay() {
        val all = world("chat" to wl(2 to 2 * h, 5 to 3 * h, 7 to 2 * h - 1, 9 to h))
        val v = WakeLimit.verdicts(all, days).getValue("chat")
        assertEquals(2, v.exceededDays)
        assertEquals(28, v.measuredDays)
        assertEquals(3 * h, v.worstMs)
        assertTrue(v.regularly)
    }

    @Test fun appsBelowTheLimitGetNoVerdict() {
        val all = world("chat" to wl(2 to 2 * h - 1))
        assertTrue(WakeLimit.verdicts(all, days).isEmpty())
    }

    @Test fun oneBadDayInAMonthIsNotRegularly() {
        val all = world("chat" to wl(20 to 5 * h))
        val v = WakeLimit.verdicts(all, days).getValue("chat")
        assertEquals(1, v.exceededDays)
        assertFalse(v.regularly)
        assertTrue(WakeLimit.flagged(all, days).isEmpty())
    }

    @Test fun unmeasuredDaysDoNotDiluteTheShare() {
        // Only 8 days measured, 1 of them above: 12.5 %. Counted over all 28 days it would be 3.6 %.
        val measured = days.takeLast(8)
        val all = mapOf(
            "quiet" to measured.associateWith { Counters(cpuMs = 1000) },
            "chat" to mapOf(measured[2] to Counters(wakelockMs = 4 * h)),
        )
        val v = WakeLimit.verdicts(all, days).getValue("chat")
        assertEquals(8, v.measuredDays)
        assertTrue(v.regularly)
    }

    @Test fun onlyTheLast28DaysCount() {
        val old = (60 downTo 28).map { LocalDate.of(2026, 10, 18).minusDays(it.toLong()).toString() }
        val all = world("chat" to old.associateWith { Counters(wakelockMs = 3 * h) })
        assertTrue(WakeLimit.verdicts(all, old + days).isEmpty())
    }

    @Test fun flaggedAreWorstFirst() {
        val all = world(
            "short" to wl(1 to 2 * h, 2 to 2 * h, 3 to 2 * h),
            "long" to wl(1 to 9 * h, 2 to 9 * h, 3 to 9 * h),
            "often" to wl(1 to 2 * h, 2 to 2 * h, 3 to 2 * h, 4 to 2 * h),
            "once" to wl(1 to 20 * h),
        )
        assertEquals(listOf("often", "long", "short"), WakeLimit.flagged(all, days).map { it.pkg })
    }
}
