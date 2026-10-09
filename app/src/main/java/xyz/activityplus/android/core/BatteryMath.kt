package xyz.activityplus.android.core

import kotlin.math.abs

/** Vendor quirks of the fuel gauge, kept apart from Android so they can be tested. */
object BatteryMath {
    /** Values below this magnitude are taken as mA: some vendors ignore the µA unit in the docs. */
    private const val MILLIAMP_CUTOFF = 10_000

    /** Above this the reading is nonsense (no phone draws 15 A). */
    private const val MAX_PLAUSIBLE_MA = 15_000.0

    /**
     * Turns BATTERY_PROPERTY_CURRENT_NOW into mA with a sign that matches the charge state
     * (positive into the battery). Vendors disagree on both unit and sign, so the sign is taken
     * from the status instead of the raw value.
     */
    fun currentMa(raw: Long?, status: ChargeStatus, plugged: Boolean): Double? {
        if (raw == null || raw == 0L || raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong()) return null
        val magnitude = abs(raw).toDouble()
        val ma = if (magnitude < MILLIAMP_CUTOFF) magnitude else magnitude / 1000.0
        if (ma > MAX_PLAUSIBLE_MA) return null
        return when (status) {
            ChargeStatus.CHARGING -> ma
            ChargeStatus.DISCHARGING -> -ma
            // Full or held by a charge limit: the adapter carries the phone, the raw sign says which way the rest flows.
            else -> if (plugged) (if (raw > 0) ma else -ma) else -ma
        }
    }

    /** BATTERY_PROPERTY_CHARGE_COUNTER is µAh by spec; some gauges report mAh. */
    fun chargeMah(rawCounter: Long?, designMah: Double?): Double? {
        if (rawCounter == null || rawCounter <= 0 || rawCounter == Int.MIN_VALUE.toLong()) return null
        val asMah = rawCounter / 1000.0
        // A µAh reading of a 4000 mAh battery is ~4,000,000. If it is in mAh already it is below the design capacity.
        if (designMah != null && rawCounter < designMah * 2) return rawCounter.toDouble()
        return asMah
    }

    /** Full-charge capacity from the gauge: charge now divided by the level. Unreliable near empty. */
    fun estimatedFullMah(chargeMah: Double?, levelFraction: Double, designMah: Double? = null): Double? {
        if (chargeMah == null || levelFraction < 0.15) return null
        val full = chargeMah / levelFraction
        // Emulators and some gauges report a placeholder; below 40 % of the rating it is not a measurement.
        if (designMah != null && (full < designMah * 0.4 || full > designMah * 1.3)) return null
        if (full < 500) return null
        return full
    }

    /** Percent per hour from two readings; positive while charging. */
    fun percentPerHour(levelA: Double, timeA: Long, levelB: Double, timeB: Long): Double? {
        val hours = (timeB - timeA) / 3_600_000.0
        if (hours < 1.0 / 60) return null
        return (levelB - levelA) * 100 / hours
    }

    fun percentPerHour(currentMa: Double?, capacityMah: Double?): Double? {
        if (currentMa == null || capacityMah == null || capacityMah <= 0) return null
        return currentMa / capacityMah * 100
    }

    /** Above 80 % phones taper the current, so this is a floor; the system estimate is preferred. */
    fun secondsToFull(level: Double, percentPerHour: Double?): Long? {
        if (percentPerHour == null || percentPerHour < 0.5 || level >= 1.0) return null
        return ((1.0 - level) * 100 / percentPerHour * 3600).toLong()
    }

    fun secondsToEmpty(level: Double, percentPerHour: Double?): Long? {
        if (percentPerHour == null || percentPerHour > -0.2) return null
        return (level * 100 / -percentPerHour * 3600).toLong().coerceAtMost(30L * 86_400)
    }

    enum class ChargeSpeed { TRICKLE, SLOW, NORMAL, FAST, SUPER_FAST }

    /** By power into the phone: USB 2.0 gives 2.5 W, a basic 5 V/2 A brick 10 W, USB-PD fast charging 18 W and up. */
    fun chargeSpeed(watts: Double): ChargeSpeed = when {
        watts < 2.5 -> ChargeSpeed.TRICKLE
        watts < 7.5 -> ChargeSpeed.SLOW
        watts < 15 -> ChargeSpeed.NORMAL
        watts < 30 -> ChargeSpeed.FAST
        else -> ChargeSpeed.SUPER_FAST
    }

    /** Hours left at the given discharge power. */
    fun hoursLeft(chargeMah: Double?, voltageV: Double, dischargeMw: Double): Double? {
        if (chargeMah == null || dischargeMw <= 50) return null
        return chargeMah * voltageV / dischargeMw
    }
}
