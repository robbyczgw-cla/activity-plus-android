package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    @Test fun batteryCheckinFromEmulator() {
        val r = ProParser.batteryCheckin(fixture("batterystats-checkin.txt"))
        assertEquals(117_263L, r.onBatteryMs)
        assertEquals(0.0982, r.byUid[1000]!!.mah, 1e-9)
        assertEquals(6800L + 15192L, r.byUid[1000]!!.cpuMs)
        assertTrue("android" in r.packages[1000]!!)
        assertTrue(r.packages.values.flatten().contains("com.google.android.apps.messaging"))
    }

    @Test fun batteryWithDashC() {
        // `dumpsys batterystats -c`: same lines, but no per-uid cpu lines.
        val r = ProParser.batteryCheckin(fixture("batterystats-c.txt"))
        assertEquals(834L, r.onBatteryMs)
        assertTrue(r.byUid.isNotEmpty())
        assertTrue(r.packages.values.flatten().contains("android"))
        assertTrue(r.byUid.values.all { it.cpuMs == 0L })
    }

    @Test fun batteryCheckinWithRealisticValues() {
        val text = """
            9,0,i,uid,10201,com.example.maps
            9,0,l,bt,3,7200000,3600000,7200000,3600000,0,0,0,4000,3000000,3000000,0
            9,10201,l,pwi,uid,312.5,0,40.1,0
            9,10201,l,cpu,600000,120000,0
            9,10201,l,wl,*job*/com.example.maps/Sync,0,f,0,0,0,0,2400000,p,12,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0
            9,10201,l,wl,location,0,f,0,0,0,0,600000,p,3,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0
            9,0,l,pwi,scrn,210.0,1,0,0
            9,0,l,pwi,cell,55.5,1,0,0
            9,0,l,pwi,idle,0,1,0,0
        """.trimIndent()
        val r = ProParser.batteryCheckin(text)
        val maps = r.byUid[10201]!!
        assertEquals(312.5, maps.mah, 1e-9)
        assertEquals(720_000L, maps.cpuMs)
        assertEquals(3_000_000L, maps.wakelockMs) // 40 min + 10 min held awake
        assertEquals(7_200_000L, r.onBatteryMs)
        assertEquals(mapOf("scrn" to 210.0, "cell" to 55.5), r.system)
        assertEquals(listOf("com.example.maps"), r.packages[10201])
    }

    @Test fun meminfoPssByProcess() {
        val pss = ProParser.meminfoPss(fixture("meminfo.txt"))
        assertTrue(pss.size > 20)
        // The PSS block, not the RSS block above it (358,288K there).
        assertEquals(174_265L * 1024, pss["system"])
        assertTrue(pss.keys.any { it.startsWith("com.google.android.apps.messaging") })
    }

    @Test fun cpuinfoByProcess() {
        val cpu = ProParser.cpuinfo(fixture("cpuinfo.txt"))
        assertTrue(cpu.isNotEmpty())
        assertTrue(cpu.keys.none { it.contains("TOTAL") })
        assertTrue(cpu.values.all { it in 0.0..800.0 })
    }

    @Test fun processNamesFoldIntoPackages() {
        assertEquals("com.google.android.gms", ProParser.packageOf("com.google.android.gms:persistent"))
        assertEquals("com.android.chrome", ProParser.packageOf("com.android.chrome"))
    }
}

class ProReportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    @Test fun joinsTheThreeReportsPerPackage() {
        val r = ProReport.build(
            1L,
            ProParser.batteryCheckin(fixture("batterystats-checkin.txt")),
            ProParser.meminfoPss(fixture("meminfo.txt")),
            ProParser.cpuinfo(fixture("cpuinfo.txt")),
        )
        // gms runs as several processes ("com.google.android.gms.persistent", ".unstable"); one row.
        val gms = r.forPackage("com.google.android.gms")
        assertTrue(gms != null && (gms.pssBytes ?: 0) > 0)
        assertTrue(r.apps.none { it.pkg.startsWith("com.google.android.gms.") })
        // system_server, surfaceflinger and kernel threads fold into the system row.
        assertTrue((r.forPackage(ProReport.SYSTEM)?.pssBytes ?: 0) > 0)
        assertEquals(117_263L, r.onBatteryMs)
    }

    @Test fun jsonRoundTrip() {
        val r = ProReport(5L, 7L, listOf(ProApp("a.b", 1.5, 2, 3, 4, 5.5), ProApp("c.d", 0.0, 0, 0, null, null)), mapOf("scrn" to 9.0))
        assertEquals(r, ProReport.fromJson(r.toJson()))
    }
}
