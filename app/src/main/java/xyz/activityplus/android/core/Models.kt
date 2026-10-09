package xyz.activityplus.android.core

/** One reading of the whole phone. Built by [SystemSampler] once per tick. */
data class Snapshot(
    val timeMillis: Long,
    val battery: BatteryState,
    val memory: MemoryState,
    val storage: StorageState,
    val network: NetworkState,
    val cpu: CpuState,
    val thermal: ThermalState,
    val screenOn: Boolean,
    val powerSave: Boolean,
    val uptimeSeconds: Long,
    /** Package on screen, if usage access is granted. */
    val foregroundPackage: String?,
)

enum class ChargeStatus { CHARGING, DISCHARGING, NOT_CHARGING, FULL, UNKNOWN }
enum class PlugType { NONE, AC, USB, WIRELESS, DOCK }
enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, FAILURE, COLD, UNKNOWN }

data class BatteryState(
    val levelFraction: Double,
    val status: ChargeStatus,
    val plug: PlugType,
    val health: BatteryHealth,
    val temperatureC: Double,
    val voltageV: Double,
    /** Current in mA, positive into the battery, negative out of it. Null when the phone does not report it. */
    val currentMa: Double?,
    /** Charge left in mAh, from the fuel gauge. */
    val chargeMah: Double?,
    /** Full-charge capacity estimated from the fuel gauge and the level. */
    val estimatedFullMah: Double?,
    /** Rated capacity from the system power profile. */
    val designMah: Double?,
    val cycleCount: Int?,
    /** What the charger offers, from the hidden max_charging_* extras. */
    val adapterMaxWatts: Double?,
    val chargeTimeRemainingSeconds: Long?,
    val technology: String?,
    /** Current averaged over the last ~30 seconds; the instant value jumps with every frame drawn. */
    val avgCurrentMa: Double? = null,
) {
    val plugged get() = plug != PlugType.NONE
    val charging get() = status == ChargeStatus.CHARGING

    /** Power in mW, positive into the battery, negative out of it. */
    val powerMw: Double? get() = currentMa?.let { it * voltageV }

    val avgPowerMw: Double? get() = (avgCurrentMa ?: currentMa)?.let { it * voltageV }

    /** Capacity the rates are measured against: the gauge's estimate, else the rating. */
    val capacityMah: Double? get() = estimatedFullMah ?: designMah

    /** Percent per hour from the averaged current; positive while charging. Finer than the 1 % level steps. */
    val percentPerHour: Double?
        get() = BatteryMath.percentPerHour(avgCurrentMa ?: currentMa, capacityMah)

    /** Seconds until full (charging) or empty (discharging) at the current average rate. */
    val timeLeftSeconds: Long?
        get() = if (charging) chargeTimeRemainingSeconds ?: BatteryMath.secondsToFull(levelFraction, percentPerHour)
        else BatteryMath.secondsToEmpty(levelFraction, percentPerHour)

    val healthFraction: Double?
        get() {
            val full = estimatedFullMah ?: return null
            val design = designMah ?: return null
            if (design <= 0) return null
            return (full / design).coerceIn(0.0, 1.2)
        }
}

data class MemoryState(
    val totalBytes: Long,
    val availableBytes: Long,
    /** Below this the system starts killing background apps. */
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
    val cachedBytes: Long,
    val freeBytes: Long = 0,
    val buffersBytes: Long = 0,
    /** zram: data stored before and after compression, when the kernel lets apps read it. */
    val zramOriginalBytes: Long? = null,
    val zramCompressedBytes: Long? = null,
) {
    /** Cache Android can drop at once: file cache and buffers, never more than what is available. */
    val reclaimableBytes get() = (cachedBytes + buffersBytes).coerceAtMost(availableBytes)

    /** Held by apps and the system right now. */
    val appsBytes get() = (totalBytes - availableBytes).coerceAtLeast(0)

    val zramRatio: Double?
        get() {
            val o = zramOriginalBytes ?: return null
            val c = zramCompressedBytes ?: return null
            return if (c > 0 && o > 0) o.toDouble() / c else null
        }

    val usedBytes get() = totalBytes - availableBytes
    val usedFraction get() = if (totalBytes > 0) usedBytes.toDouble() / totalBytes else 0.0
    val swapUsedBytes get() = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0)
}

data class StorageState(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes get() = totalBytes - freeBytes
    val freeFraction get() = if (totalBytes > 0) freeBytes.toDouble() / totalBytes else 1.0
}

enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, NONE }

data class NetworkState(
    val rxBytesPerSecond: Double,
    val txBytesPerSecond: Double,
    val transport: Transport,
    val vpn: Boolean,
    val wifiRssi: Int?,
    val wifiLinkMbps: Int?,
    val wifiFrequencyMhz: Int?,
    /** Wi-Fi generation: 4, 5, 6, 7 (Android 11+). */
    val wifiGeneration: Int?,
    val wifiTxMbps: Int?,
    val wifiRxMbps: Int?,
    val metered: Boolean,
    val addresses: List<String>,
)

data class CoreClock(val index: Int, val curMhz: Double?, val maxMhz: Double?, val online: Boolean)

data class CpuState(
    val cores: List<CoreClock>,
    /** Busy time of Activity+ itself, as a fraction of one core. */
    val ownCpuFraction: Double,
) {
    /** Clock speed against the maximum over all online cores; Android hides real CPU usage from apps. */
    val clockFraction: Double?
        get() {
            val known = cores.filter { it.online && it.curMhz != null && it.maxMhz != null && it.maxMhz > 0 }
            if (known.isEmpty()) return null
            return known.sumOf { it.curMhz!! } / known.sumOf { it.maxMhz!! }
        }

    val averageMhz: Double?
        get() = cores.mapNotNull { if (it.online) it.curMhz else null }.takeIf { it.isNotEmpty() }?.average()

    /** Cores grouped by maximum clock: efficiency, performance, prime. */
    val clusters: List<List<CoreClock>>
        get() = cores.groupBy { it.maxMhz ?: 0.0 }.toSortedMap().values.toList()
}

enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class ThermalState(
    val status: ThermalStatus,
    /** 1.0 means throttling starts; NaN or null when not reported. */
    val headroom: Double?,
    val sensors: List<Sensor>,
)

data class Sensor(val name: String, val celsius: Double)
