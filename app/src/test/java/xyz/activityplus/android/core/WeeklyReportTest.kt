package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.WeeklyReport.Change
import xyz.activityplus.android.core.WeeklyReport.Trend
import xyz.activityplus.android.core.WeeklyReport.WeekData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyReportTest {
    private val zone = ZoneId.of("Europe/Vienna")

    private fun millis(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    // 2026-10-12 is a Monday.
    private val weeks = WeeklyReport.weeks(millis(2026, 10, 12, 9), zone)

    private fun week(
        energy: Map<String, Pair<Double, Double>> = emptyMap(),
        screen: Map<String, Long> = emptyMap(),
        data: Map<String, Long> = emptyMap(),
        sessions: List<BatterySession> = emptyList(),
        days: Int = 7,
    ) = WeekData(energy, screen, data, sessions, days)

    private fun charge(start: Long, minutes: Int = 30) =
        BatterySession(true, start, start + minutes * 60_000L, 0.3, 0.8, 9_000.0, 18_000.0, 0.0, 0.0, 30.0)

    @Test fun weeksRunMondayToSunday() {
        for (day in 12..18) {
            val w = WeeklyReport.weeks(millis(2026, 10, day), zone)
            assertEquals(LocalDate.of(2026, 10, 5), w.last)
            assertEquals(LocalDate.of(2026, 9, 28), w.previous)
            assertEquals(LocalDate.of(2026, 10, 12), w.next)
        }
        assertEquals("2026-10-05", weeks.lastFirstDay)
        assertEquals("2026-10-11", weeks.lastLastDay)
        assertEquals("2026-09-28", weeks.previousFirstDay)
        assertEquals("2026-10-04", weeks.previousLastDay)
        assertEquals(millis(2026, 10, 5, 0), weeks.lastStart)
        assertEquals(millis(2026, 10, 12, 0), weeks.lastEnd)
        // Sunday night still belongs to the week before.
        assertEquals(LocalDate.of(2026, 9, 28), WeeklyReport.weeks(millis(2026, 10, 11, 23), zone).last)
    }

    @Test fun weekBoundsFollowDaylightSavingTime() {
        // Summer time ends on 2026-10-25: the week of 10-19 has 169 hours.
        val w = WeeklyReport.weeks(millis(2026, 10, 26, 9), zone)
        assertEquals((7 * 24 + 1) * 3_600_000L, w.lastEnd - w.lastStart)
    }

    @Test fun cardOnlyMondayToWednesday() {
        assertTrue(WeeklyReport.cardDay(millis(2026, 10, 12), zone))
        assertTrue(WeeklyReport.cardDay(millis(2026, 10, 14, 23), zone))
        assertFalse(WeeklyReport.cardDay(millis(2026, 10, 15, 0), zone))
        assertFalse(WeeklyReport.cardDay(millis(2026, 10, 18), zone))
    }

    @Test fun notificationOnlyOnMondayFromEight() {
        assertFalse(WeeklyReport.notifyTime(millis(2026, 10, 12, 7), zone))
        assertTrue(WeeklyReport.notifyTime(millis(2026, 10, 12, 8), zone))
        assertTrue(WeeklyReport.notifyTime(millis(2026, 10, 12, 23), zone))
        assertFalse(WeeklyReport.notifyTime(millis(2026, 10, 13, 9), zone))
    }

    @Test fun changeBands() {
        assertEquals(Change(Trend.UP, 40), WeeklyReport.change(140.0, 100.0, true, true))
        assertEquals(Change(Trend.DOWN, 25), WeeklyReport.change(75.0, 100.0, true, true))
        assertEquals(Change(Trend.FLAT), WeeklyReport.change(103.0, 100.0, true, true))
        assertEquals(Change(Trend.FLAT), WeeklyReport.change(96.0, 100.0, true, true))
        assertEquals(Change(Trend.NEW), WeeklyReport.change(10.0, 0.0, true, true))
        assertEquals(Change(Trend.NONE), WeeklyReport.change(10.0, 0.0, true, false))
        assertEquals(Change(Trend.NONE), WeeklyReport.change(10.0, 5.0, false, true))
    }

    @Test fun topFiveSortedWithChangeAndNew() {
        val now = mapOf("a" to 100.0, "b" to 300.0, "c" to 50.0, "d" to 200.0, "e" to 10.0, "f" to 5.0, "z" to 0.0)
        val before = mapOf("b" to 150.0, "a" to 100.0, "d" to 400.0)
        val top = WeeklyReport.top(now, before, known = true)
        assertEquals(listOf("b", "d", "a", "c", "e"), top.map { it.pkg })
        assertEquals(Change(Trend.UP, 100), top[0].change)
        assertEquals(Change(Trend.DOWN, 50), top[1].change)
        assertEquals(Change(Trend.FLAT), top[2].change)
        assertEquals(Change(Trend.NEW), top[3].change)
    }

    @Test fun tiesBreakByPackageName() {
        val top = WeeklyReport.top(mapOf("b" to 1.0, "a" to 1.0), emptyMap(), known = false)
        assertEquals(listOf("a", "b"), top.map { it.pkg })
        assertEquals(Trend.NONE, top[0].change.trend)
    }

    @Test fun lessThanThreeDaysIsNotEnough() {
        val r = WeeklyReport.build(weeks, week(energy = mapOf("a" to (100.0 to 60.0)), days = 2), week(), 4000.0, 3.85, null)
        assertFalse(r.enough)
        assertNull(r.headline)
        assertTrue(WeeklyReport.build(weeks, week(energy = mapOf("a" to (100.0 to 60.0)), days = 3), week(), 4000.0, 3.85, null).enough)
    }

    @Test fun reportListsTotalsAndChanges() {
        val last = week(
            energy = mapOf(
                "chrome" to (1400.0 to 3600.0), "maps" to (600.0 to 1200.0), DrainTracker.SCREEN_ON_OTHER to (200.0 to 500.0),
                "xyz.activityplus.android" to (900.0 to 100.0),
                // 10 hours with the screen off at 154 mWh each hour: 1 % of a 4000 mAh battery at 3.85 V.
                DrainTracker.SCREEN_OFF to (1540.0 to 36_000.0),
            ),
            screen = mapOf("chrome" to 7200L, "maps" to 600L, "xyz.activityplus.android" to 9999L),
            data = mapOf("chrome" to 5_000_000L, "maps" to 1_000L),
            sessions = listOf(charge(millis(2026, 10, 6)), charge(millis(2026, 10, 8)), charge(millis(2026, 10, 9), minutes = 1),
                charge(millis(2026, 10, 13))),
        )
        val before = week(
            energy = mapOf("chrome" to (1000.0 to 3000.0), DrainTracker.SCREEN_ON_OTHER to (100.0 to 100.0), DrainTracker.SCREEN_OFF to (770.0 to 36_000.0)),
            screen = mapOf("chrome" to 7200L),
            data = mapOf("chrome" to 10_000_000L),
            sessions = listOf(charge(millis(2026, 9, 29))),
        )
        val r = WeeklyReport.build(weeks, last, before, 4000.0, 3.85, "xyz.activityplus.android")

        assertEquals(listOf("chrome", "maps"), r.energy.map { it.pkg })
        assertEquals(Change(Trend.UP, 40), r.energy[0].change)
        assertEquals(Change(Trend.NEW), r.energy[1].change)
        assertEquals(listOf("chrome", "maps"), r.screenTime.map { it.pkg })
        assertEquals(Change(Trend.FLAT), r.screenTime[0].change)
        assertEquals(Change(Trend.DOWN, 50), r.data[0].change)
        assertEquals(Change(Trend.NEW), r.data[1].change)

        // Screen-on total includes the apps, own package and the "other" row, never the screen-off row.
        assertEquals(1400.0 + 600.0 + 200.0 + 900.0, r.screenOnMwh, 1e-9)
        assertEquals(1.0, r.screenOffPercentPerHour!!, 1e-9)
        assertEquals(Change(Trend.UP, 100), r.screenOffChange)
        // Two real charges in the week; the one-minute plug and next week's do not count.
        assertEquals(2, r.chargeSessions)
        assertEquals(Change(Trend.UP, 100), r.chargeChange)
        assertEquals(WeeklyReport.Headline.Kind.BATTERY, r.headline!!.kind)
        assertEquals("chrome", r.headline!!.entry.pkg)
    }

    @Test fun missingWeekBeforeShowsNoChanges() {
        val last = week(
            energy = mapOf("chrome" to (1400.0 to 3600.0)),
            screen = mapOf("chrome" to 7200L), data = mapOf("chrome" to 1000L),
        )
        val r = WeeklyReport.build(weeks, last, week(days = 0), 4000.0, 3.85, null)
        assertTrue(r.enough)
        assertEquals(Trend.NONE, r.energy[0].change.trend)
        assertEquals(Trend.NONE, r.screenTime[0].change.trend)
        assertEquals(Trend.NONE, r.data[0].change.trend)
        assertEquals(Trend.NONE, r.screenOnChange.trend)
        assertEquals(Trend.NONE, r.chargeChange.trend)
    }

    @Test fun usageStatsThatForgotTheWeekBeforeDoNotMakeEverythingNew() {
        val last = week(energy = mapOf("chrome" to (1000.0 to 100.0)), screen = mapOf("chrome" to 100L), data = mapOf("chrome" to 5L))
        val before = week(energy = mapOf("chrome" to (980.0 to 100.0)))
        val r = WeeklyReport.build(weeks, last, before, 4000.0, 3.85, null)
        assertEquals(Trend.FLAT, r.energy[0].change.trend)
        assertEquals(Trend.NONE, r.screenTime[0].change.trend)
        assertEquals(Trend.NONE, r.data[0].change.trend)
    }

    @Test fun screenOffRateNeedsHoursAndCapacity() {
        val w = week(energy = mapOf(DrainTracker.SCREEN_OFF to (100.0 to 1800.0)))
        assertNull(WeeklyReport.screenOffRate(w, 4000.0, 3.85))
        val ok = week(energy = mapOf(DrainTracker.SCREEN_OFF to (308.0 to 7200.0)))
        assertEquals(1.0, WeeklyReport.screenOffRate(ok, 4000.0, 3.85)!!, 1e-9)
        assertNull(WeeklyReport.screenOffRate(ok, null, 3.85))
        assertNull(WeeklyReport.screenOffRate(week(), 4000.0, 3.85))
    }

    @Test fun headlineFallsBackToScreenTime() {
        val last = week(screen = mapOf("chrome" to 7200L, "maps" to 60L), days = 5)
        val r = WeeklyReport.build(weeks, last, week(days = 0), null, null, null)
        assertEquals(WeeklyReport.Headline.Kind.SCREEN, r.headline!!.kind)
        assertEquals("chrome", r.headline!!.entry.pkg)
        assertNull(r.screenOffPercentPerHour)
    }

    @Test fun syntheticRowsNeverMakeTheTopLists() {
        val last = week(energy = mapOf(DrainTracker.SCREEN_ON_OTHER to (5000.0 to 1.0), SYSTEM_ROW to (10.0 to 1.0)), data = mapOf(SYSTEM_ROW to 99L))
        val r = WeeklyReport.build(weeks, last, week(days = 0), 4000.0, 3.85, null)
        assertTrue(r.energy.isEmpty())
        assertTrue(r.data.isEmpty())
        assertNull(r.headline)
    }
}
