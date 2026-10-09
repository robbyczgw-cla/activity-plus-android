package xyz.activityplus.android.core

enum class ChargeAlarmKind { LIMIT, FULL, WARM }

/**
 * Decides when a charging alarm fires, once per plug-in. Pure: the service feeds it one sample at
 * a time and shows the notifications for what comes back. Events are tracked even when their
 * alarm is off, so switching one on mid-charge does not fire for something that already happened.
 */
class ChargeAlarmLogic(
    private val warmC: Double = 40.0,
    private val warmMillis: Long = 2 * 60_000L,
    /** A longer gap between samples (the phone slept) breaks the "warm for two minutes" streak. */
    private val maxGapMillis: Long = 5 * 60_000L,
) {
    private var prevPercent = -1
    private var lastTime = 0L
    private var sawCharging = false
    private var limitDone = false
    private var fullDone = false
    private var warmSince = NONE
    private var warmDone = false

    /** [limitPercent] 0 means off. */
    @Synchronized
    fun onSample(
        now: Long, plugged: Boolean, status: ChargeStatus, level: Double, tempC: Double,
        limitPercent: Int, fullAlarm: Boolean, warmAlarm: Boolean,
    ): List<ChargeAlarmKind> {
        if (!plugged) {
            reset()
            return emptyList()
        }
        val out = ArrayList<ChargeAlarmKind>(1)
        val percent = Math.round(level * 100).toInt()
        if (status == ChargeStatus.CHARGING) sawCharging = true

        // Only a rise through the limit counts; plugging in above it is not "reached".
        if (!limitDone && limitPercent > 0 && prevPercent in 0 until limitPercent && percent >= limitPercent) {
            limitDone = true
            out += ChargeAlarmKind.LIMIT
        }
        prevPercent = percent

        // A phone that is plugged in at 100 % never charged this time, so there is nothing to announce.
        if (!fullDone && sawCharging && (status == ChargeStatus.FULL || percent >= 100)) {
            fullDone = true
            if (fullAlarm) out += ChargeAlarmKind.FULL
        }

        if (now - lastTime > maxGapMillis) warmSince = NONE
        lastTime = now
        if (tempC >= warmC) {
            if (warmSince == NONE) warmSince = now
            if (!warmDone && now - warmSince >= warmMillis) {
                warmDone = true
                if (warmAlarm) out += ChargeAlarmKind.WARM
            }
        } else {
            warmSince = NONE
            // A little hysteresis, so 39.9/40.0 flapping does not alarm over and over.
            if (tempC < warmC - 2) warmDone = false
        }
        return out
    }

    private fun reset() {
        prevPercent = -1
        sawCharging = false
        limitDone = false
        fullDone = false
        warmSince = NONE
        warmDone = false
        lastTime = 0L
    }

    private companion object {
        const val NONE = -1L
    }
}
