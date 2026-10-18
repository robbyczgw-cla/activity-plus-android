package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.WakeupSchedule.Trigger
import xyz.activityplus.android.core.Wakeups.AppCounters
import xyz.activityplus.android.core.Wakeups.Counters
import xyz.activityplus.android.core.Wakeups.Interval
import xyz.activityplus.android.core.Wakeups.Snapshot
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeupsTest {
    private val min = 60_000L
    private val h = 3_600_000L
    private val zone = ZoneId.of("Europe/Vienna")
    private val pkgs = mapOf(10_100 to "com.example.mail", 10_200 to "com.example.maps", 10_300 to "com.example.mail")
    private fun at(y: Int, mo: Int, d: Int, hh: Int, mm: Int = 0) = ZonedDateTime.of(y, mo, d, hh, mm, 0, 0, zone).toInstant().toEpochMilli()
    private fun snap(t: Long, battery: Long?, vararg uids: Pair<Int, Counters>) = Snapshot(t, battery, uids.toMap())

    @Test fun growthSubtractsAndTakesResetCountersAsTheyAre() {
        assertEquals(Counters(cpuMs = 5), Wakeups.growth(null, Counters(cpuMs = 5)))
        val prev = Counters(cpuMs = 1000, wakelockMs = 500, wakeups = 10)
        val next = Counters(cpuMs = 3000, wakelockMs = 200, wakeups = 12)
        // The wakelock counter went down: Android reset it, so 200 ms is what happened since.
        assertEquals(Counters(cpuMs = 2000, wakelockMs = 200, wakeups = 2), Wakeups.growth(prev, next))
    }

    @Test fun lessTimeOnBatteryIsAReset() {
        val prev = snap(10 * h, 5 * h, 10_100 to Counters(cpuMs = 10_000))
        assertTrue(Wakeups.isReset(prev, snap(11 * h, 10 * min, 10_100 to Counters(cpuMs = 20_000))))
        assertFalse(Wakeups.isReset(prev, snap(11 * h, 6 * h, 10_100 to Counters(cpuMs = 20_000))))
    }

    @Test fun clearlyLessCpuOnCommonUidsIsAResetWithoutBatteryTime() {
        val prev = snap(10 * h, null, 10_100 to Counters(cpuMs = 100_000), 10_200 to Counters(cpuMs = 50_000))
        assertTrue(Wakeups.isReset(prev, snap(11 * h, null, 10_100 to Counters(cpuMs = 2_000), 10_200 to Counters(cpuMs = 1_000))))
        // An uninstalled heavy app is not a reset: only uids in both readings count.
        assertFalse(Wakeups.isReset(prev, snap(11 * h, null, 10_200 to Counters(cpuMs = 60_000))))
    }

    @Test fun firstReadingHasNoInterval() {
        assertNull(Wakeups.interval(null, snap(h, h, 10_100 to Counters(wakeups = 3)), pkgs::get))
        val s = snap(h, h)
        assertNull(Wakeups.interval(s, s, pkgs::get))
    }

    @Test fun intervalSumsSharedUidsAndDropsUnknownOnes() {
        val prev = snap(10 * h, 2 * h, 10_100 to Counters(wakeups = 10, cpuMs = 1000), 10_300 to Counters(wakeups = 1), 1000 to Counters(cpuMs = 5))
        val next = snap(
            10 * h + 30 * min, 2 * h + 30 * min,
            10_100 to Counters(wakeups = 25, cpuMs = 4000), 10_300 to Counters(wakeups = 3), 10_200 to Counters(cpuMs = 1000),
            1000 to Counters(cpuMs = 500_000),
        )
        val i = Wakeups.interval(prev, next, pkgs::get)!!
        assertFalse(i.reset)
        assertEquals(10 * h, i.fromMillis)
        val mail = i.apps.single { it.pkg == "com.example.mail" }.counters
        assertEquals(17L, mail.wakeups)
        assertEquals(3000L, mail.cpuMs)
        // A new uid counts from zero.
        assertEquals(1000L, i.apps.single { it.pkg == "com.example.maps" }.counters.cpuMs)
        // The system uid has no package: not shown.
        assertEquals(2, i.apps.size)
    }

    @Test fun nothingNewMeansNoRow() {
        val c = Counters(wakeups = 4)
        val i = Wakeups.interval(snap(h, h, 10_100 to c), snap(2 * h, 2 * h, 10_100 to c), pkgs::get)!!
        assertTrue(i.apps.isEmpty())
    }

    @Test fun afterAResetTheIntervalStartsAtTheResetAndTakesNewValues() {
        val prev = snap(10 * h, 9 * h, 10_100 to Counters(wakeups = 500, wakelockMs = 3 * h))
        val next = snap(14 * h, h, 10_100 to Counters(wakeups = 40, wakelockMs = 20 * min))
        val i = Wakeups.interval(prev, next, pkgs::get)!!
        assertTrue(i.reset)
        assertEquals(13 * h, i.fromMillis)
        assertEquals(Counters(wakeups = 40, wakelockMs = 20 * min), i.apps.single().counters)
    }

    @Test fun awakeTimeIsCappedAtTheInterval() {
        // Two wakelocks held at the same time add up to more than the time that passed.
        val prev = snap(10 * h, 5 * h, 10_100 to Counters(wakelockMs = 0, gpsMs = 0))
        val next = snap(10 * h + 30 * min, 5 * h + 30 * min, 10_100 to Counters(wakelockMs = 50 * min, gpsMs = 40 * min, cpuMs = 45 * min))
        val c = Wakeups.interval(prev, next, pkgs::get)!!.apps.single().counters
        assertEquals(30 * min, c.wakelockMs)
        assertEquals(30 * min, c.gpsMs)
        // CPU can exceed it on several cores.
        assertEquals(45 * min, c.cpuMs)
    }

    @Test fun splitKeepsEveryCount() {
        val parts = Wakeups.split(Counters(wakeups = 7, cpuMs = 1001), listOf(1.0 / 3, 1.0 / 3, 1.0 / 3))
        assertEquals(7L, parts.sumOf { it.wakeups })
        assertEquals(1001L, parts.sumOf { it.cpuMs })
        assertTrue(parts.all { it.wakeups in 2L..3L })
        assertEquals(listOf(Counters(wakeups = 1)), Wakeups.split(Counters(wakeups = 1), listOf(1.0)))
    }

    @Test fun byDaySplitsAcrossMidnight() {
        val i = Interval(at(2026, 10, 17, 23, 30), at(2026, 10, 18, 0, 30), false, listOf(AppCounters("a", Counters(wakeups = 10))))
        val days = Wakeups.byDay(i, zone)
        assertEquals(5L, days.getValue("2026-10-17").single().counters.wakeups)
        assertEquals(5L, days.getValue("2026-10-18").single().counters.wakeups)
        // Nothing left for a day means no row there.
        val one = Interval(at(2026, 10, 17, 23, 59), at(2026, 10, 18, 3, 0), false, listOf(AppCounters("a", Counters(wakeups = 1))))
        assertEquals(setOf("2026-10-18"), Wakeups.byDay(one, zone).keys)
    }

    @Test fun nightIsMidnightToTodaysMorningReading() {
        val morning = at(2026, 10, 18, 6, 40)
        val w = Wakeups.nightWindow(at(2026, 10, 18, 9, 0), morning, zone)!!
        assertEquals(at(2026, 10, 18, 0, 0), w.first)
        assertEquals(morning, w.last)
        // Yesterday's morning, a reading before 05:00 or none: no night.
        assertNull(Wakeups.nightWindow(at(2026, 10, 19, 9, 0), morning, zone))
        assertNull(Wakeups.nightWindow(at(2026, 10, 18, 9, 0), at(2026, 10, 18, 3, 0), zone))
        assertNull(Wakeups.nightWindow(at(2026, 10, 18, 9, 0), null, zone))
        // First used in the evening: no night block that would repeat the whole day.
        assertNull(Wakeups.nightWindow(at(2026, 10, 18, 21, 30), at(2026, 10, 18, 21, 14), zone))
    }

    @Test fun windowTakesTheShareOfIntervalsReachingOutside() {
        val window = at(2026, 10, 18, 0, 0)..at(2026, 10, 18, 6, 0)
        val intervals = listOf(
            // Half inside.
            Interval(at(2026, 10, 17, 23, 0), at(2026, 10, 18, 1, 0), false, listOf(AppCounters("a", Counters(wakeups = 10)))),
            Interval(at(2026, 10, 18, 1, 0), at(2026, 10, 18, 6, 0), false, listOf(AppCounters("a", Counters(wakeups = 30)), AppCounters("b", Counters(wakelockMs = h)))),
            // Entirely after.
            Interval(at(2026, 10, 18, 6, 0), at(2026, 10, 18, 7, 0), false, listOf(AppCounters("a", Counters(wakeups = 99)))),
        )
        val rows = Wakeups.inWindow(intervals, window).associate { it.pkg to it.counters }
        assertEquals(35L, rows.getValue("a").wakeups)
        assertEquals(h, rows.getValue("b").wakelockMs)
    }

    @Test fun rankingPutsWakeupsAndAwakeTimeFirstAtNight() {
        val chatty = AppCounters("chatty", Counters(wakeups = 200))
        val busy = AppCounters("busy", Counters(cpuMs = 20 * min))
        val idle = AppCounters("idle", Counters(cpuMs = 10_000))
        assertEquals(listOf("chatty", "busy"), Wakeups.rank(listOf(busy, idle, chatty), night = true).map { it.pkg })
        // By day the same CPU weighs more against fewer wakeups.
        val few = AppCounters("few", Counters(wakeups = 60))
        assertEquals("few", Wakeups.rank(listOf(busy, few), night = true).first().pkg)
        assertEquals("busy", Wakeups.rank(listOf(busy, few), night = false).first().pkg)
    }

    @Test fun worthShowingNeedsSomethingReal() {
        assertTrue(Wakeups.worthShowing(Counters(wakeups = 1)))
        assertTrue(Wakeups.worthShowing(Counters(wakelockMs = 30_000)))
        assertTrue(Wakeups.worthShowing(Counters(cpuMs = 60_000)))
        assertFalse(Wakeups.worthShowing(Counters(cpuMs = 59_000, wakelockMs = 29_000, jobs = 3)))
    }

    @Test fun unusualNeedsHistoryAndAClearJump() {
        val usual = List(5) { Counters(wakeups = 40, wakelockMs = 2 * min) }
        assertTrue(Wakeups.unusual(Counters(wakeups = 100), usual))
        assertFalse(Wakeups.unusual(Counters(wakeups = 99), usual))
        assertTrue(Wakeups.unusual(Counters(wakelockMs = 10 * min), usual))
        // Small numbers are noise, however large the factor.
        assertFalse(Wakeups.unusual(Counters(wakeups = 40, wakelockMs = 9 * min), List(5) { Counters(wakeups = 1) }))
        assertFalse(Wakeups.unusual(Counters(wakeups = 1000), usual.take(2)))
    }

    @Test fun measuredValuesSkipUnmeasuredDays() {
        val series = mapOf("2026-10-15" to Counters(wakeups = 9))
        val values = Wakeups.measuredValues(series, listOf("2026-10-14", "2026-10-15", "2026-10-16"), setOf("2026-10-15", "2026-10-16"))
        assertEquals(listOf(Counters(wakeups = 9), Counters.ZERO), values)
        assertEquals(listOf(Counters.ZERO), Wakeups.measuredValues(null, listOf("2026-10-16"), setOf("2026-10-16")))
    }

    @Test fun averagePerField() {
        assertNull(Wakeups.average(emptyList()))
        assertEquals(Counters(wakeups = 15, cpuMs = 2), Wakeups.average(listOf(Counters(wakeups = 10, cpuMs = 1), Counters(wakeups = 20, cpuMs = 3))))
    }

    @Test fun nightLineOnlyForAStandout() {
        assertTrue(Wakeups.standsOut(Counters(wakeups = 100)))
        assertTrue(Wakeups.standsOut(Counters(wakelockMs = 15 * min)))
        assertFalse(Wakeups.standsOut(Counters(wakeups = 99, wakelockMs = 14 * min)))
        assertTrue(Wakeups.wakeupsDominate(Counters(wakeups = 212, wakelockMs = 5 * min)))
        assertFalse(Wakeups.wakeupsDominate(Counters(wakeups = 120, wakelockMs = h)))
        assertFalse(Wakeups.wakeupsDominate(Counters(wakeups = 50)))
    }

    @Test fun pickPackagePrefersLaunchableThenShortest() {
        assertEquals("com.b", Wakeups.pickPackage(listOf("com.a.helper", "com.b"), emptySet()))
        assertEquals("com.a.helper", Wakeups.pickPackage(listOf("com.a.helper", "com.b"), setOf("com.a.helper")))
        assertEquals("com.a", Wakeups.pickPackage(listOf("com.b", "com.a"), emptySet()))
        assertNull(Wakeups.pickPackage(emptyList(), emptySet()))
    }

    @Test fun encodeDecodeRoundTripLeavesOutZeros() {
        val byUid = mapOf(10_100 to Counters(1, 2, 3, 4, 5, 6, 7, 8), 10_200 to Counters.ZERO)
        val text = Wakeups.encode(byUid)
        assertEquals("10100:1:2:3:4:5:6:7:8", text)
        assertEquals(mapOf(10_100 to Counters(1, 2, 3, 4, 5, 6, 7, 8)), Wakeups.decode(text))
        assertEquals(emptyMap<Int, Counters>(), Wakeups.decode(""))
        // Broken or older entries are skipped, not fatal.
        assertEquals(setOf(1), Wakeups.decode("1:0:0:0:1:0:0:0:0;2:x:0:0:0:0:0:0:0;3:1:2").keys)
    }

    @Test fun scheduleReadsOnBatteryAndAtEvents() {
        val now = 100 * h
        assertTrue(WakeupSchedule.due(Trigger.PERIODIC, now, null, plugged = false))
        assertTrue(WakeupSchedule.due(Trigger.PERIODIC, now, now - 30 * min, plugged = false))
        assertFalse(WakeupSchedule.due(Trigger.PERIODIC, now, now - 29 * min, plugged = false))
        // Android does not count while charging.
        assertFalse(WakeupSchedule.due(Trigger.PERIODIC, now, now - 5 * h, plugged = true))
        assertTrue(WakeupSchedule.due(Trigger.PLUG, now, now - 2 * min, plugged = true))
        assertTrue(WakeupSchedule.due(Trigger.UNPLUG, now, now - 2 * min, plugged = false))
        // A loose cable: events within a minute are dropped.
        assertFalse(WakeupSchedule.due(Trigger.UNPLUG, now, now - 30_000, plugged = false))
        assertTrue(WakeupSchedule.due(Trigger.MORNING, now, now - 5 * min, plugged = false))
        assertTrue(WakeupSchedule.due(Trigger.SCREEN, now, now - 31 * min, plugged = true))
        assertFalse(WakeupSchedule.due(Trigger.SCREEN, now, now - 10 * min, plugged = false))
    }
}
