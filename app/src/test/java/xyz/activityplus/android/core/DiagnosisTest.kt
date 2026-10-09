package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Diagnosis.Kind

class DiagnosisTest {
    private val gb = 1_000_000_000L
    private val now = 1_800_000_000_000L

    private fun app(
        pkg: String, label: String = pkg, screen: Long = 0, service: Long = 0, bg: Long = 0,
        storage: Long = 0, lastUsed: Long = now, installed: Long = 0, system: Boolean = false,
    ) = AppUsage(
        pkg, label, system, screen, 0, service, 0, 0, bg, storage, 0, 0, lastUsed, installed,
    )

    private fun input(
        free: Long = 60 * gb, total: Long = 128 * gb, avail: Long = 3 * gb, threshold: Long = gb / 4,
        thermal: ThermalStatus = ThermalStatus.NONE, temp: Double = 30.0, uptime: Long = 3600,
        health: Double? = 0.95, apps: List<AppUsage> = emptyList(),
        energy: Map<String, Pair<Double, Double>> = emptyMap(), animator: Float = 1f,
    ) = Diagnosis.Input(
        free, total, avail, threshold, false, thermal, temp, false, uptime, health, BatteryHealth.GOOD,
        animator, "Chrome", apps, energy, 4000.0, 3.9, now,
    )

    @Test fun healthyPhoneHasNoFindings() {
        assertTrue(Diagnosis.run(input()).isEmpty())
    }

    @Test fun fullStorageIsBadAndListsUnusedApps() {
        val old = now - 90L * 86_400_000
        val f = Diagnosis.run(
            input(free = 2 * gb / 3, apps = listOf(app("big.game", "Big Game", storage = 4 * gb, lastUsed = old, installed = old)))
        )
        assertEquals(Kind.STORAGE_FULL, f.first().kind)
        assertEquals(Diagnosis.Severity.BAD, f.first().severity)
        val unused = f.single { it.kind == Kind.UNUSED_APPS }
        assertEquals("Big Game", unused.app)
    }

    @Test fun heatNamesTheAppOnScreen() {
        val f = Diagnosis.run(input(thermal = ThermalStatus.SEVERE, temp = 44.0))
        val hot = f.single { it.kind == Kind.HOT }
        assertEquals(Diagnosis.Severity.BAD, hot.severity)
        assertEquals("Chrome", hot.app)
    }

    @Test fun topDrainAppNeedsAClearShare() {
        val energy = mapOf("com.game" to (900.0 to 3600.0), "com.chat" to (300.0 to 1800.0))
        val f = Diagnosis.run(input(apps = listOf(app("com.game", "Game")), energy = energy))
        assertEquals("Game", f.single { it.kind == Kind.TOP_DRAIN_APP }.app)

        val even = mapOf("a" to (310.0 to 1.0), "b" to (300.0 to 1.0), "c" to (300.0 to 1.0), "d" to (300.0 to 1.0))
        assertTrue(Diagnosis.run(input(energy = even)).none { it.kind == Kind.TOP_DRAIN_APP })
    }

    @Test fun activityPlusIsNeverTheCulprit() {
        val energy = mapOf("xyz.activityplus.android" to (900.0 to 600.0), "com.chat" to (100.0 to 600.0))
        val f = Diagnosis.run(input(energy = energy).copy(ownPackage = "xyz.activityplus.android"))
        assertTrue(f.none { it.kind == Kind.TOP_DRAIN_APP })
    }

    @Test fun backgroundServiceForHoursWithoutScreenTime() {
        val f = Diagnosis.run(input(apps = listOf(app("com.sync", "Sync", screen = 60, service = 5 * 3600))))
        assertEquals("Sync", f.single { it.kind == Kind.BACKGROUND_SERVICE }.app)
    }

    @Test fun vpnServiceIsNotABackgroundProblem() {
        val apps = listOf(
            app("com.tailscale", "Tailscale", screen = 0, service = 16 * 3600),
            app("com.sync", "Sync", screen = 60, service = 16 * 3600),
        )
        val f = Diagnosis.run(input(apps = apps).copy(vpnPackages = setOf("com.tailscale")))
        assertEquals(listOf("Sync"), f.filter { it.kind == Kind.BACKGROUND_SERVICE }.map { it.app })
    }

    @Test fun screenOffDrainInPercentPerHour() {
        // 4000 mAh * 3.9 V = 15.6 Wh; 1.56 Wh over 2 h = 5 %/h.
        val f = Diagnosis.run(input(energy = mapOf(DrainTracker.SCREEN_OFF to (1560.0 to 7200.0))))
        val off = f.single { it.kind == Kind.SCREEN_OFF_DRAIN }
        assertEquals(5.0, off.values["percentPerHour"]!!, 1e-9)
        assertEquals(Diagnosis.Severity.BAD, off.severity)
    }

    @Test fun smallThingsAreInfo() {
        val f = Diagnosis.run(input(uptime = 20 * 86_400L, animator = 1.5f, health = 0.7))
        assertEquals(setOf(Kind.LONG_UPTIME, Kind.SLOW_ANIMATIONS, Kind.BATTERY_WORN), f.map { it.kind }.toSet())
    }
}
