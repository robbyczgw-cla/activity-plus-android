package xyz.activityplus.android.core

import xyz.activityplus.android.core.Wakeups.Counters

/**
 * Google's own limit for partial wakelocks. Google Play flags an app as an "excessive partial wake
 * lock" app when it holds a non-exempt partial wakelock for 2 hours or more with the screen off in
 * more than 5 % of its sessions over 28 days (audio playback, location callbacks and user-initiated
 * transfers are exempt). Here: the same test on the per-day wakelock time Activity+ already keeps.
 *
 * It is an approximation, and Android does not let an app see more: Google counts a "session" with
 * the screen off, but the stored value is the app's partial wakelock time per day on battery, which
 * also covers minutes with the screen on, and the exemptions are not visible. A day is the finest
 * split the counters allow honestly (an interval spans hours and carries no screen state), so a day
 * stands for a session. A flag here means "worth Google's attention", not "Google flags it".
 * The stored values are already capped per interval, so overlapping wakelocks do not count twice.
 */
object WakeLimit {
    /** A day at or above this counts as above the limit. */
    const val LIMIT_MS = 2 * 3_600_000L
    const val WINDOW_DAYS = 28
    /** Fewer measured days say too little about "regularly": one long night would flag a new install. */
    const val MIN_MEASURED_DAYS = 7

    data class Verdict(
        val pkg: String,
        val exceededDays: Int,
        val measuredDays: Int,
        /** The longest day, to show how far above. */
        val worstMs: Long,
    ) {
        val regularly get() = regularly(exceededDays, measuredDays)
    }

    fun exceeds(wakelockMs: Long) = wakelockMs >= LIMIT_MS

    /** More than 5 % of the measured days, given enough days. Integer maths: 20 days make 1 day exactly 5 %, which is not more. */
    fun regularly(exceededDays: Int, measuredDays: Int) =
        measuredDays >= MIN_MEASURED_DAYS && exceededDays * 20 > measuredDays

    /**
     * The [days] with a reading: any app with counters that day. A day without a row for an app is a
     * day it did nothing, but only when the phone was measured at all (service off, charging all day).
     */
    fun measuredDays(all: Map<String, Map<String, Counters>>, days: List<String>): Set<String> {
        val window = days.toSet()
        return all.values.flatMapTo(HashSet()) { it.keys }.filterTo(HashSet()) { it in window }
    }

    /**
     * Per app that was above the limit on at least one of [days] (oldest first, the last 28 count).
     * [all] is every app's counters per day. Apps that never reached it are left out.
     */
    fun verdicts(all: Map<String, Map<String, Counters>>, days: List<String>): Map<String, Verdict> {
        val window = days.takeLast(WINDOW_DAYS)
        val measured = measuredDays(all, window)
        val out = LinkedHashMap<String, Verdict>()
        for ((pkg, series) in all) {
            val wakelocks = window.filter { it in measured }.map { series[it]?.wakelockMs ?: 0L }
            val exceeded = wakelocks.count { exceeds(it) }
            if (exceeded > 0) out[pkg] = Verdict(pkg, exceeded, measured.size, wakelocks.maxOrNull() ?: 0L)
        }
        return out
    }

    /** The apps Google would look at, worst first: most days above the limit, then the longest day. */
    fun flagged(all: Map<String, Map<String, Counters>>, days: List<String>): List<Verdict> =
        verdicts(all, days).values.filter { it.regularly }
            .sortedWith(compareByDescending<Verdict> { it.exceededDays }.thenByDescending { it.worstMs }.thenBy { it.pkg })
}
