package xyz.activityplus.android.core

import xyz.activityplus.android.core.UpdateDetect.Event
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * "Since the update": what an app did in the days before an update against the days after it.
 * An update is a common reason for sudden changes, but Android shows neither the old numbers nor
 * the update time next to them. Here the per-day numbers Activity+ already keeps are compared as
 * medians, which one busy weekend or one odd day cannot move, and only a clear change counts:
 * at least twice as much (or half), by a minimum absolute size, on most of the days after, and
 * outside everything seen on the days before. No cause is claimed; an update is only the most
 * likely suspect, and the sentence says "since", not "because".
 */
object SinceUpdate {
    /** [minDelta] is the smallest difference that matters, in the metric's unit (see [Change]). */
    enum class Metric(val minDelta: Double) {
        /** mAh a day, with the computer grant (BackgroundStore). */
        BACKGROUND(5.0),
        /** Alarms that woke the phone, a day (WakeupStore, BATTERY_STATS). */
        WAKEUPS(40.0),
        /** Partial wakelock milliseconds a day. */
        WAKELOCK(600_000.0),
        /** mWh per hour on screen (DrainTracker's app_energy), pooled over the windows. */
        SCREEN_ENERGY(500.0),
        /** Bytes a day. */
        MOBILE(15_000_000.0),
        WIFI(50_000_000.0),
        /** Bytes of app and data, one snapshot before against one after (StorageGrowthStore). */
        STORAGE(300_000_000.0),
    }

    /** The metrics with one value per day. */
    val DAILY = listOf(Metric.BACKGROUND, Metric.WAKEUPS, Metric.WAKELOCK, Metric.MOBILE, Metric.WIFI)

    /** Metrics that drain the battery while the phone is not in use: an increase there is a warning, not a note. */
    val WHILE_ASLEEP = setOf(Metric.BACKGROUND, Metric.WAKEUPS, Metric.WAKELOCK)

    enum class Direction { UP, DOWN }

    /** Days before the update that count, and the most days after it. The update day itself is mixed and never counts. */
    const val BEFORE_DAYS = 7
    const val AFTER_DAYS = 14
    /** Fewer days on either side say too little: one day is an accident, three are a pattern. */
    const val MIN_DAYS = 3
    /** Updates older than this have lost their "before" days (the stores keep 30). */
    const val RECENT_DAYS = 21
    const val FACTOR = 2.0
    private const val STORAGE_FACTOR = 1.5
    /** Pooled energy needs this much time on screen on each side, or one short session decides. */
    const val MIN_SCREEN_SECONDS = 20 * 60
    /** A storage snapshot right after the update still shows the old data; later ones show what the app did since. */
    private const val STORAGE_SETTLE_DAYS = 2L
    private const val STORAGE_BEFORE_DAYS = 10L
    /** A change counts as "strong" (diagnosis) from this factor, or when there was almost nothing before. */
    const val STRONG_FACTOR = 3.0

    /** A "before" below this share of the metric's minimum change counts as nothing (see [Change.ratio]). */
    const val NOISE_SHARE = 0.1

    /** Increases beyond this are told as the new amount, not as a factor nobody can picture. */
    const val MAX_FACTOR = 20.0

    private const val DAY_MS = 86_400_000L

    /** [before] and [after] are in the metric's unit: a median per day, or per hour for [Metric.SCREEN_ENERGY]. */
    data class Change(val metric: Metric, val direction: Direction, val before: Double, val after: Double) {
        /**
         * After over before; null when there was next to nothing before, i.e. the app is new at this.
         * Android reports idle apps with crumbs like 0.000002 mAh a day; a factor over such a crumb
         * ("8066270× as much") is true arithmetic and no use to anyone, so below [NOISE_SHARE] of the
         * metric's minimum, and for increases beyond [MAX_FACTOR], the text names the amount instead.
         */
        val ratio: Double? get() {
            if (before < metric.minDelta * NOISE_SHARE) return null
            val r = after / before
            return if (direction == Direction.UP && r > MAX_FACTOR) null else r
        }

