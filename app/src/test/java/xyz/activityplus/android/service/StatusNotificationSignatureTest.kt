package xyz.activityplus.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import xyz.activityplus.android.core.BatteryHealth
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.ChargeStatus
import xyz.activityplus.android.core.CpuState
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.MemoryState
import xyz.activityplus.android.core.NetworkState
import xyz.activityplus.android.core.PlugType
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.StorageState
import xyz.activityplus.android.core.ThermalState
import xyz.activityplus.android.core.ThermalStatus
import xyz.activityplus.android.core.Transport
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.data.StatusItem
import java.util.Locale

/** The service posts the status notification only when this changes (see MonitorService.onSample). */
class StatusNotificationSignatureTest {
    @Before fun setUp() { Format.locale = Locale.US }

    private fun snapshot(time: Long = 0, level: Double = 0.8, current: Double = -400.0, rx: Double = 0.0, temp: Double = 30.0) = Snapshot(
        timeMillis = time,
        battery = BatteryState(
            levelFraction = level, status = ChargeStatus.DISCHARGING, plug = PlugType.NONE, health = BatteryHealth.GOOD,
            temperatureC = temp, voltageV = 4.0, currentMa = current, chargeMah = null, estimatedFullMah = null, designMah = null,
            cycleCount = null, adapterMaxWatts = null, chargeTimeRemainingSeconds = null, technology = null,
        ),
        memory = MemoryState(8_000_000_000, 3_000_000_000, 500_000_000, false, 0, 0, 0),
        storage = StorageState(100_000_000_000, 40_000_000_000),
        network = NetworkState(rx, 0.0, Transport.WIFI, false, null, null, null, null, null, null, false, emptyList()),
        cpu = CpuState(emptyList(), 0.0),
        thermal = ThermalState(ThermalStatus.NONE, null, emptyList()),
        screenOn = true, powerSave = false, uptimeSeconds = 100, foregroundPackage = null,
    )

    private val settings = Settings(items = listOf(StatusItem.POWER, StatusItem.TEMPERATURE, StatusItem.BATTERY))

    @Test fun sameRoundedValuesMeanNoNewPost() {
        // Another second, a hair more current: the same text on the notification.
        val a = StatusNotification.signature(snapshot(time = 1000, current = -400.0), settings, "Maps")
        val b = StatusNotification.signature(snapshot(time = 6000, current = -400.2), settings, "Maps")
        assertEquals(a, b)
    }

    @Test fun anyVisibleChangePosts() {
        val base = StatusNotification.signature(snapshot(), settings, "Maps")
        assertNotEquals(base, StatusNotification.signature(snapshot(level = 0.79), settings, "Maps"))
        assertNotEquals(base, StatusNotification.signature(snapshot(temp = 31.0), settings, "Maps"))
        assertNotEquals(base, StatusNotification.signature(snapshot(current = -900.0), settings, "Maps"))
        assertNotEquals(base, StatusNotification.signature(snapshot(), settings, "Chrome"))
        assertNotEquals(base, StatusNotification.signature(snapshot(), settings.copy(items = listOf(StatusItem.BATTERY)), "Maps"))
        assertNotEquals(base, StatusNotification.signature(snapshot(), settings.copy(iconItem = StatusItem.BATTERY), "Maps"))
    }

    @Test fun theAppLineOnlyCountsWhenItIsShown() {
        val off = settings.copy(appLine = false)
        assertEquals(
            StatusNotification.signature(snapshot(), off, "Maps"),
            StatusNotification.signature(snapshot(), off, "Chrome"),
        )
    }
}
