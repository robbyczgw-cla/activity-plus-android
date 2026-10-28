package xyz.activityplus.android.core

import kotlin.math.abs
import kotlin.math.ln

/**
 * Android's battery report states charge in mAh. Some phones do not: a vivo X300 on Android 16 wrote
 * "285078419" for YouTube after 20 hours, on a 6000 mAh battery. The report carries what is needed to
 * tell: the sum it computed against the charge the battery really lost (from the level, so in mAh),
 * and its idea of the battery's capacity.
 *
 * Detection only answers when the answer is unambiguous; a phone's unit is confirmed only after two
 * reports agree (see BackgroundStore.noteUnit), and nothing is ever converted on a guess. Pure, so it
 * is tested without a phone.
 */
object ChargeUnit {
    /**
     * Units a report could plausibly be in, as "this many per mAh": mAh itself, µAh, mC, and µC (the
     * unit of Android's measured-energy counters, the same factor as mA·ms).
     */
    val CANDIDATES = listOf(1.0, 1_000.0, 3_600.0, 3_600_000.0)

    /**
     * The battery must have lost at least this much, so the level's 1 % steps do not make the
     * drained figure (and the ratio) too coarse to tell µAh from mC.
     */
    const val MIN_DRAINED_MAH = 20.0

    /** No phone battery comes near this; a capacity beyond it is in some other unit. */
    private const val MAX_CAPACITY_MAH = 50_000.0

    /**
     * Units per mAh from the report itself, or null when it does not say so clearly:
     * - its computed sum against the charge drained, when the battery lost enough and the report's
     *   drained range is narrow (apps never cover exactly all of it, so the ratio is only near a unit);
     * - else its capacity against the real one ([capacityHint], from Android's battery service).
     */
    fun detect(computed: Double?, drainedLow: Double?, drainedHigh: Double?, reportCapacity: Double?, capacityHint: Double?): Double? {
        if (computed != null && computed > 0 && drainedLow != null && drainedHigh != null && drainedHigh >= drainedLow) {
            val mid = (drainedLow + drainedHigh) / 2
            if (mid >= MIN_DRAINED_MAH && drainedHigh - drainedLow <= mid) return unambiguous(computed / mid)
        }
        if (reportCapacity != null && capacityHint != null && capacityHint > 0 && reportCapacity > MAX_CAPACITY_MAH) {
            return unambiguous(reportCapacity / capacityHint)
        }
        return null
    }

    /** A single value since the last charge above ten batteries' worth is not mAh. */
    fun implausible(maxValue: Double, capacityHint: Double?): Boolean =
        maxValue > 10 * (capacityHint?.takeIf { it in 1.0..MAX_CAPACITY_MAH } ?: 10_000.0)

    /**
     * The candidate [ratio] points to, only when it is clearly closer to that one than to any other
     * (in orders of magnitude): between µAh and mC (3.6 apart) a ratio of ~1900 says nothing.
     */
    internal fun unambiguous(ratio: Double): Double? {
        if (ratio <= 0 || ratio.isNaN() || ratio.isInfinite()) return null
        val byDistance = CANDIDATES.sortedBy { abs(ln(ratio / it)) }
        val best = abs(ln(ratio / byDistance[0]))
        val next = abs(ln(ratio / byDistance[1]))
        return byDistance[0].takeIf { best * 2 < next }
    }
}