        val strong get() = direction == Direction.UP && (ratio?.let { it >= STRONG_FACTOR } ?: true)

        /** Orders by how far it went: an increase from nothing is the furthest. */
        internal val magnitude: Double
            get() {
                val r = ratio ?: return Double.MAX_VALUE
                return if (direction == Direction.UP) r else if (r > 0) 1 / r else Double.MAX_VALUE
            }
    }

    sealed interface Verdict {
        /** Too few days with a reading before or after. */
        object NotEnough : Verdict
        object NoChange : Verdict
        data class Changed(val change: Change) : Verdict
    }

    /** The day keys (yyyy-MM-dd, oldest first) to compare for one update. */
    data class Windows(val before: List<String>, val after: List<String>)

    /**
     * [update] is the day of the update, [previous] and [next] the days of the same app's other
     * updates, which end the windows: numbers from another version belong to another comparison.
     * Today is partial and never counts.
     */
    fun windows(update: LocalDate, today: LocalDate, previous: LocalDate?, next: LocalDate?): Windows {
        val before = (BEFORE_DAYS downTo 1).map { update.minusDays(it.toLong()) }
            .filter { previous == null || it > previous }
        val lastAfter = listOfNotNull(update.plusDays(AFTER_DAYS.toLong()), today.minusDays(1), next?.minusDays(1))
            .minOf { it.toEpochDay() }
        val after = generateSequence(update.plusDays(1)) { it.plusDays(1) }.takeWhile { it.toEpochDay() <= lastAfter }
        return Windows(before.map { it.toString() }, after.map { it.toString() }.toList())
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        if (s.isEmpty()) return 0.0
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Whether [after] against [before] is at least [factor] times more or less, and by at least the metric's minimum. */
    private fun decide(metric: Metric, before: Double, after: Double, factor: Double = FACTOR): Direction? = when {
        after - before >= metric.minDelta && after >= factor * before -> Direction.UP
        before - after >= metric.minDelta && before >= factor * after -> Direction.DOWN
        else -> null
    }

    /**
     * One value per day in [series] (only days with a reading; a day without a row for an app that
     * was measured counts as 0 and must be filled in by the caller). On top of the factor and the
     * minimum, most of the days after must individually be on the new side, and the median after
     * must lie outside everything seen on the days before, so a naturally jumpy app does not flag.
     */
    fun compareDaily(metric: Metric, series: Map<String, Double>, windows: Windows): Verdict {
        val before = windows.before.mapNotNull { series[it] }
        val after = windows.after.mapNotNull { series[it] }
        if (before.size < MIN_DAYS || after.size < MIN_DAYS) return Verdict.NotEnough
        val b = median(before)
        val a = median(after)
        val direction = decide(metric, b, a) ?: return Verdict.NoChange
        val half = metric.minDelta / 2
        val outside = if (direction == Direction.UP) a > before.max() else a < before.min()
        val agreeing = after.count {
            if (direction == Direction.UP) it - b >= half && it >= 1.5 * b else b - it >= half && it * 1.5 <= b
        }
        // At least two thirds of the days after.
        return if (outside && agreeing * 3 >= after.size * 2) Verdict.Changed(Change(metric, direction, b, a)) else Verdict.NoChange
    }

    /** Energy and time on screen summed over the windows, from the per-day app_energy rows. */
    data class Energy(val beforeMwh: Double, val beforeSeconds: Double, val afterMwh: Double, val afterSeconds: Double)

    /** mWh per hour on screen before against after; pooled, because the per-day seconds are small and uneven. */
    fun compareEnergy(e: Energy): Verdict {
        if (e.beforeSeconds < MIN_SCREEN_SECONDS || e.afterSeconds < MIN_SCREEN_SECONDS) return Verdict.NotEnough
        val b = e.beforeMwh / (e.beforeSeconds / 3600)
        val a = e.afterMwh / (e.afterSeconds / 3600)
        val direction = decide(Metric.SCREEN_ENERGY, b, a) ?: return Verdict.NoChange
        return Verdict.Changed(Change(Metric.SCREEN_ENERGY, direction, b, a))
    }

    /** One storage snapshot of an app: when, and bytes of app plus data (the cache comes and goes by itself). */
    data class StorageAt(val timeMillis: Long, val bytes: Long)

    /**
     * The last snapshot before the update (up to 10 days back) and the last one from two days after
     * it until the next update or 14 days; null if either is missing.
     */
    fun pickStorage(snapshots: List<StorageAt>, updateMillis: Long, nextMillis: Long?): Pair<Long, Long>? {
        val before = snapshots.filter { it.timeMillis < updateMillis && it.timeMillis >= updateMillis - STORAGE_BEFORE_DAYS * DAY_MS }
            .maxByOrNull { it.timeMillis } ?: return null
        val limit = minOf(nextMillis ?: Long.MAX_VALUE, updateMillis + AFTER_DAYS * DAY_MS + DAY_MS)
        val after = snapshots.filter { it.timeMillis >= updateMillis + STORAGE_SETTLE_DAYS * DAY_MS && it.timeMillis < limit }
            .maxByOrNull { it.timeMillis } ?: return null
        return before.bytes to after.bytes
    }

    /** Only growth is reported: a smaller app after an update is not news. */
    fun compareStorage(beforeBytes: Long, afterBytes: Long): Verdict {
        val change = Change(Metric.STORAGE, Direction.UP, beforeBytes.toDouble(), afterBytes.toDouble())
        return if (decide(Metric.STORAGE, change.before, change.after, STORAGE_FACTOR) == Direction.UP) Verdict.Changed(change) else Verdict.NoChange
    }

    /**
     * The outcome for one update. [evaluated] is how many metrics had enough days to be judged;
     * 0 means "too early or not measured", which is not the same as "no change".
     */
    data class Result(val update: Event, val changes: List<Change>, val evaluated: Int, val afterDays: Int) {
        val enough get() = evaluated > 0
        val clear get() = changes.isNotEmpty()

        /** The change that went furthest in the wrong direction, for the diagnosis; null if none is strong. */
        val strongest: Change? get() = changes.filter { it.strong }.maxByOrNull { it.magnitude }
    }

    fun evaluate(
        update: Event,
        windows: Windows,
        daily: Map<Metric, Map<String, Double>>,
        energy: Energy?,
        storage: Pair<Long, Long>?,
    ): Result {
        val verdicts = ArrayList<Verdict>()
        for (m in DAILY) daily[m]?.let { verdicts += compareDaily(m, it, windows) }
        energy?.let { verdicts += compareEnergy(it) }
        storage?.let { verdicts += compareStorage(it.first, it.second) }
        val changes = verdicts.filterIsInstance<Verdict.Changed>().map { it.change }
            // Increases first (they are what people look for), the furthest first.
            .sortedWith(compareBy<Change> { it.direction }.thenByDescending { it.magnitude })
        return Result(update, changes, verdicts.count { it !is Verdict.NotEnough }, windows.after.size)
    }

    /** "4×", or "2.5×" below 3, where the decimal still says something. */
    fun times(ratio: Double): String {
        val r = if (ratio < 3) (ratio * 10).roundToLong() / 10.0 else ratio.roundToLong().toDouble()
        return Format.number(r, if (r % 1.0 == 0.0) 0 else 1) + "×"
    }

    /** The times of the updates of one package that come right before and after [event]; null at the ends. */
    fun neighbours(times: List<Long>, event: Long): Pair<Long?, Long?> =
        times.filter { it < event }.maxOrNull() to times.filter { it > event }.minOrNull()
}
