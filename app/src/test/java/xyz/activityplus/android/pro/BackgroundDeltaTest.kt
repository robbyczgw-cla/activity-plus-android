package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.pro.BackgroundDelta.Snapshot
import xyz.activityplus.android.pro.BackgroundSchedule.Trigger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BackgroundDeltaTest {
    private val h = 3_600_000L
    private val pkgs = mapOf(10_100 to listOf("com.example.mail"), 10_200 to listOf("com.example.maps"), 1000 to listOf("android"))
    private fun u(mah: Double, cpu: Long = 0, wake: Long = 0) = ProParser.UidBattery(mah, cpu, wake)
    private fun snap(t: Long, onBattery: Long?, start: Long?, vararg uids: Pair<Int, ProParser.UidBattery>, hw: Double = 0.0) =
        Snapshot(t, onBattery, start, uids.toMap(), hw)

    @Test fun firstReadingHasNoInterval() {
        assertNull(BackgroundDelta.interval(null, snap(10 * h, h, 1L, 10_100 to u(5.0)), pkgs, false))
    }

    @Test fun normalGrowthIsTheDifference() {
        val prev = snap(10 * h, 2 * h, 1L, 10_100 to u(10.0, 1000, 60_000), 10_200 to u(4.0), hw = 30.0)
        val next = snap(13 * h, 5 * h, 1L, 10_100 to u(25.0, 4000, 660_000), 10_200 to u(4.0), hw = 50.0)
        val i = BackgroundDelta.interval(prev, next, pkgs, chargedBetween = false)!!
        assertFalse(i.reset)
        assertEquals(10 * h, i.fromMillis)
        assertEquals(13 * h, i.toMillis)
        val mail = i.apps.single { it.pkg == "com.example.mail" }
        assertEquals(15.0, mail.mah, 1e-9)
        assertEquals(3000L, mail.cpuMs)
        assertEquals(600_000L, mail.wakelockMs)
        // Nothing new for maps: no row at all.
        assertTrue(i.apps.none { it.pkg == "com.example.maps" })
        assertEquals(20.0, i.hardwareMah, 1e-9)
        assertEquals(35.0, i.totalMah, 1e-9)
    }

    @Test fun negativeDifferencesClampToZero() {
        // Android's power model sometimes revises a uid slightly downwards.
        val prev = snap(10 * h, 2 * h, 1L, 10_100 to u(10.0, 5000, 100), 10_200 to u(3.0))
        val next = snap(13 * h, 5 * h, 1L, 10_100 to u(9.5, 4000, 50), 10_200 to u(5.0))
        val i = BackgroundDelta.interval(prev, next, pkgs, false)!!
        assertFalse(i.reset)
        assertEquals(listOf("com.example.maps"), i.apps.map { it.pkg })
        assertEquals(2.0, i.apps[0].mah, 1e-9)
    }

    @Test fun resetByShorterTimeOnBatteryTakesTheNewValues() {
        val prev = snap(10 * h, 9 * h, null, 10_100 to u(80.0, 9000, 9000), hw = 300.0)
        val next = snap(14 * h, 1 * h, null, 10_100 to u(6.0, 100, 200), hw = 12.0)
        val i = BackgroundDelta.interval(prev, next, pkgs, false)!!
        assertTrue(i.reset)
        assertEquals(6.0, i.apps.single().mah, 1e-9)
        assertEquals(200L, i.apps.single().wakelockMs)
        assertEquals(12.0, i.hardwareMah, 1e-9)
        assertEquals(10 * h, i.fromMillis)
    }

    @Test fun resetByNewStartClockStartsTheIntervalThere() {
        // Plugged in at 10 h, unplugged and reset at 12 h; by 15 h more time on battery than before.
        val prev = snap(10 * h, 2 * h, 1L, 10_100 to u(10.0))
        val next = snap(15 * h, 3 * h, 12 * h, 10_100 to u(7.0))
        val i = BackgroundDelta.interval(prev, next, pkgs, chargedBetween = false)!!
        assertTrue(i.reset)
        assertEquals(12 * h, i.fromMillis)
        assertEquals(7.0, i.apps.single().mah, 1e-9)
    }

    @Test fun chargeWithoutResetKeepsSubtractingWhenTheStartClockIsKnown() {
        val prev = snap(10 * h, 2 * h, 1L, 10_100 to u(10.0))
        val next = snap(15 * h, 4 * h, 1L, 10_100 to u(14.0))
        val i = BackgroundDelta.interval(prev, next, pkgs, chargedBetween = true)!!
        assertFalse(i.reset)
        assertEquals(4.0, i.apps.single().mah, 1e-9)
    }

    @Test fun chargeCountsAsResetWithoutStartClock() {
        val prev = snap(10 * h, 2 * h, null, 10_100 to u(10.0))
        val next = snap(15 * h, 4 * h, null, 10_100 to u(14.0))
        assertTrue(BackgroundDelta.interval(prev, next, pkgs, chargedBetween = true)!!.reset)
        assertFalse(BackgroundDelta.interval(prev, next, pkgs, chargedBetween = false)!!.reset)
    }

    @Test fun clearlySmallerTotalsMeanReset() {
        val prev = snap(10 * h, null, null, 10_100 to u(100.0))
        val next = snap(15 * h, null, null, 10_100 to u(20.0))
        assertTrue(BackgroundDelta.interval(prev, next, pkgs, false)!!.reset)
    }

    @Test fun newAndVanishedUids() {
        val prev = snap(10 * h, 2 * h, 1L, 10_100 to u(10.0), 10_300 to u(50.0))
        // 10_300 was uninstalled; 10_200 is new and counts from zero.
        val next = snap(13 * h, 5 * h, 1L, 10_100 to u(12.0), 10_200 to u(3.0, 0, 120_000))
        val i = BackgroundDelta.interval(prev, next, pkgs, false)!!
        assertFalse(i.reset)
        assertEquals(mapOf("com.example.mail" to 2.0, "com.example.maps" to 3.0), i.apps.associate { it.pkg to it.mah })
        assertEquals(120_000L, i.apps.single { it.pkg == "com.example.maps" }.wakelockMs)
    }

    @Test fun sharedAndSystemUidsFoldIntoSystem() {
        val shared = pkgs + (10_400 to listOf("com.a", "com.b"))
        val prev = snap(10 * h, 2 * h, 1L)
        val next = snap(13 * h, 5 * h, 1L, 1000 to u(4.0), 10_400 to u(1.0), 10_999 to u(0.5), 10_100 to u(2.0))
        val i = BackgroundDelta.interval(prev, next, shared, false)!!
        assertEquals(5.5, i.apps.single { it.pkg == ProReport.SYSTEM }.mah, 1e-9)
        assertEquals(2.0, i.apps.single { it.pkg == "com.example.mail" }.mah, 1e-9)
        assertEquals(ProReport.SYSTEM, i.apps.first().pkg) // sorted by mAh
    }

    @Test fun startClockFromCheckin() {
        val text = javaClass.classLoader!!.getResource("dumpsys/batterystats-checkin.txt")!!.readText()
        assertEquals(1_791_528_581_269L, BackgroundDelta.startClock(text))
        assertNull(BackgroundDelta.startClock("9,0,l,bt,3,7200000,3600000,7200000,3600000,0,0,0"))
        assertNull(BackgroundDelta.startClock(""))
    }

    @Test fun snapshotFromReport() {
        val r = ProParser.BatteryReport(mapOf(10_100 to u(1.0)), pkgs, 5000L, mapOf("scrn" to 2.0, "cell" to 1.5))
        val s = BackgroundDelta.snapshot(7L, r, 3L)
        assertEquals(Snapshot(7L, 5000L, 3L, mapOf(10_100 to u(1.0)), 3.5), s)
    }

    @Test fun encodeRoundTrip() {
        val m = mapOf(10_100 to u(12.345678, 1234, 5678), 1000 to u(0.0001, 0, 0))
        assertEquals(m, BackgroundDelta.decode(BackgroundDelta.encode(m)))
        assertEquals(emptyMap<Int, ProParser.UidBattery>(), BackgroundDelta.decode(""))
        assertEquals(1, BackgroundDelta.decode("1:2.0:3:4;broken;5:x:1:1").size)
    }

    @Test fun splitByDayOverMidnight() {
        val zone = ZoneId.of("Europe/Vienna")
        val from = ZonedDateTime.of(2026, 10, 8, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(2026, 10, 9, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val parts = BackgroundDelta.splitByDay(from, to, zone)
        assertEquals(listOf("2026-10-08", "2026-10-09"), parts.map { it.first })
        assertEquals(1.0 / 8, parts[0].second, 1e-9)
        assertEquals(7.0 / 8, parts[1].second, 1e-9)
        val same = BackgroundDelta.splitByDay(to, to + h, zone)
        assertEquals(listOf("2026-10-09" to 1.0), same)
        assertEquals(listOf("2026-10-09" to 1.0), BackgroundDelta.splitByDay(to, to, zone))
        // Across two midnights the shares still add up to one.
        assertEquals(1.0, BackgroundDelta.splitByDay(from - 30 * h, to, zone).sumOf { it.second }, 1e-9)
    }

    @Test fun levelLostSkipsCharging() {
        val points = listOf(0.80 to false, 0.78 to false, 0.75 to false, 0.76 to true, 0.90 to true, 0.90 to false, 0.88 to false)
        assertEquals(0.07, BackgroundDelta.levelLost(points), 1e-9)
        assertEquals(0.0, BackgroundDelta.levelLost(emptyList()), 0.0)
    }

    @Test fun unusualNeedsHistoryAndSize() {
        assertTrue(BackgroundDelta.unusual(30.0, listOf(10.0, 8.0, 12.0)))
        assertFalse(BackgroundDelta.unusual(24.0, listOf(10.0, 10.0, 10.0))) // only 2.4×
        assertFalse(BackgroundDelta.unusual(15.0, listOf(1.0, 1.0, 1.0))) // under 20 mAh
        assertFalse(BackgroundDelta.unusual(100.0, listOf(1.0, 1.0))) // too few days
    }
}

class BackgroundScheduleTest {
    private val h = 3_600_000L

    @Test fun periodicEveryThreeHoursOnBatteryOnly() {
        assertTrue(BackgroundSchedule.due(Trigger.PERIODIC, 10 * h, null, plugged = false))
        assertFalse(BackgroundSchedule.due(Trigger.PERIODIC, 10 * h, 8 * h, plugged = false))
        assertTrue(BackgroundSchedule.due(Trigger.PERIODIC, 11 * h, 8 * h, plugged = false))
        assertFalse(BackgroundSchedule.due(Trigger.PERIODIC, 11 * h, 8 * h, plugged = true))
    }

    @Test fun plugAndMorningBypassThePeriodButNotTheMinimumGap() {
        assertTrue(BackgroundSchedule.due(Trigger.PLUG, 10 * h, 10 * h - 10 * 60_000, plugged = true))
        assertTrue(BackgroundSchedule.due(Trigger.MORNING, 10 * h, 10 * h - 10 * 60_000, plugged = false))
        assertFalse(BackgroundSchedule.due(Trigger.PLUG, 10 * h, 10 * h - 60_000, plugged = true))
        assertFalse(BackgroundSchedule.due(Trigger.MORNING, 10 * h, 10 * h - 60_000, plugged = false))
    }

    @Test fun morningIsTheFirstScreenOnAfterFive() {
        val zone = ZoneId.of("Europe/Vienna")
        fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(BackgroundSchedule.isMorning(at(4, 59), "2026-10-08", zone))
        assertTrue(BackgroundSchedule.isMorning(at(5, 0), "2026-10-08", zone))
        assertTrue(BackgroundSchedule.isMorning(at(7, 30), null, zone))
        assertFalse(BackgroundSchedule.isMorning(at(7, 30), "2026-10-09", zone))
    }

    @Test fun lastDaysOldestFirst() {
        assertEquals(
            listOf("2026-09-29", "2026-09-30", "2026-10-01"),
            BackgroundSchedule.lastDays(LocalDate.of(2026, 10, 1), 3),
        )
    }
}
