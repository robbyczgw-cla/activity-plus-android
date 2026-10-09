package xyz.activityplus.android.core

/**
 * "Why is my phone slow?" Plain rules over what Android lets us see. Each finding names the
 * evidence and, where there is one, the app and the system screen that fixes it. No cleaner,
 * no task killer: Android restarts killed apps, and the system settings are the real fix.
 */
object Diagnosis {
    enum class Severity { INFO, WARN, BAD }

    enum class Kind {
        STORAGE_FULL, LOW_MEMORY, HOT, POWER_SAVE, TOP_DRAIN_APP, BACKGROUND_SERVICE,
        BACKGROUND_DATA, SCREEN_OFF_DRAIN, BATTERY_WORN, LONG_UPTIME, SLOW_ANIMATIONS, UNUSED_APPS,
    }

    enum class Fix { STORAGE_SETTINGS, APP_INFO, BATTERY_SAVER, BATTERY_USAGE, DEVELOPER_OPTIONS, NONE }

    data class Finding(
        val kind: Kind,
        val severity: Severity,
        val fix: Fix,
        val pkg: String? = null,
        val app: String? = null,
        /** Numbers the text needs, named per kind (see the string resources). */
        val values: Map<String, Double> = emptyMap(),
        /** Extra apps for list findings (unused apps). */
        val apps: List<String> = emptyList(),
    )

    data class Input(
        val storageFreeBytes: Long,
        val storageTotalBytes: Long,
        val memAvailableBytes: Long,
        val memThresholdBytes: Long,
        val lowMemory: Boolean,
        val thermal: ThermalStatus,
        val batteryTempC: Double,
        val powerSave: Boolean,
        val uptimeSeconds: Long,
        val healthFraction: Double?,
        val batteryHealth: BatteryHealth,
        val animatorScale: Float,
        val foregroundLabel: String?,
        /** Today's usage per app. */
        val apps: List<AppUsage>,
        /** Today's measured energy per package, including [DrainTracker.SCREEN_OFF]. */
        val energy: Map<String, Pair<Double, Double>>,
        val capacityMah: Double?,
        val voltage: Double,
        val nowMillis: Long,
        /** Activity+ itself: it is on screen whenever someone checks, so it is never the culprit. */
        val ownPackage: String? = null,
    )

    private const val GB = 1_000_000_000L

