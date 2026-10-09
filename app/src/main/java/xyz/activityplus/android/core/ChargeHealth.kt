package xyz.activityplus.android.core

import kotlin.math.abs

/** Full capacity estimated from one finished charge. */
data class CapacityEstimate(val timeMillis: Long, val mah: Double, val levelFrom: Double, val levelTo: Double)

/** Mean of the voltage while charging, so the energy of a session can be turned into mAh. */
class VoltageAverage {
    private var sum = 0.0
    private var count = 0

    @Synchronized fun add(volts: Double) { if (volts > 0) { sum += volts; count++ } }
    @Synchronized fun mean(): Double? = if (count > 0) sum / count else null
    @Synchronized fun reset() { sum = 0.0; count = 0 }
}

object ChargeHealth {
    /** Below this the level steps (whole percent) and the gauge noise make the estimate useless. */
    const val MIN_LEVEL_CHANGE = 0.3

    /**
     * mAh charged = energy / average voltage; capacity = mAh charged / level change.
     * Null for sessions that cannot say much (short, no current readings) and for results that
     * cannot be a real battery: under 40 % or over 130 % of the rated capacity, when that is known.
     */
    fun estimate(session: BatterySession, avgVoltageV: Double?, designMah: Double?): CapacityEstimate? {
        if (!session.charging) return null
        if (session.levelChange < MIN_LEVEL_CHANGE - 1e-9) return null
        if (session.energyMwh <= 0 || avgVoltageV == null || avgVoltageV <= 0) return null
        val mah = session.energyMwh / avgVoltageV / session.levelChange
        if (!mah.isFinite() || mah <= 0) return null
        if (designMah != null && designMah > 0 && (mah < designMah * 0.4 || mah > designMah * 1.3)) return null
        return CapacityEstimate(session.endMillis, mah, session.startLevel, session.endLevel)
    }

    /** Change of the full capacity since the first measurements. */
    data class Trend(val firstMillis: Long, val firstMah: Double, val latestMah: Double, val count: Int) {
        /** -0.03 for three percent less than at the start. */
        val change get() = latestMah / firstMah - 1
    }

    /**
     * Medians of the first and the latest three, so one odd charge does not move the line; with
     * fewer than six measurements the two groups shrink so they never share one. Null below two.
     */
    fun trend(estimates: List<CapacityEstimate>): Trend? {
        if (estimates.size < 2) return null
        val sorted = estimates.sortedBy { it.timeMillis }
        val k = minOf(3, sorted.size / 2)
        val first = median(sorted.take(k).map { it.mah })
        val latest = median(sorted.takeLast(k).map { it.mah })
        if (first <= 0) return null
        return Trend(sorted.first().timeMillis, first, latest, sorted.size)
    }

    /** "−3 %", "+2 %", "0 %" with a real minus sign. */
    fun signedPercent(change: Double): String {
        val p = Math.round(change * 100).toInt()
        return when {
            p > 0 -> "+$p %"
            p < 0 -> "−${abs(p)} %"
            else -> "0 %"
        }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
