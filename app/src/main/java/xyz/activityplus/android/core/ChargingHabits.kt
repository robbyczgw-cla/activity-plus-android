package xyz.activityplus.android.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val DAY_MILLIS = 86_400_000L

/** What the battery was doing in one stored minute. [charging] is the status: a full battery on the charger is not charging. */
data class HabitSample(val ts: Long, val level: Double, val tempC: Double, val charging: Boolean)

/** One stretch on the charger, from the saved battery sessions. */
data class PlugSpan(val startMillis: Long, val endMillis: Long, val startLevel: Double, val endLevel: Double)

/**
 * Charging habits over the last 30 days, and the ageing forecast from the measured full capacity.
 * Pure: the caller reads the minute samples, the sessions and the capacity estimates.
 */
object ChargingHabits {
    const val WINDOW_DAYS = 30

    /** Fewer days of minute samples and the per-day figures say more about the gaps than about the habit. */
    const val MIN_OBSERVED_DAYS = 3.0
    const val FULL_LEVEL = 0.95
    const val LOW_LEVEL = 0.10
    const val WARM_C = 35.0
    const val HOT_C = 40.0

    /** Charges shorter than this, or moving less than 5 points, are top-ups or plug blips; they do not describe a habit. */
    private const val MIN_CHARGE_MILLIS = 10 * 60_000L
    private const val MIN_CHARGE_STEP = 0.05
    const val MIN_CHARGES = 3

    /** Above this gap between two stored minutes the phone was off, not plugged in at the same level. */
    private const val MAX_GAP_MILLIS = DAY_MILLIS

    /** Thresholds for the advice: well above what a normal day does, so the advice only shows when it matters. */
    const val FULL_MIN_SECONDS = 2 * 3600.0
    const val HOT_MIN_SECONDS = 10 * 60.0
    const val WARM_MIN_SECONDS = 30 * 60.0
    const val DEEP_MIN_COUNT = 2

    /** Charging policies that already keep the battery below full; advice to switch them on would repeat them. */
    private val LIMITING = setOf(ChargingPolicy.LIMITED, ChargingPolicy.ADAPTIVE, ChargingPolicy.LONG_LIFE)

    const val MIN_MEASUREMENTS = 5
    const val MIN_SPAN_DAYS = 56.0
    /** The forecast says "80 %", the usual end of a battery's useful life. */
    const val REFERENCE_PERCENT = 80.0

    /** A slope counts as loss only when it is this many standard errors steeper than zero (about 95 % sure). */
    private const val SIGNIFICANCE = 2.0
    private const val DAYS_PER_MONTH = 30.44

    /**
     * Per-day figures over [observedDays] (at most [WINDOW_DAYS]). Times are seconds per day.
     * Typical levels are medians of the charges, in percent; null with fewer than [MIN_CHARGES] charges.
     */
    data class Summary(
        val observedDays: Double,
        val fullSecondsPerDay: Double,
        val warmSecondsPerDay: Double,
        val hotSecondsPerDay: Double,
        val deepDischarges: Int,
        val chargeCount: Int,
        val typicalStartPercent: Int?,
        val typicalEndPercent: Int?,
    )

    enum class Advice { HOT_CHARGING, WARM_CHARGING, FULL_WHILE_PLUGGED, DEEP_DISCHARGES }

    /** Where the ageing forecast stands. Only [Forecast] carries a number of months. */
    sealed interface Ageing {
        /** Not enough full-capacity measurements spread over enough weeks yet. */
        data class NotYet(val measurements: Int, val spanDays: Double) : Ageing

        /** The measured trend is within its own scatter: no clear loss yet. */
        data object NoClearLoss : Ageing

        /** Already at or below [REFERENCE_PERCENT]. */
        data class Reached(val percentNow: Double) : Ageing

        /**
         * [months] at the fitted pace, and the range from one standard error faster or slower.
         * [rated] says the percentages are of the rated capacity, else of the first measurement.
         */
        data class Forecast(
            val percentNow: Double,
            val months: Double,
            val monthsEarliest: Double,
            val monthsLatest: Double,
            val rated: Boolean,
        ) : Ageing
    }

