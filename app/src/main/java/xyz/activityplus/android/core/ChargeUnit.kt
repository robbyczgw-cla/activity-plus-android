package xyz.activityplus.android.core

import kotlin.math.abs
import kotlin.math.ln

/**
 * Android's battery report states charge in mAh. Some phones do not: a vivo X300 on Android 16 wrote
 * "285078419" for YouTube after 20 hours, on a 6000 mAh battery, apparently charge from its measured
 * energy that never got converted (1 mAh = 3.6 C = 3,600,000 µC). The report carries what is needed
 * to tell: the sum it computed against the charge the battery really lost (from the level, so in mAh),
 * and its idea of the battery's capacity. Pure, so it is tested without a phone.
 */
object ChargeUnit {
    /**
     * Units a report could plausibly be in, as "this many per mAh": µAh, mC, and µC (the unit of
     * Android's measured-energy counters, the same factor as mA·ms). No unit near a million per mAh
     * is in use, and leaving it out keeps µC apart from it when the apps cover only half the drain.
     */
    val CANDIDATES = listOf(1_000.0, 3_600.0, 3_600_000.0)

    /** Above this, sum over drained can no longer be explained by an estimate that is merely off. */
    private const val RATIO_LIMIT = 30.0

    /** No phone battery comes near this; a capacity beyond it is in some other unit. */
    private const val MAX_CAPACITY_MAH = 50_000.0

    /**
     * Units per mAh from the report itself, or null when it gives nothing to go on:
     * - its computed sum against the charge drained, when the battery lost a few mAh;
     * - its capacity against the real one ([capacityHint], from Android's battery service).
     */
    fun detect(computed: Double?, drainedLow: Double?, drainedHigh: Double?, reportCapacity: Double?, capacityHint: Double?): Double? {
        val drained = if (drainedLow != null && drainedHigh != null && drainedHigh >= 1) (drainedLow + drainedHigh) / 2 else null
        if (computed != null && computed > 0 && drained != null && drained >= 1) {
            val r = computed / drained
            return if (r < RATIO_LIMIT) 1.0 else nearest(r)
        }
        if (reportCapacity != null && capacityHint != null && capacityHint > 0 && reportCapacity > MAX_CAPACITY_MAH) {
            return nearest(reportCapacity / capacityHint)
        }
        return null
    }

    /**
     * The unit to divide by: what the report says ([detected]); else what this phone's reports were in
     * before ([known]); else, when the biggest value cannot be mAh at all, the most likely one (µC).
     */
    fun choose(detected: Double?, known: Double?, maxValue: Double, capacityHint: Double?): Double {
        if (detected != null && detected > 1) return detected
        if (!implausible(maxValue, capacityHint)) return detected ?: 1.0
        return known?.takeIf { it > 1 } ?: CANDIDATES.last()
    }

    /** A single value since the last charge above ten batteries' worth is not mAh. */
    fun implausible(maxValue: Double, capacityHint: Double?): Boolean =
        maxValue > 10 * (capacityHint?.takeIf { it in 1.0..MAX_CAPACITY_MAH } ?: 10_000.0)

    private fun nearest(ratio: Double): Double = CANDIDATES.minBy { abs(ln(ratio) - ln(it)) }
}
