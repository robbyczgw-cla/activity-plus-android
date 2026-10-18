package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Schedule.Reason
import xyz.activityplus.android.pro.AlarmParser
import xyz.activityplus.android.pro.AlarmParser.Type
import xyz.activityplus.android.pro.JobParser
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScheduleTest {
    private val utc = ZoneOffset.UTC
    private val min = 60_000L
    private val hour = 3_600_000L

    private fun at(h: Int, m: Int = 0, day: Int = 18) = LocalDateTime.of(2026, 10, day, h, m).toInstant(utc).toEpochMilli()

    private val evening = at(22)

    private fun alarm(
        pkg: String, tag: String, whenMillis: Long?, type: Type = Type.RTC_WAKEUP, repeat: Long = 0, window: Long? = 0,
        flags: Int = AlarmParser.FLAG_ALLOW_WHILE_IDLE or 0x1,
    ) = AlarmParser.Alarm(pkg, type, whenMillis, repeat, window, flags, tag)

    private fun report(
        alarms: List<AlarmParser.Alarm>, stats: Map<String, AlarmParser.PackageStats> = emptyMap(),
        sinceBoot: Long? = 10 * hour, now: Long? = evening,
    ) = AlarmParser.Report(now, sinceBoot, alarms, stats)

    private fun stats(pkg: String, tag: String, count: Long) =
        mapOf(pkg to AlarmParser.PackageStats(pkg, 0, count, count, listOf(AlarmParser.TagStats(tag, 0, count, count))))

    private fun job(
        pkg: String, periodic: Long? = null, charging: Boolean = false, earliest: Long? = null, id: String = "1",
    ) = JobParser.Job("u0a1", id, pkg, "$pkg.SyncService", periodic, null, charging, false, false, true, false, earliest, null, null)

    @Test fun exactAlarmInDozeEveryQuarterHourIsCostly() {
        // One-time exact alarm set again each time: 40 deliveries in 10 hours since start = every 15 min.
        val chat = alarm("com.chat", "KEEPALIVE", at(22, 10))
        val plan = Schedule.plan(report(listOf(chat), stats("com.chat", "KEEPALIVE", 40)), null, 0, utc)
        val a = plan.apps.single()
        val p = a.alarms.single()
        assertEquals(15 * min, p.intervalMs)
        assertTrue(p.intervalMeasured)
        assertTrue(p.costly)
        // 23:10, 23:25 ... 06:55 within 23:00–07:00.
        assertEquals(32, p.tonight)
        assertEquals(32, a.wakeupsTonight)
        assertEquals(at(22, 10), a.nextWakeupMillis)
        assertEquals(setOf(Reason.EXACT_IN_DOZE, Reason.NIGHT_WAKEUPS), a.reasons)
        assertEquals(p, a.headline)
        assertEquals(listOf(Schedule.Costly("com.chat", 15 * min, 32)), Schedule.costly(plan))
    }

    @Test fun freshStartDoesNotMakeAStorm() {
        // Four deliveries in the first hour after a reboot say nothing about the night.
        val chat = alarm("com.chat", "KEEPALIVE", at(22, 10))
        val plan = Schedule.plan(report(listOf(chat), stats("com.chat", "KEEPALIVE", 4), sinceBoot = hour), null, 0, utc)
        val p = plan.apps.single().alarms.single()
        assertNull(p.intervalMs)
        assertFalse(p.costly)
        assertEquals(0, p.tonight)
        assertEquals(1, p.ahead)
        assertTrue(Schedule.costly(plan).isEmpty())
    }

    @Test fun inexactRepeatingAlarmIsNotCostlyAndWeighsLess() {
        val news = alarm("com.news", "REFRESH", at(22, 30), repeat = 30 * min, window = 22 * min, flags = 0)
        val chat = alarm("com.chat", "PING", at(23, 0), repeat = 30 * min)
        val plan = Schedule.plan(report(listOf(news, chat)), null, 0, utc)
        val n = plan.apps.single { it.pkg == "com.news" }
        assertFalse(n.alarms.single().costly)
        assertEquals(16, n.wakeupsTonight)
        assertEquals(setOf(Reason.NIGHT_WAKEUPS), n.reasons)
        // Same count, but only the one allowed in Doze comes on time: it ranks first.
        assertEquals(listOf("com.chat", "com.news"), plan.apps.map { it.pkg })
        assertEquals(16.0, plan.apps[0].score, 1e-9)
        assertEquals(16 * Schedule.DEFERRED_WEIGHT, n.score, 1e-9)
    }

    @Test fun repeatsBelowAMinuteCountAsAMinute() {
        val p = Schedule.plan(report(listOf(alarm("com.x", "T", at(23, 0), repeat = 1000))), null, 0, utc).apps.single().alarms.single()
        assertEquals(Schedule.MIN_REPEAT_MS, p.intervalMs)
        assertEquals(8 * 60, p.tonight)
    }

    @Test fun alarmClockIsNeverTheProblem() {
        val clock = alarm("com.clock", "ALARM", at(6, 30, day = 19), flags = AlarmParser.FLAG_WAKE_FROM_IDLE or 0x1)
        val plan = Schedule.plan(report(listOf(clock)), null, 0, utc)
        val a = plan.apps.single()
        assertTrue(a.alarms.single().alarmClock)
        assertEquals(0, a.wakeupsTonight)
        assertNull(a.nextWakeupMillis)
        assertNull(a.headline)
        assertEquals(0.0, a.score, 0.0)
        assertTrue(Schedule.noisiest(plan, 5).isEmpty())
    }

    @Test fun nonWakeupUnknownTimeAndIgnoredPackagesAreLeftOut() {
        val alarms = listOf(
            alarm("com.quiet", "TICK", at(23, 30), type = Type.RTC),
            alarm("com.parked", "LATER", null),
            alarm("android", "SYSTEM", at(23, 30)),
        )
        assertTrue(Schedule.plan(report(alarms), null, 0, utc, ignore = setOf("android")).apps.isEmpty())
    }

    @Test fun sameTagIsOnePatternAndOverdueIsNow() {
        val alarms = listOf(
            alarm("com.cal", "CHECK", at(21, 50)),
            alarm("com.cal", "CHECK", at(23, 30)),
            alarm("com.cal", "OTHER", at(2, 0, day = 19)),
        )
        val a = Schedule.plan(report(alarms), null, 0, utc).apps.single()
        assertEquals(2, a.alarms.size)
        val check = a.alarms.first()
        assertEquals(evening, check.nextMillis)
        assertNull(check.intervalMs)
        // One-time alarms: each counts where it falls.
        assertEquals(1, check.tonight)
        assertEquals(2, check.ahead)
        assertEquals(2, a.wakeupsTonight)
        assertEquals(3, a.wakeupsAhead)
    }

    @Test fun reportClockWinsOverTheCallersClock() {
        val plan = Schedule.plan(report(emptyList(), now = evening), null, at(12), utc)
        assertEquals(evening, plan.nowMillis)
        assertEquals(at(23) until at(7, day = 19), plan.night)
        assertEquals(at(12), Schedule.plan(report(emptyList(), now = null), null, at(12), utc).nowMillis)
    }

    @Test fun jobsOnBatteryAndOften() {
        val jobs = JobParser.Report(
            listOf(
                job("com.sync", periodic = 15 * min, id = "1"),
                job("com.sync", periodic = 15 * min, charging = true, id = "2"),
                job("com.backup", periodic = 24 * hour),
                job("com.mail", earliest = 2 * hour),
            ),
            mapOf("com.sync" to 12), 3 * hour,
        )
        val plan = Schedule.plan(report(emptyList()), jobs, 0, utc)
        val sync = plan.apps.single { it.pkg == "com.sync" }
        assertEquals(setOf(Reason.FREQUENT_JOBS), sync.reasons)
        assertEquals(listOf(32, 0), sync.jobs.map { it.tonight })
        assertEquals(listOf(true, false), sync.jobs.map { it.frequent })
        assertEquals(32 * Schedule.DEFERRED_WEIGHT, sync.score, 1e-9)
        assertEquals(12, sync.jobRuns)
        assertEquals(3 * hour, plan.runsSpanMs)
        // Once a day does not fit into one night as a whole period.
        assertEquals(0, plan.apps.single { it.pkg == "com.backup" }.jobs.single().tonight)
        // 22:00 + 2 h = midnight: inside the night.
        assertEquals(1, plan.apps.single { it.pkg == "com.mail" }.jobs.single().tonight)
        assertEquals("com.sync", Schedule.noisiest(plan, 1).single().pkg)
    }

    @Test fun planFromTheEmulatorReports() {
        fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()
        val alarms = AlarmParser.parse(fixture("alarm.txt"))
        val jobs = JobParser.parse(fixture("jobscheduler.txt"), utc)
        val plan = Schedule.plan(alarms, jobs, 0, utc, ignore = setOf("android"))
        assertTrue(plan.apps.none { it.pkg == "android" })
        val gms = plan.apps.single { it.pkg == "com.google.android.gms" }
        assertEquals(alarms.nowMillis!! + 15_501, gms.nextWakeupMillis)
        assertEquals(2L, gms.pastWakeups)
        // Two minutes after boot: nothing to read an interval from, so nothing costly yet.
        assertTrue(Schedule.costly(plan).isEmpty())
        assertTrue(plan.apps.any { it.pkg == "com.google.android.apps.messaging" && it.jobs.isNotEmpty() && it.jobRuns == 7 })
    }

    @Test fun comingNight() {
        assertEquals(at(23) until at(7, day = 19), Schedule.comingNight(at(21, 49), utc))
        assertEquals(at(23) until at(7, day = 19), Schedule.comingNight(at(7), utc))
        assertEquals(at(23, 30) until at(7, day = 19), Schedule.comingNight(at(23, 30), utc))
        assertEquals(at(2, day = 19) until at(7, day = 19), Schedule.comingNight(at(2, day = 19), utc))
    }

    @Test fun occurrences() {
        assertEquals(10, Schedule.occurrences(0, 10, 0, 100))
        assertEquals(3, Schedule.occurrences(0, 10, 15, 45)) // 20, 30, 40
        assertEquals(1, Schedule.occurrences(50, 100, 0, 100))
        assertEquals(0, Schedule.occurrences(100, 10, 0, 100))
        assertEquals(0, Schedule.occurrences(0, 1000, 15, 45))
        assertEquals(1, Schedule.occurrences(20, null, 15, 45))
        assertEquals(0, Schedule.occurrences(50, null, 15, 45))
        assertEquals(0, Schedule.occurrences(0, 10, 50, 50))
    }

    @Test fun shortNames() {
        assertEquals("CHECK_NOTIFICATIONS", Schedule.shortName("com.google.android.calendar.intent.action.CHECK_NOTIFICATIONS"))
        assertEquals("PlayLogReportingService", Schedule.shortName("com.google.android.gms/.analytics.internal.PlayLogReportingService"))
        assertEquals("SystemJobService", Schedule.shortName("androidx.work.impl.background.systemjob.SystemJobService"))
        assertEquals("wlan0.RENEW", Schedule.shortName("DhcpClient.wlan0.RENEW"))
        assertEquals("WifiHealthMonitor Schedule Daily Detection Timer", Schedule.shortName("WifiHealthMonitor Schedule Daily Detection Timer"))
        assertEquals("TICK", Schedule.shortName("TICK"))
        assertEquals("", Schedule.shortName(""))
    }
}