    /**
     * Walks the samples in pairs: each stored minute counts until the next one (sample-and-hold), and
     * only when both belong to the same stretch on the charger, so a gap in between is not guessed at.
     * Null with less than [MIN_OBSERVED_DAYS] of samples.
     */
    fun summarize(samples: List<HabitSample>, spans: List<PlugSpan>): Summary? {
        val s = samples.sortedBy { it.ts }
        if (s.size < 2) return null
        val observed = (s.last().ts - s.first().ts) / DAY_MILLIS.toDouble()
        if (observed < MIN_OBSERVED_DAYS) return null
        val plugs = spans.sortedBy { it.startMillis }
        val spanOf = spanIndices(s, plugs)

        var full = 0.0
        var warm = 0.0
        var hot = 0.0
        var deep = 0
        for (i in 0 until s.size - 1) {
            val a = s[i]
            val b = s[i + 1]
            val gap = b.ts - a.ts
            if (gap <= 0) continue
            // Going under 10 % while on battery is one deep discharge; charging back up in between counts again.
            if (a.level >= LOW_LEVEL && b.level < LOW_LEVEL && spanOf[i + 1] < 0) deep++
            if (gap > MAX_GAP_MILLIS || spanOf[i] < 0 || spanOf[i] != spanOf[i + 1]) continue
            val seconds = gap / 1000.0
            if (a.level >= FULL_LEVEL) full += seconds
            if (a.charging) {
                if (a.tempC >= WARM_C) warm += seconds
                if (a.tempC >= HOT_C) hot += seconds
            }
        }

        val days = min(observed, WINDOW_DAYS.toDouble())
        // Top-ups and plug blips are not "a charge" for the typical range.
        val charges = plugs.filter {
            it.endMillis - it.startMillis >= MIN_CHARGE_MILLIS && it.endLevel - it.startLevel >= MIN_CHARGE_STEP
        }
        val typical = charges.takeIf { it.size >= MIN_CHARGES }
        return Summary(
            observedDays = days,
            fullSecondsPerDay = full / days,
            warmSecondsPerDay = warm / days,
            hotSecondsPerDay = hot / days,
            deepDischarges = deep,
            chargeCount = charges.size,
            typicalStartPercent = typical?.let { percent(median(it.map { c -> c.startLevel })) },
            typicalEndPercent = typical?.let { percent(median(it.map { c -> c.endLevel })) },
        )
    }

    /**
     * The advice that applies, hottest first. Advice on the top-up is dropped when the phone already
     * limits the charge (policy from the battery's property): telling it to do what it does is noise.
     */
    fun advice(summary: Summary, policy: ChargingPolicy?): List<Advice> = buildList {
        // Warm is the milder version of hot, so only one of the two shows.
        if (summary.hotSecondsPerDay >= HOT_MIN_SECONDS) add(Advice.HOT_CHARGING)
        else if (summary.warmSecondsPerDay >= WARM_MIN_SECONDS) add(Advice.WARM_CHARGING)
        if (summary.fullSecondsPerDay >= FULL_MIN_SECONDS && policy !in LIMITING) add(Advice.FULL_WHILE_PLUGGED)
        if (summary.deepDischarges >= DEEP_MIN_COUNT) add(Advice.DEEP_DISCHARGES)
    }

    /**
     * Fits a straight line through the full-capacity estimates (mAh over days) and reads off when it
     * reaches [REFERENCE_PERCENT] of the rated capacity, or of the first measurement without a rating.
     * A single odd charge moves a least-squares line a lot, which is why the minimum counts exist; the
     * range from the slope's standard error says how sure the figure is.
     */
    fun ageing(estimates: List<CapacityEstimate>, designMah: Double?): Ageing {
        val pts = estimates.sortedBy { it.timeMillis }
        val spanDays = if (pts.size < 2) 0.0 else (pts.last().timeMillis - pts.first().timeMillis) / DAY_MILLIS.toDouble()
        if (pts.size < MIN_MEASUREMENTS || spanDays < MIN_SPAN_DAYS) return Ageing.NotYet(pts.size, spanDays)

        val t0 = pts.first().timeMillis
        val x = pts.map { (it.timeMillis - t0) / DAY_MILLIS.toDouble() }
        val y = pts.map { it.mah }
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val slope = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx // mAh per day
        val intercept = my - slope * mx
        val ssr = x.indices.sumOf { val r = y[it] - (intercept + slope * x[it]); r * r }
        // Scatter of the points around the line tells how much the slope itself can be trusted.
        val se = sqrt(ssr / (x.size - 2) / sxx)
        if (-slope <= SIGNIFICANCE * se) return Ageing.NoClearLoss

        val rated = designMah?.takeIf { it > 0 }
        // The fitted first measurement stands in for the rated capacity when the battery does not report it.
        val reference = rated ?: intercept
        val now = (intercept + slope * x.last()) / reference * 100
        if (now <= REFERENCE_PERCENT) return Ageing.Reached(now)

        val rate = slope / reference * 100 // percent per day, negative
        val rateError = se / reference * 100
        fun monthsAt(r: Double) = (now - REFERENCE_PERCENT) / -r / DAYS_PER_MONTH
        val fast = monthsAt(rate - rateError)
        val slow = monthsAt(rate + rateError)
        return Ageing.Forecast(now, monthsAt(rate), min(fast, slow), max(fast, slow), rated != null)
    }

    /**
     * For each sample (sorted) the index of the plug span that holds it, or -1. Both lists are sorted by
     * time, so one pass with a moving pointer does it.
     */
    private fun spanIndices(samples: List<HabitSample>, spans: List<PlugSpan>): IntArray {
        val out = IntArray(samples.size) { -1 }
        var k = 0
        samples.forEachIndexed { i, s ->
            while (k < spans.size && spans[k].endMillis < s.ts) k++
            if (k < spans.size && spans[k].startMillis <= s.ts) out[i] = k
        }
        return out
    }

    private fun percent(fraction: Double): Int = Math.round(fraction * 100).toInt()

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
