package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.pro.AlarmParser.Type

class AlarmParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    private val nowRtc = 1_792_360_188_304L

    @Test fun recognisesReportsButNotGarbageOrPermissionDenial() {
        assertFalse(AlarmParser.recognised("garbage"))
        assertFalse(AlarmParser.recognised("Pending alarm batches: 0\nPermission Denial"))
        assertTrue(AlarmParser.recognised("Pending alarm batches: 0"))
    }

    @Test fun pendingAlarmsFromEmulator() {
        val r = AlarmParser.parse(fixture("alarm.txt"))
        assertEquals(nowRtc, r.nowMillis)
        assertEquals(147_047L, r.sinceBootMs)
        assertEquals(11, r.alarms.size)
        // The removal history further down repeats tags but is no pending alarm.
        assertTrue(r.alarms.none { it.tag.startsWith("WificondScannerImpl") })

        val gcm = r.alarms.single { it.tag == "com.google.android.intent.action.GCM_RECONNECT" }
        assertEquals("com.google.android.gms", gcm.pkg)
        assertEquals(Type.ELAPSED_WAKEUP, gcm.type)
        assertEquals(nowRtc + 15_501, gcm.whenMillis)
        assertTrue(gcm.wakeup && gcm.exact && gcm.allowWhileIdle)
        assertFalse(gcm.alarmClock)

        // Delivery time on the elapsed clock, turned into the wall clock: 22:00:00 as the app asked.
        val settings = r.alarms.single { it.pkg == "com.android.settings" }
        assertEquals(1_792_360_800_000L, settings.whenMillis)
        assertEquals(Type.RTC_WAKEUP, settings.type)

        val calendar = r.alarms.filter { it.pkg == "com.google.android.calendar" }
        assertEquals(2, calendar.size)
        assertTrue(calendar.all { it.exact && it.allowWhileIdle && it.wakeup }) // flags 0x5

        val provider = r.alarms.single { it.pkg == "com.android.providers.calendar" }
        assertEquals(21_600_000L, provider.repeatMs)
        assertEquals(4L * 3_600_000 + 30 * 60_000, provider.windowMs)
        assertFalse(provider.exact)
        assertFalse(provider.allowWhileIdle)

        val maps = r.alarms.single { it.pkg == "com.google.android.apps.maps" }
        assertFalse(maps.wakeup)
        assertEquals(60_000L, maps.repeatMs)
    }

    @Test fun farFutureAlarmsHaveNoTime() {
        val r = AlarmParser.parse(fixture("alarm.txt"))
        // Ten years ahead, and Long.MAX_VALUE: parked, not planned.
        assertNull(r.alarms.single { it.tag.endsWith("GoogleLocationService") }.whenMillis)
        assertNull(r.alarms.single { it.pkg == "com.google.android.googlequicksearchbox" }.whenMillis)
    }

    @Test fun alarmStatsPerPackage() {
        val stats = AlarmParser.parse(fixture("alarm.txt")).stats
        val android = stats.getValue("android")
        assertEquals(7L, android.wakeups)
        assertEquals(178L, android.runningMs)
        assertEquals(15L, android.count)
        assertEquals(8, android.tags.size)
        val gms = stats.getValue("com.google.android.gms")
        assertEquals(2L, gms.wakeups)
        assertEquals(8L, gms.count)
        val reconnect = gms.tags.single { it.tag == "com.google.android.intent.action.GCM_RECONNECT" }
        assertEquals(1L, reconnect.wakeups)
        assertEquals(2L, reconnect.runningMs)
        assertEquals(1L, stats.getValue("com.android.systemui").wakeups)
        assertEquals(5, stats.size)
    }

    @Test fun topAlarmsWhenAlarmStatsAreMissing() {
        val text = """
            |  nowRTC=1000000=2020-01-01 00:16:40.000 nowELAPSED=500000
            |  Top Alarms:
            |    +97ms running, 1 wakeups, 1 alarms: u0a101:com.example.sync
            |      *walarm*:com.example.sync.RUN
            |    +3ms running, 4 wakeups, 6 alarms: u0a101:com.example.sync
            |      *walarm*:com.example.sync.PING
            |
        """.trimMargin()
        val sync = AlarmParser.parse(text).stats.getValue("com.example.sync")
        assertEquals(5L, sync.wakeups)
        assertEquals(7L, sync.count)
        assertEquals(100L, sync.runningMs)
        assertEquals(listOf("com.example.sync.RUN", "com.example.sync.PING"), sync.tags.map { it.tag })
    }

    @Test fun olderLayoutWithBatchesAndRequestedTimes() {
        // Android 10/11 as AOSP's dump code writes it: alarms in batches, "when" on the alarm's own clock,
        // numeric types in the detail line. Unknown vendor lines are skipped.
        val text = """
            |Current Alarm Manager state:
            |  nowRTC=1580000000000=2020-01-26 00:53:20.000 nowELAPSED=3600000
            |  Pending alarm batches: 2
            |Batch{5c2ad3a num=1 start=3660000 end=3660000 flgs=0x5}:
            |    RTC_WAKEUP #0: Alarm{2b9d0b type 0 when 1580000060000 com.example.chat}
            |      tag=*walarm*:com.example.chat.KEEPALIVE
            |      type=0 expectedWhenElapsed=+1m0s0ms expectedMaxWhenElapsed=+1m0s0ms whenElapsed=+1m0s0ms maxWhenElapsed=+1m0s0ms when=2020-01-26 00:54:20.000
            |      window=0 repeatInterval=0 count=0 flags=0x5
            |      vendor_policy=whatever
            |      operation=PendingIntent{1a2b3c: PendingIntentRecord{4d5e6f com.example.chat broadcastIntent}}
            |Batch{77aa01 num=1 start=5400000 end=6750000 flgs=0x0}:
            |    ELAPSED_WAKEUP #0: Alarm{3c1e7f type 2 when 5400000 com.example.news}
            |      tag=*walarm*:com.example.news.REFRESH
            |      type=2 expectedWhenElapsed=+30m0s0ms expectedMaxWhenElapsed=+52m30s0ms whenElapsed=+30m0s0ms maxWhenElapsed=+52m30s0ms when=+30m0s0ms
            |      window=+22m30s0ms repeatInterval=1800000 count=0 flags=0x0
            |    RTC #1: Alarm{9f9f9f type 1 when 1580000500000 com.example.clock}
            |      tag=*alarm*:TICK
            |      type=1 whenElapsed=+8m20s0ms when=2020-01-26 01:01:40.000
            |      window=0 repeatInterval=0 count=0 flags=0x3
        """.trimMargin()
        val r = AlarmParser.parse(text)
        assertEquals(3, r.alarms.size)
        val chat = r.alarms[0]
        assertEquals("com.example.chat", chat.pkg)
        assertEquals("com.example.chat.KEEPALIVE", chat.tag)
        assertEquals(1_580_000_060_000L, chat.whenMillis)
        assertTrue(chat.exact && chat.allowWhileIdle)
        val news = r.alarms[1]
        assertEquals(1_580_001_800_000L, news.whenMillis)
        assertEquals(1_800_000L, news.repeatMs)
        assertEquals(22L * 60_000 + 30_000, news.windowMs)
        assertTrue(r.alarms[2].alarmClock)
        assertFalse(r.alarms[2].wakeup)
        assertTrue(r.stats.isEmpty())
    }

    @Test fun emptyOrForeignTextGivesEmptyReport() {
        for (text in listOf("", "Can't find service: alarm", "Permission Denial: can't dump AlarmManager")) {
            val r = AlarmParser.parse(text)
            assertNull(r.nowMillis)
            assertTrue(r.alarms.isEmpty())
            assertTrue(r.stats.isEmpty())
        }
    }

    @Test fun wallClockWithoutAReportClock() {
        // An RTC alarm keeps its own time; an elapsed one cannot be placed without the report's clocks.
        assertEquals(5000L, AlarmParser.wallClock(Type.RTC_WAKEUP, 5000, null, null, null))
        assertNull(AlarmParser.wallClock(Type.ELAPSED_WAKEUP, 5000, null, null, null))
        assertEquals(1_000_000L + 4000, AlarmParser.wallClock(Type.ELAPSED, 5000, null, 1_000_000, 1000))
        assertNull(AlarmParser.wallClock(Type.RTC, Long.MAX_VALUE, null, 1_000_000, 1000))
    }

    @Test fun cleanTagDropsAndroidsPrefix() {
        assertEquals("com.app.SYNC", AlarmParser.cleanTag("*walarm*:com.app.SYNC"))
        assertEquals("TIME_TICK", AlarmParser.cleanTag("  *alarm*:TIME_TICK "))
        assertEquals("plain", AlarmParser.cleanTag("plain"))
    }

    @Test fun dumpsysDurations() {
        assertEquals(0L, dumpsysMillis("0"))
        assertEquals(86_400_000L, dumpsysMillis("+1d0h0m0s0ms"))
        assertEquals(-(60_000L + 519), dumpsysMillis("-1m0s519ms"))
        assertEquals(44_999L, dumpsysMillis("+44s999ms"))
        assertEquals(-521L, dumpsysMillis("-521ms"))
        assertEquals(3649L * 86_400_000 + 23 * 3_600_000 + 58 * 60_000 + 3967, dumpsysMillis("+3649d23h58m3s967ms"))
        assertEquals(130_756L, dumpsysMillis("+2m10s756ms,"))
        assertNull(dumpsysMillis("--"))
        assertNull(dumpsysMillis("none"))
        assertNull(dumpsysMillis("+"))
        assertNull(dumpsysMillis("12x"))
    }
}
