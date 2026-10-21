package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.activityplus.android.core.SelfCost.Counters
import xyz.activityplus.android.core.SelfCost.Energy
import xyz.activityplus.android.core.SelfCost.Job
import xyz.activityplus.android.core.SelfCost.Need
import xyz.activityplus.android.core.SelfCost.Reading
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class SelfCostTest {
    private val min = 60_000L
    private val h = 3_600_000L
    private val zone = ZoneId.of("Europe/Vienna")
    private fun at(y: Int, mo: Int, d: Int, hh: Int, mm: Int = 0) = ZonedDateTime.of(y, mo, d, hh, mm, 0, 0, zone).toInstant().toEpochMilli()
    private fun reading(t: Long, battery: Long?, c: Counters) = Reading(t, battery, c)

    @Before fun setUp() { Format.locale = Locale.US }

    // Per-day deltas

    @Test fun growthSubtractsAndTakesResetCountersAsTheyAre() {
        val prev = Counters(cpuMs = 1000, wakelockMs = 500, wakeups = 10, serviceMs = 5000)
        val next = Counters(cpuMs = 3000, wakelockMs = 200, wakeups = 12, serviceMs = 9000)
        // The wakelock counter went down: Android reset it, so 200 ms is what happened since.
        assertEquals(Counters(cpuMs = 2000, wakelockMs = 200, wakeups = 2, serviceMs = 4000), SelfCost.growth(prev, next))
    }

    @Test fun lessTimeOnBatteryOrMuchLessCpuIsAReset() {
        val prev = reading(10 * h, 5 * h, Counters(cpuMs = 10_000))
        assertTrue(SelfCost.isReset(prev, reading(11 * h, 10 * min, Counters(cpuMs = 20_000))))
        assertFalse(SelfCost.isReset(prev, reading(11 * h, 6 * h, Counters(cpuMs = 20_000))))
        val noBattery = reading(10 * h, null, Counters(cpuMs = 100_000))
        assertTrue(SelfCost.isReset(noBattery, reading(11 * h, null, Counters(cpuMs = 2_000))))
        assertFalse(SelfCost.isReset(noBattery, reading(11 * h, null, Counters(cpuMs = 120_000))))
    }

    @Test fun intervalIsTheGrowthBetweenTwoReadings() {
        val prev = reading(10 * h, 2 * h, Counters(cpuMs = 1000, wakeups = 3, dataBytes = 0))
        val next = reading(11 * h, 3 * h, Counters(cpuMs = 1800, wakeups = 4, dataBytes = 0))
        val i = SelfCost.interval(prev, next)!!
        assertFalse(i.reset)
        assertEquals(10 * h, i.fromMillis)
        assertEquals(Counters(cpuMs = 800, wakeups = 1), i.counters)
    }

    @Test fun intervalAfterAResetStartsWhereBatteryTimeSays() {
        val prev = reading(10 * h, 5 * h, Counters(cpuMs = 90_000))
        // 20 minutes on battery since the reset, 50 minutes after the last reading: the counters cover the last 20 minutes.
        val next = reading(10 * h + 50 * min, 20 * min, Counters(cpuMs = 4_000, wakelockMs = 2 * h))
        val i = SelfCost.interval(prev, next)!!
        assertTrue(i.reset)
        assertEquals(10 * h + 30 * min, i.fromMillis)
        assertEquals(4_000L, i.counters.cpuMs)
        // The wakelock cannot be longer than the time it covers.
        assertEquals(20 * min, i.counters.wakelockMs)
    }

    @Test fun firstReadingCountsFromAndroidsReset() {
        val first = reading(12 * h, 3 * h, Counters(cpuMs = 7000, wakeups = 2))
        val i = SelfCost.interval(null, first)!!
        assertTrue(i.reset)
        assertEquals(9 * h, i.fromMillis)
        assertEquals(first.counters, i.counters)
    }

    @Test fun noIntervalWithoutAnyWayToDateIt() {
        // First reading and the phone does not say how long it ran on battery: only a base for the next one.
        assertNull(SelfCost.interval(null, reading(h, null, Counters(cpuMs = 5))))
        val r = reading(h, h, Counters())
        assertNull(SelfCost.interval(r, r))
        assertNull(SelfCost.interval(r, r.copy(timeMillis = h - 1)))
    }

    @Test fun splitKeepsEveryCount() {
        val parts = SelfCost.split(Counters(wakeups = 5, cpuMs = 1001), listOf(0.5, 0.3, 0.2))
        assertEquals(5L, parts.sumOf { it.wakeups })
        assertEquals(1001L, parts.sumOf { it.cpuMs })
    }

    @Test fun intervalAcrossMidnightIsSplitPerDayAndEveryDayIsMeasured() {
        val i = SelfCost.Interval(at(2026, 3, 9, 23, 0), at(2026, 3, 10, 1, 0), false, Counters(cpuMs = 1000, wakeups = 4))
        val byDay = SelfCost.byDay(i, zone)
        assertEquals(listOf("2026-03-09", "2026-03-10"), byDay.keys.toList())
        assertEquals(500L, byDay.getValue("2026-03-09").cpuMs)
        assertEquals(4L, byDay.values.sumOf { it.wakeups })
    }

    @Test fun anIntervalWithoutGrowthStillMarksTheDayAsMeasured() {
        val i = SelfCost.Interval(at(2026, 3, 9, 10), at(2026, 3, 9, 11), false, Counters.ZERO)
        val byDay = SelfCost.byDay(i, zone)
        assertEquals(setOf("2026-03-09"), byDay.keys)
        // A charging day: measured, nothing counted. The card shows 0, not a dash.
        val sum = SelfCost.sum(byDay, listOf("2026-03-08", "2026-03-09"))
        assertEquals(1, sum.measuredDays)
        assertTrue(sum.counters.isZero)
    }

    @Test fun sumAddsOnlyTheDaysAsked() {
        val series = mapOf("a" to Counters(cpuMs = 1000, wakeups = 1), "b" to Counters(cpuMs = 2000), "c" to Counters(cpuMs = 4000))
        val s = SelfCost.sum(series, listOf("b", "c", "d"))
        assertEquals(6000L, s.counters.cpuMs)
        assertEquals(2, s.measuredDays)
        assertEquals(0, SelfCost.sum(series, listOf("x")).measuredDays)
    }

    // When to read

    @Test fun hourlyReadingOnBatteryOnly() {
        assertTrue(SelfCost.due(h, null, plugged = false, plugChanged = false))
        assertFalse(SelfCost.due(h + 59 * min, h, plugged = false, plugChanged = false))
        assertTrue(SelfCost.due(2 * h, h, plugged = false, plugChanged = false))
        // Charging: Android does not count, nothing can have changed.
        assertFalse(SelfCost.due(5 * h, h, plugged = true, plugChanged = false))
        // The plug changed: a charge resets the counters, so read right away.
        assertTrue(SelfCost.due(h + 5 * min, h, plugged = true, plugChanged = true))
        assertTrue(SelfCost.due(h + 5 * min, h, plugged = false, plugChanged = true))
    }

    @Test fun heavyWorkWaitsForTheScreenAtNight() {
        val period = 30 * min
        // On screen or charging: whenever it is due.
        assertTrue(SelfCost.mayRunHeavy(screenOn = true, plugged = false, doze = false, sinceLastMs = period, periodMs = period))
        assertTrue(SelfCost.mayRunHeavy(screenOn = false, plugged = true, doze = false, sinceLastMs = period, periodMs = period))
        // Screen off on battery: not just because it is due ...
        assertFalse(SelfCost.mayRunHeavy(screenOn = false, plugged = false, doze = false, sinceLastMs = period, periodMs = period))
        assertFalse(SelfCost.mayRunHeavy(screenOn = false, plugged = false, doze = false, sinceLastMs = null, periodMs = period))
        // ... but when a whole period late.
        assertTrue(SelfCost.mayRunHeavy(screenOn = false, plugged = false, doze = false, sinceLastMs = 2 * period, periodMs = period))
    }

    @Test fun heavyWorkNeverRunsInDoze() {
        assertFalse(SelfCost.mayRunHeavy(screenOn = false, plugged = false, doze = true, sinceLastMs = 10 * h, periodMs = 30 * min))
        assertFalse(SelfCost.mayRunHeavy(screenOn = false, plugged = true, doze = true, sinceLastMs = 10 * h, periodMs = 30 * min))
    }

    // Energy: every state has an answer

    @Test fun energyKnownWithPercentOfACharge() {
        val e = SelfCost.energy(hasGrant = true, measureOn = true, measuredDays = 3, mah = 6.0, capacityMah = 4000.0) as Energy.Known
        assertEquals(6.0, e.mah, 1e-9)
        assertEquals(0.15, e.percentOfCharge!!, 1e-9)
    }

    @Test fun energyKnownWithoutCapacityHasNoPercent() {
        val e = SelfCost.energy(true, true, 1, 2.0, null) as Energy.Known
        assertNull(e.percentOfCharge)
        assertNull((SelfCost.energy(true, true, 1, 2.0, 0.0) as Energy.Known).percentOfCharge)
    }

    @Test fun energyWithoutTheGrantSaysSo() {
        // Whatever else is true: without the grant there is no battery report to read.
        assertEquals(Energy.NeedsGrant, SelfCost.energy(false, true, 5, 3.0, 4000.0))
        assertEquals(Energy.NeedsGrant, SelfCost.energy(false, false, 0, 0.0, null))
    }

    @Test fun energyWithGrantButSettingOffAndNoReportYet() {
        assertEquals(Energy.SettingOff, SelfCost.energy(true, false, 5, 3.0, 4000.0))
        assertEquals(Energy.NotYet, SelfCost.energy(true, true, 0, 0.0, 4000.0))
    }

    // Formatting decisions

    @Test fun durationsShowTinyTimesAsLessThanASecond() {
        assertEquals("0 s", SelfCost.durationText(0))
        assertEquals("<1 s", SelfCost.durationText(40))
        assertEquals("12 s", SelfCost.durationText(12_400))
        assertEquals("2 h 05 min", SelfCost.durationText(2 * h + 5 * min))
    }

    @Test fun mahKeepsPrecisionWhereItMatters() {
        assertEquals("0 mAh", SelfCost.mahText(0.0))
        assertEquals("<0.1 mAh", SelfCost.mahText(0.04))
        assertEquals("3.2 mAh", SelfCost.mahText(3.17))
        assertEquals("42 mAh", SelfCost.mahText(42.3))
    }

    @Test fun percentOfACharge() {
        assertEquals("<0.1 %", SelfCost.percentText(0.02))
        assertEquals("0.4 %", SelfCost.percentText(0.4))
        assertEquals("12.0 %", SelfCost.percentText(12.0))
    }

    @Test fun dataIsShownAsZeroBytes() {
        assertEquals("0 B", Format.bytes(0).toString())
    }

    // What runs

    private val everything = SelfCost.Env(live = true, batteryStats = true, computerGrant = true, backgroundMeasure = true, powerMonitors = true, weekly = true, widgets = true)
    private val plainUser = SelfCost.Env(live = true, batteryStats = false, computerGrant = false, backgroundMeasure = true, powerMonitors = false, weekly = true, widgets = false)

    @Test fun withEverythingOnEveryJobRuns() {
        assertTrue(Job.entries.all { SelfCost.need(it, everything) == Need.NONE })
    }

    @Test fun aDefaultSetupRunsOnlyWhatNeedsNoGrant() {
        val need = Job.entries.associateWith { SelfCost.need(it, plainUser) }
        assertEquals(Need.NONE, need[Job.MONITOR])
        assertEquals(Need.NONE, need[Job.NIGHT])
        assertEquals(Need.NONE, need[Job.SELF_COST])
        assertEquals(Need.BATTERY_STATS, need[Job.WAKEUPS])
        assertEquals(Need.COMPUTER_GRANT, need[Job.SENSORS])
        assertEquals(Need.COMPUTER_GRANT, need[Job.BACKGROUND])
        assertEquals(Need.UNSUPPORTED, need[Job.POWER_RAILS])
        assertEquals(Need.NO_WIDGETS, need[Job.WIDGETS])
    }

    @Test fun backgroundMeasurementNeedsItsSettingToo() {
        assertEquals(Need.BACKGROUND_SETTING, SelfCost.need(Job.BACKGROUND, everything.copy(backgroundMeasure = false)))
    }

    @Test fun weeklyReportFollowsAlertsAndItsSetting() {
        assertEquals(Need.WEEKLY_SETTING, SelfCost.need(Job.WEEKLY, everything.copy(weekly = false)))
    }

    @Test fun withTheLiveStatusOffOnlyWidgetsAreLeft() {
        val off = everything.copy(live = false)
        assertEquals(Need.NONE, SelfCost.need(Job.WIDGETS, off))
        assertTrue(Job.entries.filter { it != Job.WIDGETS }.all { SelfCost.need(it, off) == Need.LIVE })
        assertEquals(Need.NO_WIDGETS, SelfCost.need(Job.WIDGETS, off.copy(widgets = false)))
    }
}
