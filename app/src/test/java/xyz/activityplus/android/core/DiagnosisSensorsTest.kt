package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Diagnosis.Kind

class DiagnosisSensorsTest {
    private val gb = 1_000_000_000L

    private fun input(backgroundLocation: List<Pair<SensorLog.BackgroundUse, String>> = emptyList()) = Diagnosis.Input(
        60 * gb, 128 * gb, 3 * gb, gb / 4, false, ThermalStatus.NONE, 30.0, false, 3600, 0.95, BatteryHealth.GOOD,
        1f, "Chrome", emptyList(), emptyMap(), 4000.0, 3.9, 1_800_000_000_000L, backgroundLocation = backgroundLocation,
    )

    @Test fun noLocationUseNoFinding() {
        assertTrue(Diagnosis.run(input()).none { it.kind == Kind.BACKGROUND_LOCATION })
    }

    @Test fun appsAskingInTheBackgroundGetAWarningWithAppInfoAsFix() {
        val use = SensorLog.BackgroundUse("com.example.weather", 20, 0)
        val f = Diagnosis.run(input(listOf(use to "Weather"))).single { it.kind == Kind.BACKGROUND_LOCATION }
        assertEquals(Diagnosis.Severity.WARN, f.severity)
        assertEquals(Diagnosis.Fix.APP_INFO, f.fix)
        assertEquals("com.example.weather", f.pkg)
        assertEquals("Weather", f.app)
        assertEquals(20.0, f.values.getValue("uses"), 0.0)
    }

    @Test fun onlyTheTwoWorstAreListed() {
        val list = listOf("a", "b", "c").map { SensorLog.BackgroundUse(it, 30, 0) to it }
        assertEquals(2, Diagnosis.run(input(list)).count { it.kind == Kind.BACKGROUND_LOCATION })
    }
}