    fun run(i: Input): List<Finding> {
        val out = ArrayList<Finding>()
        val labels = i.apps.associate { it.pkg to it.label }

        // Storage: Android slows down and stops installing updates when it runs out.
        val freeFraction = if (i.storageTotalBytes > 0) i.storageFreeBytes.toDouble() / i.storageTotalBytes else 1.0
        if (freeFraction < 0.10 || i.storageFreeBytes < 3 * GB) {
            out += Finding(
                Kind.STORAGE_FULL,
                if (freeFraction < 0.05 || i.storageFreeBytes < GB) Severity.BAD else Severity.WARN,
                Fix.STORAGE_SETTINGS,
                values = mapOf("free" to i.storageFreeBytes.toDouble(), "total" to i.storageTotalBytes.toDouble()),
            )
        }

        if (i.lowMemory || i.memAvailableBytes < i.memThresholdBytes * 1.3) {
            out += Finding(
                Kind.LOW_MEMORY, Severity.WARN, Fix.NONE,
                app = i.foregroundLabel,
                values = mapOf("available" to i.memAvailableBytes.toDouble()),
            )
        }

        if (i.thermal >= ThermalStatus.MODERATE || i.batteryTempC >= 42) {
            out += Finding(
                Kind.HOT,
                if (i.thermal >= ThermalStatus.SEVERE || i.batteryTempC >= 45) Severity.BAD else Severity.WARN,
                Fix.NONE,
                app = i.foregroundLabel,
                values = mapOf("temp" to i.batteryTempC, "status" to i.thermal.ordinal.toDouble()),
            )
        }

        if (i.powerSave) out += Finding(Kind.POWER_SAVE, Severity.INFO, Fix.BATTERY_SAVER)

        // The app that used the most battery on screen today, if it stands out.
        val appEnergy = i.energy.filterKeys { !it.startsWith("#") && it != i.ownPackage }
        val screenOnTotal = i.energy.filterKeys { it != DrainTracker.SCREEN_OFF }.values.sumOf { it.first }
        appEnergy.maxByOrNull { it.value.first }?.let { (pkg, v) ->
            val (mwh, seconds) = v
            if (mwh >= 300 && screenOnTotal > 0 && mwh / screenOnTotal >= 0.3) {
                out += Finding(
                    Kind.TOP_DRAIN_APP, Severity.INFO, Fix.APP_INFO, pkg, labels[pkg] ?: pkg,
                    values = mapOf("mwh" to mwh, "seconds" to seconds, "share" to mwh / screenOnTotal),
                )
            }
        }

        // Foreground services: working in the background with a notification for hours.
        i.apps.filter { it.serviceSeconds >= 3 * 3600 && it.screenSeconds < 15 * 60 && !it.system && it.pkg != i.ownPackage }
            .sortedByDescending { it.serviceSeconds }
            .take(2)
            .forEach {
                out += Finding(
                    Kind.BACKGROUND_SERVICE, Severity.WARN, Fix.APP_INFO, it.pkg, it.label,
                    values = mapOf("seconds" to it.serviceSeconds.toDouble()),
                )
            }

        i.apps.filter { it.backgroundBytes >= 300_000_000 && it.pkg != SYSTEM_ROW }
            .maxByOrNull { it.backgroundBytes }
            ?.let {
                out += Finding(
                    Kind.BACKGROUND_DATA, Severity.WARN, Fix.APP_INFO, it.pkg, it.label,
                    values = mapOf("bytes" to it.backgroundBytes.toDouble(), "mobile" to it.mobileBytes.toDouble()),
                )
            }

        // Screen-off drain above ~2 % per hour means something keeps the phone awake.
        val off = i.energy[DrainTracker.SCREEN_OFF]
        if (off != null && off.second >= 2 * 3600 && i.capacityMah != null && i.voltage > 0) {
            val percentPerHour = off.first / (off.second / 3600) / (i.capacityMah * i.voltage) * 100
            if (percentPerHour >= 2) {
                out += Finding(
                    Kind.SCREEN_OFF_DRAIN, if (percentPerHour >= 4) Severity.BAD else Severity.WARN, Fix.BATTERY_USAGE,
                    values = mapOf("percentPerHour" to percentPerHour, "seconds" to off.second),
                )
            }
        }

        val worn = i.healthFraction?.let { it < 0.80 } ?: false
        if (worn || i.batteryHealth in setOf(BatteryHealth.DEAD, BatteryHealth.FAILURE, BatteryHealth.OVER_VOLTAGE)) {
            out += Finding(
                Kind.BATTERY_WORN, Severity.WARN, Fix.NONE,
                values = listOfNotNull(i.healthFraction?.let { "health" to it }).toMap(),
            )
        }

        if (i.uptimeSeconds >= 14 * 86_400) {
            out += Finding(Kind.LONG_UPTIME, Severity.INFO, Fix.NONE, values = mapOf("seconds" to i.uptimeSeconds.toDouble()))
        }

        if (i.animatorScale > 1.0f) {
            out += Finding(
                Kind.SLOW_ANIMATIONS, Severity.INFO, Fix.DEVELOPER_OPTIONS,
                values = mapOf("scale" to i.animatorScale.toDouble()),
            )
        }

        // Unused apps only matter when space is short.
        if (out.any { it.kind == Kind.STORAGE_FULL }) {
            val cutoff = i.nowMillis - 60L * 86_400_000
            val unused = i.apps.filter {
                !it.system && it.lastUsedMillis in 1 until cutoff && it.installedMillis < cutoff && it.storageBytes > 200_000_000
            }.sortedByDescending { it.storageBytes }
            if (unused.isNotEmpty()) {
                out += Finding(
                    Kind.UNUSED_APPS, Severity.INFO, Fix.APP_INFO, unused.first().pkg, unused.first().label,
                    values = mapOf("bytes" to unused.sumOf { it.storageBytes }.toDouble(), "count" to unused.size.toDouble()),
                    apps = unused.take(5).map { it.label },
                )
            }
        }

        return out.sortedByDescending { it.severity }
    }
}
