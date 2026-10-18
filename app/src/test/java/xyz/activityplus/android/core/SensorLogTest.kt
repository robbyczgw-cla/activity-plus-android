package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.SensorLog.Access
import xyz.activityplus.android.pro.AppOpsParser
import xyz.activityplus.android.pro.AppOpsParser.Op
import xyz.activityplus.android.pro.AppOpsParser.Sensor
import xyz.activityplus.android.pro.AppOpsParser.Where
import java.time.Instant
import java.time.ZoneOffset

class SensorLogTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
    private val min = 60_000L

    private fun ev(
        op: Op, ago: Long, where: Where = Where.BACKGROUND, flags: String = "s", duration: Long? = null,
        running: Boolean = false, rejected: Boolean = false,
    ) = AppOpsParser.Event(op, now - ago, where, flags, duration, running, rejected)

    private fun acc(pkg: String, e: AppOpsParser.Event) = Access(pkg, e)

    @Test fun closeAccessesAreOneUse() {
        val events = listOf(
            ev(Op.MONITOR_LOCATION, 100 * min), ev(Op.FINE_LOCATION, 100 * min - 300), // a request and its first fix
            ev(Op.FINE_LOCATION, 40 * min), ev(Op.FINE_LOCATION, 10 * min),
        )
        val clusters = SensorLog.clusters(events)
        assertEquals(listOf(2, 1, 1), clusters.map { it.size })
        // Exactly the gap counts as a new use.
        assertEquals(2, SensorLog.clusters(listOf(ev(Op.FINE_LOCATION, 20_000), ev(Op.FINE_LOCATION, 10_000))).size)
        assertEquals(1, SensorLog.clusters(listOf(ev(Op.FINE_LOCATION, 20_000), ev(Op.FINE_LOCATION, 10_001))).size)
        assertTrue(SensorLog.clusters(emptyList()).isEmpty())
    }

    @Test fun backgroundUsesAreCountedPerAppAndSensor() {
        val list = listOf(
            // Location: 3 uses, 2 of them in the background, one on screen.
            acc("maps", ev(Op.FINE_LOCATION, 300 * min, Where.BACKGROUND)),
            acc("maps", ev(Op.FINE_LOCATION, 200 * min, Where.BACKGROUND)),
            acc("maps", ev(Op.FINE_LOCATION, 100 * min, Where.SCREEN)),
            // Camera once, on screen.
            acc("maps", ev(Op.CAMERA, 50 * min, Where.SCREEN, duration = 90_000)),
            acc("chat", ev(Op.RECORD_AUDIO, 30 * min, Where.SERVICE, duration = 5 * min)),
        )
        val apps = SensorLog.summarize(list, now - 24 * 60 * min, now)
        assertEquals(listOf("maps", "chat"), apps.map { it.pkg }) // most background use first
        val loc = apps[0].sensors.getValue(Sensor.LOCATION)
        assertEquals(3, loc.uses)
        assertEquals(2, loc.background)
        assertEquals(0, loc.service)
        assertEquals(now - 100 * min, loc.lastMillis)
        assertEquals(Where.SCREEN, loc.lastWhere)
        assertEquals(2, apps[0].background)
        val cam = apps[0].sensors.getValue(Sensor.CAMERA)
        assertEquals(90_000L, cam.activeMs)
        assertEquals(0, cam.background)
        val mic = apps[1].sensors.getValue(Sensor.MICROPHONE)
        assertEquals(1, mic.service)
        assertEquals(5 * min, mic.activeMs)
    }

    @Test fun oneUseCanMixBackgroundAndScreenAccesses() {
        // A request that began on screen and a fix a second later from the background: counted once, as background.
        val list = listOf(
            acc("a", ev(Op.MONITOR_LOCATION, 60 * min, Where.SCREEN)),
            acc("a", ev(Op.FINE_LOCATION, 60 * min - 1_000, Where.BACKGROUND)),
        )
        val loc = SensorLog.summarize(list, 0, now).single().sensors.getValue(Sensor.LOCATION)
        assertEquals(1, loc.uses)
        assertEquals(1, loc.background)
    }

    @Test fun blockedAttemptsAreSeparateFromUses() {
        val list = listOf(
            acc("a", ev(Op.FINE_LOCATION, 90 * min, rejected = true)),
            acc("a", ev(Op.FINE_LOCATION, 30 * min, rejected = true)),
            acc("a", ev(Op.FINE_LOCATION, 10 * min, Where.SCREEN)),
            acc("b", ev(Op.CAMERA, 20 * min, Where.BACKGROUND, rejected = true)),
        )
        val apps = SensorLog.summarize(list, 0, now).associateBy { it.pkg }
        val a = apps.getValue("a").sensors.getValue(Sensor.LOCATION)
        assertEquals(1, a.uses)
        assertEquals(2, a.blocked)
        assertEquals(0, a.background)
        // Only blocked: the sensor still shows, with no use and no background use.
        val b = apps.getValue("b").sensors.getValue(Sensor.CAMERA)
        assertEquals(0, b.uses)
        assertEquals(1, b.blocked)
        assertEquals(now - 20 * min, b.lastMillis)
        assertEquals(0, apps.getValue("b").background)
    }

    @Test fun accessesMadeForAnotherAppAreNotCharged() {
        val list = listOf(
            // Play services acting as proxy for an app: the app has its own "tpd" line.
            acc("gms", ev(Op.FINE_LOCATION, 10 * min, flags = "tp")),
            acc("maps", ev(Op.FINE_LOCATION, 10 * min, flags = "tpd")),
        )
        assertEquals(listOf("maps"), SensorLog.summarize(list, 0, now).map { it.pkg })
    }

    @Test fun onlyTheSpanIsCounted() {
        val list = listOf(
            acc("a", ev(Op.FINE_LOCATION, 30 * 60 * min)),
            acc("a", ev(Op.FINE_LOCATION, 2 * 60 * min)),
        )
        val day = SensorLog.summarize(list, now - 24 * 60 * min, now).single().sensors.getValue(Sensor.LOCATION)
        assertEquals(1, day.uses)
        assertTrue(SensorLog.summarize(list, now + 1, now + 2).isEmpty())
    }

    @Test fun rightNowIsWhatRunsOrJustHappened() {
        val list = listOf(
            acc("cam", ev(Op.CAMERA, 5_000, Where.SCREEN)),
            acc("nav", ev(Op.MONITOR_LOCATION, 3 * 60 * min, Where.SERVICE, running = true)), // began hours ago, still going
            acc("nav", ev(Op.FINE_LOCATION, 2_000, Where.SERVICE)),
            acc("old", ev(Op.RECORD_AUDIO, 61_000)), // a minute ago and a second: over
            acc("blocked", ev(Op.CAMERA, 1_000, rejected = true)),
            acc("proxy", ev(Op.FINE_LOCATION, 1_000, flags = "up")),
            acc("future", ev(Op.CAMERA, -5_000)), // a clock that jumped: not "now"
            acc("mic", ev(Op.RECORD_AUDIO, 60_000, Where.BACKGROUND)),
        )
        val live = SensorLog.rightNow(list, now)
        // Camera before microphone before location; one row per app and sensor.
        assertEquals(listOf("cam" to Sensor.CAMERA, "mic" to Sensor.MICROPHONE, "nav" to Sensor.LOCATION), live.map { it.pkg to it.sensor })
        assertEquals(Where.BACKGROUND, live[1].where)
        assertEquals(now - 3 * 60 * min, live[2].sinceMillis)
    }

    @Test fun manyBackgroundUsesNeedTheThreshold() {
        fun loc(pkg: String, uses: Int) = SensorLog.AppUse(
            pkg, mapOf(Sensor.LOCATION to SensorLog.SensorUse(uses, uses, 0, now, Where.BACKGROUND, 0, 0)),
        )
        val apps = listOf(loc("few", SensorLog.MANY_BACKGROUND - 1), loc("many", SensorLog.MANY_BACKGROUND), loc("most", 40))
        assertEquals(listOf("most", "many"), SensorLog.manyInBackground(apps).map { it.pkg })
        assertEquals(40, SensorLog.manyInBackground(apps).first().uses)
        // The camera in the background is not a location finding.
        val cam = SensorLog.AppUse("cam", mapOf(Sensor.CAMERA to SensorLog.SensorUse(50, 50, 0, now, Where.BACKGROUND, 0, 0)))
        assertTrue(SensorLog.manyInBackground(listOf(cam)).isEmpty())
        assertEquals(listOf("cam"), SensorLog.backgroundUses(listOf(cam), Sensor.CAMERA).map { it.pkg })
    }

    @Test fun backgroundUsesLeaveOutAppsWithoutAny() {
        val screenOnly = SensorLog.AppUse("a", mapOf(Sensor.LOCATION to SensorLog.SensorUse(5, 0, 0, now, Where.SCREEN, 0, 0)))
        assertTrue(SensorLog.backgroundUses(listOf(screenOnly), Sensor.LOCATION).isEmpty())
    }

    @Test fun nightIsMidnightToSix() {
        val afternoon = Instant.parse("2026-10-18T15:00:00Z").toEpochMilli()
        val w = SensorLog.nightWindow(afternoon, ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-18T00:00:00Z").toEpochMilli(), w.first)
        assertEquals(Instant.parse("2026-10-18T06:00:00Z").toEpochMilli(), w.last)
        // At 03:00 the night is not over: the one before is the last whole night.
        val small = SensorLog.nightWindow(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(), small.first)
        // In a zone: 06:00 local is the end.
        val berlin = SensorLog.nightWindow(Instant.parse("2026-10-18T04:30:00Z").toEpochMilli(), ZoneOffset.ofHours(2))
        assertEquals(Instant.parse("2026-10-17T22:00:00Z").toEpochMilli(), berlin.first)
        assertEquals(Instant.parse("2026-10-18T04:00:00Z").toEpochMilli(), berlin.last)
        assertFalse(afternoon in w)
    }

    @Test fun reportAccessesCarryTheirPackage() {
        val report = AppOpsParser.Report(
            mapOf(
                "a" to AppOpsParser.PackageOps(
                    "a", mapOf(Op.CAMERA to AppOpsParser.OpState(Op.CAMERA, null, listOf(ev(Op.CAMERA, min)))),
                ),
            ),
        )
        assertEquals(listOf("a"), SensorLog.accesses(report).map { it.pkg })
    }
}
