package xyz.activityplus.android.core

import xyz.activityplus.android.pro.BackgroundDelta
import java.time.ZoneId

/**
 * What Activity+ costs itself. Android's counters for the app's own uid (SystemHealthManager, no
 * permission needed for the own uid) turned into what grew between two readings, per day. The
 * counters work like those of [Wakeups]: cumulative since Android's last reset, and only while the
 * phone runs on battery; a counter that went down means Android started again.
 * Energy is not counted here: Android shows it only to the computer grant (see [energy]).
 */
object SelfCost {
    /** One reading's counters, or what they grew by between two readings. */
    data class Counters(
        /** User + system CPU time of all Activity+ processes. */
        val cpuMs: Long = 0,
        /** Partial wakelocks: time Activity+ kept the CPU running. */
        val wakelockMs: Long = 0,
        val wakelocks: Long = 0,
        /** Alarms of Activity+ that pulled the phone out of sleep. */
        val wakeups: Long = 0,
        val jobs: Long = 0,
        /** Mobile + Wi-Fi bytes; Activity+ has no INTERNET permission, so this should stay 0. */
        val dataBytes: Long = 0,
        /** Time the process ran as a foreground service (the live status), the background it is in. */
        val serviceMs: Long = 0,
    ) {
        internal fun toArray() = longArrayOf(cpuMs, wakelockMs, wakelocks, wakeups, jobs, dataBytes, serviceMs)

        operator fun plus(o: Counters) = zip(o) { a, b -> a + b }

        val isZero get() = this == ZERO

        internal fun zip(o: Counters, f: (Long, Long) -> Long): Counters {
            val a = toArray()
            val b = o.toArray()
            return of(LongArray(a.size) { f(a[it], b[it]) })
        }

        companion object {
            val ZERO = Counters()
            const val FIELDS = 7

            fun of(f: LongArray) = Counters(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
        }
    }

    data class Reading(
        val timeMillis: Long,
        /** Time on battery since Android's last reset; null when the phone did not say. */
        val batteryMs: Long?,
        val counters: Counters,
    )

    data class Interval(val fromMillis: Long, val toMillis: Long, val reset: Boolean, val counters: Counters)

    /** What a counter grew by; a smaller value means Android started again, so the new value is all there is. */
    fun growth(prev: Counters, next: Counters): Counters = next.zip(prev) { n, p -> if (n < p) n else n - p }

    /**
     * Whether Android reset its counters between two readings: less time on battery says so directly;
     * otherwise clearly less CPU time. Same rule as [Wakeups.isReset], applied to the one uid.
     */
    fun isReset(prev: Reading, next: Reading): Boolean {
        fun asWakeups(r: Reading) = Wakeups.Snapshot(r.timeMillis, r.batteryMs, mapOf(0 to Wakeups.Counters(cpuMs = r.counters.cpuMs)))
        return Wakeups.isReset(asWakeups(prev), asWakeups(next))
    }

    /**
     * What happened up to [next]. Without a previous reading the counters themselves are the answer: they
     * count from Android's last reset, which [Reading.batteryMs] dates, so a first look is not empty. Null
     * when that is not known either (then [next] is only the base for the next interval) or time went back.
     */
    fun interval(prev: Reading?, next: Reading): Interval? {
        if (prev == null) {
            val battery = next.batteryMs ?: return null
            val from = (next.timeMillis - battery).coerceAtMost(next.timeMillis)
            return Interval(from, next.timeMillis, reset = true, next.counters.capWakelock(next.timeMillis - from))
        }
        if (next.timeMillis <= prev.timeMillis) return null
        val reset = isReset(prev, next)
        // After a reset the counters cover at most the time on battery since, not the whole gap.
        val from = if (reset && next.batteryMs != null) {
            (next.timeMillis - next.batteryMs).coerceIn(prev.timeMillis, next.timeMillis)
        } else prev.timeMillis
        val span = next.timeMillis - from
        val g = if (reset) next.counters else growth(prev.counters, next.counters)
        return Interval(from, next.timeMillis, reset, g.capWakelock(span))
    }

    /** Two wakelocks at once do not keep the phone awake twice. */
    private fun Counters.capWakelock(spanMs: Long) = copy(wakelockMs = wakelockMs.coerceAtMost(spanMs))

    /**
     * Splits [c] by [shares] (summing to 1) so the parts add up to exactly [c]: each part is the rounded
     * running total minus the one before, so no wakeup gets lost to rounding.
     */
    fun split(c: Counters, shares: List<Double>): List<Counters> {
        val total = c.toArray()
        val done = LongArray(Counters.FIELDS)
        var cumulative = 0.0
        return shares.mapIndexed { i, s ->
            cumulative += s
            val last = i == shares.lastIndex
            Counters.of(LongArray(Counters.FIELDS) { f ->
                val upTo = if (last) total[f] else Math.round(total[f] * cumulative.coerceAtMost(1.0))
                (upTo - done[f]).also { done[f] = upTo }
            })
        }
    }

    /**
     * The interval per local day ("yyyy-MM-dd"), spread evenly over its time. Every day it touches gets an
     * entry, also an all-zero one: that is how "Activity+ did nothing" differs from "not measured".
     */
    fun byDay(interval: Interval, zone: ZoneId): Map<String, Counters> {
        val days = BackgroundDelta.splitByDay(interval.fromMillis, interval.toMillis, zone)
        val out = LinkedHashMap<String, Counters>()
        split(interval.counters, days.map { it.second }).forEachIndexed { i, part -> out[days[i].first] = part }
        return out
    }

    /** The sum over [days] and how many of them have a reading at all. */
    data class Sum(val counters: Counters, val measuredDays: Int)

    fun sum(series: Map<String, Counters>, days: List<String>): Sum {
        val measured = days.filter { it in series }
        return Sum(measured.fold(Counters.ZERO) { acc, d -> acc + series.getValue(d) }, measured.size)
    }

    // When to read

    const val PERIOD_MS = 3_600_000L

    /**
     * Whether the hourly reading is due on a service tick. The first one is due at once. Android does not
     * count while charging, so a charging phone is not asked, except right when the plug changed: a charge
     * is what resets the counters, and a reading just before keeps what happened until then.
     */
    fun due(nowMillis: Long, lastMillis: Long?, plugged: Boolean, plugChanged: Boolean): Boolean {
        if (lastMillis == null) return true
        if (plugChanged) return true
        return !plugged && nowMillis - lastMillis >= PERIOD_MS
    }

    /**
     * Whether a heavy report (hundreds of kB of system text, a scan of all apps) may run now. Never in Doze
     * (the phone is in a maintenance window and the report would extend it). On screen or charging, any time
     * it is due. Screen off on battery, only when it is more than a whole period late: the work waits for the
     * next screen-on, where it also costs the phone nothing extra to be awake. [sinceLastMs] null: unknown,
     * which waits as well.
     */
    fun mayRunHeavy(screenOn: Boolean, plugged: Boolean, doze: Boolean, sinceLastMs: Long?, periodMs: Long): Boolean {
        if (doze) return false
        if (screenOn || plugged) return true
        return sinceLastMs != null && sinceLastMs >= periodMs * OVERDUE_PERIODS
    }

    const val OVERDUE_PERIODS = 2

    // What to show

    /** Android's own estimate for Activity+, from the battery report that only the computer grant can read. */
    sealed interface Energy {
        data class Known(val mah: Double, val percentOfCharge: Double?) : Energy
        /** No computer grant: Android keeps energy per app to itself. */
        data object NeedsGrant : Energy
        /** The grant is there, but the automatic measurement is off. */
        data object SettingOff : Energy
        /** Measurement on, but no battery report with Activity+ in it yet. */
        data object NotYet : Energy
    }

    /** [measuredDays]: days of the period that have a battery report; [mah] their sum for Activity+. */
    fun energy(hasGrant: Boolean, measureOn: Boolean, measuredDays: Int, mah: Double, capacityMah: Double?): Energy = when {
        !hasGrant -> Energy.NeedsGrant
        !measureOn -> Energy.SettingOff
        measuredDays == 0 -> Energy.NotYet
        else -> Energy.Known(mah, capacityMah?.takeIf { it > 0 }?.let { mah / it * 100 })
    }

    /** CPU time is mostly milliseconds to seconds: "<1 s" instead of a misleading "0 s" when something ran. */
    fun durationText(ms: Long): String = when {
        ms <= 0 -> Format.duration(0)
        ms < 1000 -> "<1 s"
        else -> Format.duration(ms / 1000)
    }

    /** mAh with the precision that matters at this size; tiny values are not shown as 0. */
    fun mahText(mah: Double): String = when {
        mah <= 0 -> "0 mAh"
        mah < 0.1 -> "<0.1 mAh"
        mah < 10 -> Format.number(mah, 1) + " mAh"
        else -> Format.number(mah, 0) + " mAh"
    }

    fun percentText(percent: Double): String = if (percent < 0.1) "<0.1 %" else Format.number(percent, 1) + " %"

    // What runs in the background

    /** Everything Activity+ does on its own, in the order of the card. Each one is described where it lives. */
    enum class Job { MONITOR, WAKEUPS, SENSORS, POWER_RAILS, BACKGROUND, UPDATES, STORAGE, THERMAL, NIGHT, SELF_COST, WEEKLY, WIDGETS }

    /** What a job needs besides the live status; [NONE] means it runs. */
    enum class Need { NONE, LIVE, BATTERY_STATS, COMPUTER_GRANT, BACKGROUND_SETTING, UNSUPPORTED, WEEKLY_SETTING, NO_WIDGETS }

    data class Env(
        val live: Boolean,
        val batteryStats: Boolean,
        val computerGrant: Boolean,
        val backgroundMeasure: Boolean,
        /** Android 15+ with at least one power monitor. */
        val powerMonitors: Boolean,
        /** Alerts and the weekly report both on. */
        val weekly: Boolean,
        /** At least one home screen widget of Activity+. */
        val widgets: Boolean,
    )

    fun need(job: Job, env: Env): Need = when {
        // Android refreshes widgets itself (every 30 minutes at most), with or without the service.
        job == Job.WIDGETS -> if (env.widgets) Need.NONE else Need.NO_WIDGETS
        !env.live -> Need.LIVE
        else -> when (job) {
            Job.WAKEUPS -> if (env.batteryStats) Need.NONE else Need.BATTERY_STATS
            Job.SENSORS -> if (env.computerGrant) Need.NONE else Need.COMPUTER_GRANT
            Job.POWER_RAILS -> if (env.powerMonitors) Need.NONE else Need.UNSUPPORTED
            Job.BACKGROUND -> when {
                !env.computerGrant -> Need.COMPUTER_GRANT
                !env.backgroundMeasure -> Need.BACKGROUND_SETTING
                else -> Need.NONE
            }
            Job.WEEKLY -> if (env.weekly) Need.NONE else Need.WEEKLY_SETTING
            Job.MONITOR, Job.UPDATES, Job.STORAGE, Job.THERMAL, Job.NIGHT, Job.SELF_COST, Job.WIDGETS -> Need.NONE
        }
    }
}
