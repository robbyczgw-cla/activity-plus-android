package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class JobParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    private val utc = ZoneOffset.UTC

    @Test fun registeredJobsFromEmulator() {
        val r = JobParser.parse(fixture("jobscheduler.txt"), utc)
        // The finished job under "Recently completed jobs" is no registered one.
        assertEquals(4, r.jobs.size)
        assertTrue(r.jobs.none { it.pkg == "com.google.android.googlequicksearchbox" })

        val mapping = r.jobs.single { it.id == "usagestats_mapping:0" }
        assertEquals("1000", mapping.uid)
        assertEquals("android", mapping.pkg)
        assertEquals("com.android.server.usage.UsageStatsIdleService", mapping.service)
        assertNull(mapping.periodicMs)
        assertEquals(86_400_000L, mapping.minLatencyMs)
        assertEquals(23L * 3_600_000 + 57 * 60_000 + 59_889, mapping.earliestInMs)
        assertFalse(mapping.requiresCharging)

        val vm = r.jobs.single { it.service.endsWith("SecretkeeperJobService") }
        assertEquals(86_400_000L, vm.periodicMs)
        assertTrue(vm.requiresCharging)
        assertTrue(vm.requiresIdle)
        assertFalse(vm.requiresNetwork)

        val safety = r.jobs.single { it.pkg == "com.google.android.apps.safetyhub" }
        assertEquals("u0a130", safety.uid)
        assertEquals("50", safety.id)
        assertEquals("androidx.work.impl.background.systemjob.SystemJobService", safety.service)
        assertEquals(4L * 3_600_000 + 59 * 60_000 + 739, safety.earliestInMs)
        assertEquals("EXEMPTED", safety.bucket)
        assertNull(safety.lastRunMillis)

        val metrics = r.jobs.single { it.pkg == "com.google.android.apps.messaging" }
        assertEquals("30000", metrics.id)
        assertEquals(86_400_000L, metrics.periodicMs)
        assertTrue(metrics.requiresNetwork)
        assertFalse(metrics.requiresUnmetered)
        assertEquals("ACTIVE", metrics.bucket)
        assertEquals(LocalDateTime.of(2026, 10, 18, 21, 47, 56).toInstant(utc).toEpochMilli(), metrics.lastRunMillis)
    }

    @Test fun runsFromTheStatsBlock() {
        val r = JobParser.parse(fixture("jobscheduler.txt"), utc)
        assertEquals(130_756L, r.runsSpanMs)
        assertEquals(7, r.runs["com.google.android.apps.messaging"])
        assertEquals(2, r.runs["com.google.android.partnersetup"])
        assertEquals(1, r.runs["com.google.android.as"])
        // From the stats, not the history lines below them.
        assertNull(r.runs["com.google.android.apps.photos"])
    }

    @Test fun statsBlocksAddUpAndSkipJobsOnScreen() {
        val text = """
            |Last stats at 2026-10-18-20-47-37 (-1h0m0s0ms) over +30m0s0ms:
            |  u0a200 / com.example.news:
            |    4x pending 3x active
            |  u0a201 / com.example.game:
            |    2x active-top
            |
            |Current stats at 2026-10-18-21-17-37 (-30m0s0ms) over +30m0s0ms:
            |  u0a200 / com.example.news:
            |    2x pending 1% 2x active 1x active-top
            |    2x successful_finish
        """.trimMargin()
        val r = JobParser.parse(text, utc)
        assertEquals(3_600_000L, r.runsSpanMs)
        assertEquals(5, r.runs["com.example.news"])
        assertNull(r.runs["com.example.game"])
    }

    @Test fun historyWhenThereAreNoStats() {
        val text = """
            |  Job history:
            |       -2h0m0s0ms   START: #u0a200/7 com.example.news/.SyncJob
            |       -1h0m0s0ms    STOP: #u0a200/7 com.example.news/.SyncJob app called jobFinished
            |         -30m0s0ms START-P: #u0a200/8 @androidx.work.systemjobscheduler@com.example.news/androidx.work.impl.background.systemjob.SystemJobService
            |           -5s0ms   START: #u0a300/1 com.example.mail/.Fetch
        """.trimMargin()
        val r = JobParser.parse(text, utc)
        assertEquals(2, r.runs["com.example.news"])
        assertEquals(1, r.runs["com.example.mail"])
        assertEquals(2 * 3_600_000L, r.runsSpanMs)
        assertTrue(r.jobs.isEmpty())
    }

    @Test fun sourcePackageAndUnmeteredNetwork() {
        // A sync run through another app counts for the app it works for; vendor lines are skipped.
        val text = """
            |Registered 1 jobs:
            |  JOB #1000/77: 1a2b3c android/com.android.server.content.SyncJobService
            |    Source: uid=u0a210 user=0 pkg=com.example.mail
            |    JobInfo:
            |      Service: android/com.android.server.content.SyncJobService
            |      PERIODIC: interval=+15m0s0ms flex=+5m0s0ms
            |      Requires: charging=false batteryNotLow=true deviceIdle=false
            |      Network type: NetworkRequest [ NONE id=0, [ Capabilities: NOT_METERED&INTERNET ] ]
            |    Vendor boost: off
            |    Run time: earliest=none, latest=none, original latest=none
            |Pending queue:
            |  None
        """.trimMargin()
        val job = JobParser.parse(text, utc).jobs.single()
        assertEquals("com.example.mail", job.pkg)
        assertEquals(15 * 60_000L, job.periodicMs)
        assertTrue(job.requiresBatteryNotLow)
        assertTrue(job.requiresNetwork)
        assertTrue(job.requiresUnmetered)
        assertNull(job.earliestInMs)
        assertNull(job.bucket)
    }

    @Test fun emptyTextGivesEmptyReport() {
        val r = JobParser.parse("Permission Denial: can't dump jobscheduler", utc)
        assertTrue(r.jobs.isEmpty())
        assertTrue(r.runs.isEmpty())
        assertNull(r.runsSpanMs)
    }
}
