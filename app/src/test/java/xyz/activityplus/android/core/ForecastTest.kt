package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Forecast.Sample
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ForecastTest {
    private val zone = ZoneOffset.UTC
    private val minute = 60_000L
    private val hour = 3_600_000L

    // 2026-10-05 is a Monday; the 10th and 11th are the weekend.
    private val monday = LocalDate.of(2026, 10, 5)

    private fun millis(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).toInstant(zone).toEpochMilli()

    /** One sample a minute through the day, the level starting at 100 % and falling by [rate] (percent per hour) of that hour. */
    private fun day(date: LocalDate, rate: (Int) -> Double): List<Sample> {
        val out = ArrayList<Sample>()
        var level = 100.0
        for (i in 0 until 24 * 60) {
            out += Sample(millis(date, 0) + i * minute, level / 100)
            level -= rate(i / 60) / 60
        }
        return out
    }

    private val weekdayRate = { h: Int -> if (h in 7..21) 5.0 else 0.5 }
    private val weekendRate = { h: Int -> if (h in 9..22) 3.0 else 0.5 }

    /** Two weeks: Monday 5th to Sunday 18th. */
    private fun twoWeeks(weekday: (Int) -> Double = weekdayRate, weekend: (Int) -> Double = weekendRate): List<Sample> =
        (0 until 14).flatMap { d ->
            val date = monday.plusDays(d.toLong())
            day(date, if (date.dayOfWeek.value >= 6) weekend else weekday)
        }

    @Test fun theProfileLearnsTheRateOfEachHour() {
        val p = Forecast.profile(twoWeeks(weekend = weekdayRate), emptyList(), zone)
        assertTrue(p.ready)
        assertEquals(5.0, p.all[12]!!, 0.05)
        assertEquals(0.5, p.all[2]!!, 0.05)
        assertEquals(5.0, p.rate(12, weekend = false), 0.05)
    }

    @Test fun weekendsHaveTheirOwnRatesWhenThereIsEnoughData() {
        val p = Forecast.profile(twoWeeks(), emptyList(), zone)
        assertEquals(5.0, p.weekday[12]!!, 0.05)
        assertEquals(3.0, p.weekend[12]!!, 0.05)
        // Saturday morning at 8 is quiet, a working Monday at 8 is not.
        assertEquals(0.5, p.rate(8, weekend = true), 0.05)
        assertEquals(5.0, p.rate(8, weekend = false), 0.05)
    }

    @Test fun anHourMissingOnWeekendsFallsBackToEveryDay() {
        // Only the five weekdays of one week: the weekend table is empty.
        val p = Forecast.profile((0 until 5).flatMap { day(monday.plusDays(it.toLong()), weekdayRate) }, emptyList(), zone)
        assertNull(p.weekend[12])
        assertEquals(5.0, p.rate(12, weekend = true), 0.05)
    }

    @Test fun timeOnTheChargerIsNotUse() {
        // Wednesday sits on a charger at 100 %: flat, which would pull every hour down if it counted.
        val flat = (0 until 24 * 60).map { Sample(millis(monday.plusDays(2), 0) + it * minute, 1.0) }
        val days = (0 until 14).filter { it != 2 }.flatMap { d ->
            val date = monday.plusDays(d.toLong())
            day(date, if (date.dayOfWeek.value >= 6) weekendRate else weekdayRate)
        }
        val samples = (days + flat).sortedBy { it.ts }
        val charger = listOf(millis(monday.plusDays(2), 0)..millis(monday.plusDays(3), 0))
        val withRange = Forecast.profile(samples, charger, zone)
        assertEquals(5.0, withRange.weekday[12]!!, 0.05)
        val without = Forecast.profile(samples, emptyList(), zone)
        assertTrue(without.weekday[12]!! < 4.5)
    }

    @Test fun chargingSamplesAreSkippedEvenWithoutAPluggedRange() {
        val rows = listOf(Sample(millis(monday, 0), 0.5, charging = true), Sample(millis(monday, 0, 1), 0.5, charging = true), Sample(millis(monday, 0, 2), 0.499))
        assertEquals(0.0, Forecast.profile(rows, emptyList(), zone).observedSeconds, 0.0)
    }

    @Test fun aDropAcrossASleepGapIsSpreadOverTheHoursBetween() {
        // Phone asleep from 01:00 to 06:00 (samples five hours apart): 5 points, 1 point per hour.
        val night = listOf(Sample(millis(monday, 1), 0.90), Sample(millis(monday, 6), 0.85))
        val p = Forecast.profile(night, emptyList(), zone)
        for (h in 1..5) assertEquals(1.0, p.weekday[h]!!, 1e-9)
        assertNull(p.weekday[0])
        assertNull(p.weekday[6])
    }

    @Test fun gapsOfSixHoursAndReboundsAreSkipped() {
        val long = listOf(Sample(millis(monday, 0), 0.9), Sample(millis(monday, 8), 0.5))
        assertEquals(0.0, Forecast.profile(long, emptyList(), zone).observedSeconds, 0.0)
        // A recalibration upwards is not use; a wobble of one point is a measurement of zero.
        val up = listOf(Sample(millis(monday, 0), 0.5), Sample(millis(monday, 0, 1), 0.9))
        assertEquals(0.0, Forecast.profile(up, emptyList(), zone).observedSeconds, 0.0)
        val wobble = listOf(Sample(millis(monday, 0), 0.50), Sample(millis(monday, 0, 1), 0.51))
        assertEquals(60.0, Forecast.profile(wobble, emptyList(), zone).observedSeconds, 1e-9)
    }

    @Test fun tooLittleHistoryIsNotReady() {
        assertFalse(Forecast.profile(emptyList(), emptyList(), zone).ready)
        // One day of 24 hours is enough in time, but not 10 hours of one afternoon.
        val afternoon = day(monday, weekdayRate).filter { it.ts >= millis(monday, 8) && it.ts < millis(monday, 18) }
        assertFalse(Forecast.profile(afternoon, emptyList(), zone).ready)
        assertTrue(Forecast.profile(day(monday, weekdayRate) + day(monday.plusDays(1), weekdayRate), emptyList(), zone).ready)
    }

    @Test fun reachingTheTargetWalksThroughTheHoursOfTheDay() {
        val p = Forecast.profile(twoWeeks(weekend = weekdayRate), emptyList(), zone)
        // Tuesday 20:00 at 30 %: 2 h at 5 %/h to 22:00 (20 % left), the night at 0.5 %/h until 07:00 (15.5 % left), then 3.1 h.
        val now = millis(monday.plusDays(1), 20)
        val at = Forecast.reach(p, now, 0.30, 0.0, zone)!!
        val expected = now + 2 * hour + 9 * hour + (3.1 * hour).toLong()
        assertEquals(expected.toDouble(), at.toDouble(), 2.0 * minute)
    }

    @Test fun theWeekendProfileIsUsedOnWeekendDays() {
        val p = Forecast.profile(twoWeeks(), emptyList(), zone)
        // 10:00 with 30 points to go: Saturday at 3 %/h takes 10 h, Monday at 5 %/h takes 6 h.
        val saturday = Forecast.reach(p, millis(monday.plusDays(5), 10), 0.30, 0.0, zone)!!
        val weekdayRun = Forecast.reach(p, millis(monday, 10), 0.30, 0.0, zone)!!
        assertEquals(10.0, (saturday - millis(monday.plusDays(5), 10)) / hour.toDouble(), 0.3)
        assertEquals(6.0, (weekdayRun - millis(monday, 10)) / hour.toDouble(), 0.3)
    }

    @Test fun nowWhenAlreadyThereAndNullBeyondThreeDays() {
        val p = Forecast.profile(twoWeeks(), emptyList(), zone)
        val now = millis(monday, 12)
        assertEquals(now, Forecast.reach(p, now, 0.10, 0.15, zone))
        assertEquals(now, Forecast.reachAtCurrentRate(now, 0.10, 0.15, -3.0))
        // A phone that hardly moves still gets an answer, but not for a full battery at the floor rate.
        val quiet = Forecast.profile(twoWeeks({ 0.0 }, { 0.0 }), emptyList(), zone)
        assertNull(Forecast.reach(quiet, now, 1.0, 0.0, zone))
    }

    @Test fun theCurrentRateIsAStraightLine() {
        val now = millis(monday, 12)
        // Percent maths in doubles: a second is close enough.
        assertEquals((now + 5 * hour).toDouble(), Forecast.reachAtCurrentRate(now, 0.5, 0.0, -10.0)!!.toDouble(), 1000.0)
        assertEquals((now + 3 * hour).toDouble(), Forecast.reachAtCurrentRate(now, 0.5, 0.2, -10.0)!!.toDouble(), 1000.0)
        assertNull(Forecast.reachAtCurrentRate(now, 0.5, 0.0, null))
        assertNull(Forecast.reachAtCurrentRate(now, 0.5, 0.0, -0.1))
        assertNull(Forecast.reachAtCurrentRate(now, 0.5, 0.0, 4.0))
        assertNull("more than three days", Forecast.reachAtCurrentRate(now, 1.0, 0.0, -1.0))
    }

    @Test fun withoutAProfileOnlyTheCurrentRateIsThere() {
        val now = millis(monday, 12)
        val o = Forecast.outcome(null, now, 0.5, 0.0, -10.0, zone)
        assertFalse(o.patternReady)
        assertNull(o.pattern)
        assertEquals((now + 5 * hour).toDouble(), o.current!!.toDouble(), 1000.0)
        assertFalse(o.differs(now))
        val thin = Forecast.outcome(Forecast.profile(emptyList(), emptyList(), zone), now, 0.5, 0.0, -10.0, zone)
        assertFalse(thin.patternReady)
    }

    @Test fun bothAreShownOnlyWhenTheyDifferMeaningfully() {
        val now = millis(monday, 12)
        fun o(pattern: Long, current: Long) = Forecast.Outcome(true, pattern, current)
        // 10 h left: 20 % is 2 h.
        assertFalse(o(now + 10 * hour, now + 11 * hour).differs(now))
        assertTrue(o(now + 10 * hour, now + 13 * hour).differs(now))
        // 1 h left: the 30 min floor decides, not 20 % of an hour.
        assertFalse(o(now + hour, now + hour + 20 * minute).differs(now))
        assertTrue(o(now + hour, now + hour + 30 * minute).differs(now))
        assertFalse(Forecast.Outcome(true, null, now + hour).differs(now))
    }

    @Test fun theOutcomeUsesThePatternWhenReady() {
        val p = Forecast.profile(twoWeeks(weekend = weekdayRate), emptyList(), zone)
        val now = millis(monday.plusDays(1), 20)
        val o = Forecast.outcome(p, now, 0.30, 0.0, -5.0, zone)
        assertTrue(o.patternReady)
        assertNotNull(o.pattern)
        // At the current 5 %/h the phone would be empty in 6 h, the usual night makes it last much longer.
        assertEquals((now + 6 * hour).toDouble(), o.current!!.toDouble(), 1000.0)
        assertTrue(o.differs(now))
    }

    @Test fun theChartShowsTheHoursOfADayKind() {
        val p = Forecast.profile(twoWeeks(), emptyList(), zone)
        assertEquals(24, p.day(weekend = false).size)
        assertEquals(5.0, p.day(false)[12]!!, 0.05)
        assertEquals(3.0, p.day(true)[12]!!, 0.05)
        assertTrue(Forecast.profile(emptyList(), emptyList(), zone).day(false).all { it == null })
    }
}
