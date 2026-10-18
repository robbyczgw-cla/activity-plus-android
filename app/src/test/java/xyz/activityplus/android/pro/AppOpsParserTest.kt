package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.pro.AppOpsParser.Mode
import xyz.activityplus.android.pro.AppOpsParser.Op
import xyz.activityplus.android.pro.AppOpsParser.Where
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AppOpsParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The moment of the emulator report: 1m42s662ms after the Play services location request began. */
    private val dumpTime = at("2026-10-18T21:48:05.555Z") + 102_662

    @Test fun locationOpsOfPlayServicesFromTheEmulator() {
        val r = AppOpsParser.parse(fixture("appops-sensors.txt"), dumpTime, utc)
        val gms = r.packages["com.google.android.gms"]!!
        val fine = gms.ops.getValue(Op.FINE_LOCATION)
        assertEquals(Mode.ALLOW, fine.mode)
        // One event per attribution tag, all in the foreground and by the app itself.
        assertEquals(4, fine.events.size)
        val nlp = fine.events.single { it.timeMillis == at("2026-10-18T21:48:05.610Z") }
        assertEquals(Where.SCREEN, nlp.where)
        assertEquals("s", nlp.flags)
        assertFalse(nlp.running)
        assertFalse(nlp.rejected)
        assertNull(nlp.durationMs)
        // An op line without events is a mode only.
        assertEquals(Mode.ALLOW, gms.ops.getValue(Op.COARSE_LOCATION).mode)
        assertTrue(gms.ops.getValue(Op.COARSE_LOCATION).events.isEmpty())
    }

    @Test fun runningLocationRequestKeepsItsStartAndDuration() {
        val r = AppOpsParser.parse(fixture("appops-sensors.txt"), dumpTime, utc)
        val monitor = r.packages["com.google.android.gms"]!!.ops.getValue(Op.MONITOR_LOCATION).events
        assertEquals(4, monitor.size)
        // The "Running start" line belongs to the access above it, not to the older service access.
        val running = monitor.filter { it.running }
        assertEquals(2, running.size)
        assertTrue(running.all { it.where == Where.SCREEN })
        val first = running.single { it.timeMillis == at("2026-10-18T21:48:05.555Z") }
        assertEquals(102_681L, first.durationMs)
        val service = monitor.filter { it.where == Where.SERVICE }
        assertEquals(2, service.size)
        assertTrue(service.none { it.running })
        assertEquals(34L, service.first().durationMs)
    }

    @Test fun highPowerLocationAndNearbyAndBodyOpsAreRead() {
        val gms = AppOpsParser.parse(fixture("appops-sensors.txt"), dumpTime, utc).packages["com.google.android.gms"]!!
        assertEquals(3_739L, gms.ops.getValue(Op.MONITOR_HIGH_POWER_LOCATION).events.single().durationMs)
        assertEquals(1, gms.ops.getValue(Op.BLUETOOTH_SCAN).events.size)
        assertEquals(2, gms.ops.getValue(Op.ACTIVITY_RECOGNITION).events.size)
        assertEquals(AppOpsParser.Sensor.BODY, Op.ACTIVITY_RECOGNITION.sensor)
    }

    @Test fun uidModeWinsOverPackageMode() {
        val r = AppOpsParser.parse(fixture("appops-sensors.txt"), dumpTime, utc)
        val assistant = r.packages["com.google.android.as"]!!
        // The package line says allow; the runtime permission of the uid says "only while in use".
        assertEquals(Mode.FOREGROUND, assistant.ops.getValue(Op.CAMERA).mode)
        assertEquals(Mode.ALLOW, assistant.ops.getValue(Op.COARSE_LOCATION).mode)
        assertTrue(assistant.ops.getValue(Op.MONITOR_LOCATION).events.single().running)
    }

    @Test fun packagesWithoutSensorOpsAreLeftOut() {
        val r = AppOpsParser.parse(fixture("appops-sensors.txt"), dumpTime, utc)
        assertFalse("com.google.android.gsf" in r.packages)
        assertEquals(setOf("com.google.android.gms", "com.google.android.as"), r.packages.keys)
    }

    @Test fun streamingAndTextGiveTheSameResult() {
        val text = fixture("appops-sensors.txt")
        assertEquals(AppOpsParser.parse(text, dumpTime, utc), AppOpsParser.parse(text.lineSequence(), dumpTime, utc))
    }

    private val modern = """
        Current AppOps Service state:
          Op mode watchers:
            Op CAMERA:
              #0: ModeCallback{2ced8ee watchinguid=-1 flags=0x1 op=CAMERA from uid=1000 pid=593}
          Uid u0a200:
            state=cch
              CAMERA: mode=foreground
              RECORD_AUDIO: mode=foreground
              FINE_LOCATION: mode=foreground
            Package com.example.recorder:
              CAMERA (allow):
                null=[
                  Access: [top-s] 2026-10-18 20:00:00.000 (-2h0m0s0ms) duration=+30s0ms
                  Reject: [bg-s]2026-10-18 21:00:00.000 (-1h0m0s0ms)
                ]
              RECORD_AUDIO (allow):
                null=[
                  Access: [cch-s] 2026-10-18 21:30:00.250 (-30m0s0ms)
                  Access: [fgsvc-s] 2026-10-18 21:40:00.000 (-20m0s0ms) duration=+5m0s0ms
                  Access: [fg-tp] 2026-10-18 21:50:00.000 (-10m0s0ms)
                  Access: [fg-tpd] 2026-10-18 21:50:00.000 (-10m0s0ms) proxy[uid=10144, pkg=com.google.android.gms, attributionTag=x]
                ]
              FINE_LOCATION (allow):
                null=[
                  Access: [bg-s] 2026-10-18 21:55:00.000 (-5m0s0ms)
                  Access: [pers-s] 2026-10-18 21:56:00.000 (-4m0s0ms)
                  Access: [zzz-s] 2026-10-18 21:57:00.000 (-3m0s0ms)
                ]
              WAKE_LOCK (allow):
                null=[
                  Access: [bg-s] 2026-10-18 21:58:00.000 (-2m0s0ms) duration=+1ms
                ]
    """.trimIndent()

    private val now = at("2026-10-18T22:00:00Z")

    @Test fun statesFlagsAndRejects() {
        val p = AppOpsParser.parse(modern, now, utc).packages.getValue("com.example.recorder")
        val camera = p.ops.getValue(Op.CAMERA)
        assertEquals(Mode.FOREGROUND, camera.mode)
        val shown = camera.events.single { !it.rejected }
        assertEquals(Where.SCREEN, shown.where)
        assertEquals(30_000L, shown.durationMs)
        val rejected = camera.events.single { it.rejected }
        assertEquals(Where.BACKGROUND, rejected.where)
        assertEquals(at("2026-10-18T21:00:00Z"), rejected.timeMillis)

        val mic = p.ops.getValue(Op.RECORD_AUDIO).events
        assertEquals(Where.BACKGROUND, mic[0].where) // cached counts as background
        assertEquals(at("2026-10-18T21:30:00.250Z"), mic[0].timeMillis)
        assertEquals(Where.SERVICE, mic[1].where)
        assertEquals(300_000L, mic[1].durationMs)
        // Proxy side of an access for another app, and the access made for this app.
        assertTrue(mic[2].forOthers)
        assertFalse(mic[3].forOthers)
        assertEquals("tpd", mic[3].flags)
    }

    @Test fun unknownStatesAndOpsAreHandledQuietly() {
        val p = AppOpsParser.parse(modern, now, utc).packages.getValue("com.example.recorder")
        val location = p.ops.getValue(Op.FINE_LOCATION).events
        assertEquals(listOf(Where.BACKGROUND, Where.SYSTEM, Where.UNKNOWN), location.map { it.where })
        // WAKE_LOCK is not a sensor.
        assertEquals(setOf(Op.CAMERA, Op.RECORD_AUDIO, Op.FINE_LOCATION), p.ops.keys)
    }

    @Test fun androidTenLayoutHasNoAttributionTags() {
        val text = """
            Current AppOps Service state:
              Uid u0a77:
                Package com.example.maps:
                  COARSE_LOCATION (allow):
                    Access: [top-s] 2026-10-18 21:00:00 (-1h0m0s0ms)
                  CAMERA: mode=ignore
            """.trimIndent()
        val p = AppOpsParser.parse(text, now, utc).packages.getValue("com.example.maps")
        // No milliseconds in the clock text.
        assertEquals(at("2026-10-18T21:00:00Z"), p.ops.getValue(Op.COARSE_LOCATION).events.single().timeMillis)
        assertEquals(Mode.IGNORE, p.ops.getValue(Op.CAMERA).mode)
    }

    @Test fun relativeTimeWinsWhenTheClockTextDisagrees() {
        val text = """
            Current AppOps Service state:
              Uid u0a1:
                Package a.b:
                  CAMERA (allow):
                    x=[
                      Access: [top-s] 2026-10-18 21:00:00.000 (-1h0m0s0ms)
                    ]
            """.trimIndent()
        // Parsed in a zone five hours off: the clock text would be an hour-wrong time, the relative one is right.
        val event = AppOpsParser.parse(text, now, ZoneOffset.ofHours(5)).packages.getValue("a.b").events.single()
        assertEquals(now - 3_600_000L, event.timeMillis)
        // Without a relative time the clock text is all there is.
        val bare = text.replace(" (-1h0m0s0ms)", "")
        assertEquals(at("2026-10-18T16:00:00Z"), AppOpsParser.parse(bare, now, ZoneOffset.ofHours(5)).packages.getValue("a.b").events.single().timeMillis)
    }

    @Test fun garbageEmptyAndWrongReportsGiveEmptyResults() {
        assertTrue(AppOpsParser.parse("", now, utc).packages.isEmpty())
        assertTrue(AppOpsParser.parse("Permission Denial: can't dump appops\nfrom pid=1", now, utc).packages.isEmpty())
        // An event with no time at all is dropped; the rest of the package still counts.
        val text = """
            Current AppOps Service state:
              Uid u0a1:
                Package a.b:
                  CAMERA (allow):
                    x=[
                      Access: [top-s] soon
                      Access: [bg-s] 2026-10-18 21:00:00.000 (-1h0m0s0ms)
                    ]
            """.trimIndent()
        assertEquals(1, AppOpsParser.parse(text, now, utc).packages.getValue("a.b").events.size)
    }

    @Test fun samePackageInTwoUidBlocksIsMerged() {
        val text = """
            Current AppOps Service state:
              Uid u0a1:
                Package a.b:
                  CAMERA (allow):
                    x=[
                      Access: [top-s] 2026-10-18 21:00:00.000 (-1h0m0s0ms)
                    ]
              Uid u10a1:
                Package a.b:
                  CAMERA (allow):
                    x=[
                      Access: [bg-s] 2026-10-18 21:30:00.000 (-30m0s0ms)
                    ]
            """.trimIndent()
        assertEquals(2, AppOpsParser.parse(text, now, utc).packages.getValue("a.b").ops.getValue(Op.CAMERA).events.size)
    }

    @Test fun durationsParseToMilliseconds() {
        assertEquals(24_472_762L, AppOpsParser.durationMs("-6h47m52s762ms"))
        assertEquals(102_681L, AppOpsParser.durationMs("+1m42s681ms"))
        assertEquals(9L * 86_400_000 + 6 * 3_600_000 + 5 * 60_000 + 39_000 + 845, AppOpsParser.durationMs("-9d6h5m39s845ms"))
        assertEquals(34L, AppOpsParser.durationMs("+34ms"))
        assertNull(AppOpsParser.durationMs("0"))
        assertNull(AppOpsParser.durationMs(""))
    }

    @Test fun statesAndModesHaveNames() {
        assertEquals(Where.SCREEN, AppOpsParser.whereOf("top"))
        assertEquals(Where.SCREEN, AppOpsParser.whereOf("fg"))
        assertEquals(Where.SERVICE, AppOpsParser.whereOf("fgsvc"))
        assertEquals(Where.SERVICE, AppOpsParser.whereOf("fgsvcl"))
        assertEquals(Where.BACKGROUND, AppOpsParser.whereOf("bg"))
        assertEquals(Where.BACKGROUND, AppOpsParser.whereOf("cch"))
        assertEquals(Where.SYSTEM, AppOpsParser.whereOf("pers"))
        assertEquals(Where.UNKNOWN, AppOpsParser.whereOf("xyz"))
        assertEquals(Mode.FOREGROUND, AppOpsParser.modeOf("foreground"))
        assertEquals(Mode.DEFAULT, AppOpsParser.modeOf("default"))
        assertNull(AppOpsParser.modeOf("sometimes"))
    }
}
