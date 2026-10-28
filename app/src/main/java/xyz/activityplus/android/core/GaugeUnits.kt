package xyz.activityplus.android.core

import kotlin.math.abs

/**
 * The units this phone's fuel gauge reports in, learned once and kept. Android specifies µA for
 * the current and µAh for the charge counter; some vendors report mA and mAh. Deciding per reading
 * (as earlier versions did) misreads every small µA current: 4,000 µA (4 mA, a full battery on the
 * charger or a phone asleep) became 4,000 mA, about 15 W. A unit is a property of the phone, so it
 * is decided from readings only one unit can explain, and never changes afterwards.
 */
class GaugeUnits(currentMicro: Boolean? = null, counterMicro: Boolean? = null) {
    /** True: current in µA; false: mA; null: not known yet. */
    var currentMicro: Boolean? = currentMicro
        private set

    /** True: charge counter in µAh; false: mAh; null: not known yet. */
    var counterMicro: Boolean? = counterMicro
        private set

    private var milliCurrentSeen = 0
    private var milliCounterSeen = 0

    /**
     * Feeds one reading; true when a unit was decided by it (time to save). [screenOn] marks
     * readings where the phone certainly draws tens of mA or more.
     */
    fun observe(rawCurrent: Long?, rawCounter: Long?, screenOn: Boolean, levelFraction: Double, designMah: Double?): Boolean {
        var decided = false
        if (currentMicro == null && rawCurrent != null && valid(rawCurrent)) {
            val m = abs(rawCurrent)
            when {
                // No phone draws 20 A: this many can only be µA.
                m > MICRO_PROOF_CURRENT -> { currentMicro = true; decided = true }
                // With the screen on a phone draws at least ~50 mA: in µA that reads 50,000 or more. A
                // screen-on reading between 100 and 15,000, again and again, can only be mA.
                screenOn && m in 100..MAX_MILLI_CURRENT -> if (++milliCurrentSeen >= MILLI_EVIDENCE) { currentMicro = false; decided = true }
            }
        }
        if (counterMicro == null && rawCounter != null && rawCounter > 0 && rawCounter != Int.MIN_VALUE.toLong()) {
            when {
                // No phone battery holds 50 Ah.
                rawCounter > MICRO_PROOF_COUNTER -> { counterMicro = true; decided = true }
                // A third full or more, yet the number is below one and a half batteries: mAh.
                designMah != null && levelFraction >= 0.3 && rawCounter <= designMah * 1.5 ->
                    if (++milliCounterSeen >= MILLI_EVIDENCE) { counterMicro = false; decided = true }
            }
        }
        return decided
    }

    private fun valid(raw: Long) = raw != 0L && raw != Long.MIN_VALUE && raw != Int.MIN_VALUE.toLong()

    companion object {
        const val MICRO_PROOF_CURRENT = 20_000L
        const val MAX_MILLI_CURRENT = 15_000L
        const val MICRO_PROOF_COUNTER = 50_000L

        /** Readings that must agree before mA/mAh is believed (µA/µAh needs only one). */
        const val MILLI_EVIDENCE = 30
    }
}
