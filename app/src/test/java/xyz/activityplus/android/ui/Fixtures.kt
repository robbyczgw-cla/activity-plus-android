package xyz.activityplus.android.ui

import xyz.activityplus.android.core.BatteryHealth
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.ChargeStatus
import xyz.activityplus.android.core.CpuState
import xyz.activityplus.android.core.MemoryState
import xyz.activityplus.android.core.NetworkState
import xyz.activityplus.android.core.PlugType
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.StorageState
import xyz.activityplus.android.core.ThermalState
import xyz.activityplus.android.core.ThermalStatus
import xyz.activityplus.android.core.Transport

/** Plain data for the card tests: a phone on battery at 60 %, nothing else known unless asked for. */
object Fixtures {
    const val NOW = 1_760_000_000_000L

    fun battery(
        level: Double = 0.6,
        plug: PlugType = PlugType.NONE,
        currentMa: Double? = null,
        designMah: Double? = null,
    ) = BatteryState(
        levelFraction = level,
        status = if (plug == PlugType.NONE) ChargeStatus.DISCHARGING else ChargeStatus.CHARGING,
        plug = plug,
        health = BatteryHealth.GOOD,
        temperatureC = 30.0,
        voltageV = 3.9,
        currentMa = currentMa,
        chargeMah = null,
        estimatedFullMah = null,
        designMah = designMah,
        cycleCount = null,
        adapterMaxWatts = null,
        chargeTimeRemainingSeconds = null,
        technology = null,
    )

    fun snapshot(battery: BatteryState = battery()) = Snapshot(
        timeMillis = NOW,
        battery = battery,
        memory = MemoryState(8L shl 30, 4L shl 30, 1L shl 30, false, 0, 0, 0),
        storage = StorageState(128L shl 30, 64L shl 30),
        network = NetworkState(0.0, 0.0, Transport.NONE, false, null, null, null, null, null, null, false, emptyList()),
        cpu = CpuState(emptyList(), 0.0),
        thermal = ThermalState(ThermalStatus.NONE, null, emptyList()),
        screenOn = true,
        powerSave = false,
        uptimeSeconds = 3600,
        foregroundPackage = null,
    )
}
