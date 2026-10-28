package xyz.activityplus.android.core

import xyz.activityplus.android.pro.BackgroundDelta
import xyz.activityplus.android.pro.BackgroundSchedule
import java.time.Instant
import java.time.ZoneId

/**
 * "Who woke the phone": Android's per-app counters (SystemHealthManager, read with BATTERY_STATS)
 * turned into what each app did between two readings, per day and per night, and which app to
 * look at first. Android counts from its last reset (a charge or an unplug) and only on battery.
 */
object Wakeups {
    /** One uid's counters since Android's reset, or what they grew by between two readings. */
    data class Counters(
        val cpuMs: Long = 0,
        /** Partial wakelocks: the app kept the CPU running with the screen off. */
        val wakelockMs: Long = 0,
        val wakelocks: Long = 0,
        /** Alarms that pulled the phone out of sleep. */
        val wakeups: Long = 0,
        val jobs: Long = 0,
        val jobMs: Long = 0,
        val syncs: Long = 0,
        val gpsMs: Long = 0,
    ) {
        internal fun toArray() = longArrayOf(cpuMs, wakelockMs, wakelocks, wakeups, jobs, jobMs, syncs, gpsMs)

        operator fun plus(o: Counters) = zip(o) { a, b -> a + b }

        val isZero get() = this == ZERO

        internal fun zip(o: Counters, f: (Long, Long) -> Long): Counters {
            val a = toArray()
            val b = o.toArray()
            return of(LongArray(a.size) { f(a[it], b[it]) })
        }

        companion object {
            val ZERO = Counters()
            const val FIELDS = 8

            fun of(f: LongArray) = Counters(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
        }
    }

    data class Snapshot(
        val timeMillis: Long,
        /** Time on battery since Android's last reset; null when the phone did not say. */
        val batteryMs: Long?,
        val byUid: Map<Int, Counters>,
    )

    data class AppCounters(val pkg: String, val counters: Counters)

    data class Interval(
        val fromMillis: Long,
        val toMillis: Long,
        /** Android had reset its counters, so the new reading is the whole interval. */
        val reset: Boolean,
        val apps: List<AppCounters>,
    )

    /**
     * What one counter set grew by. A counter that went down means Android started again in between,
     * so its new value is what happened since then; a uid without an earlier reading is new.
     */
    fun growth(prev: Counters?, next: Counters): Counters =
        if (prev == null) next else next.zip(prev) { n, p -> if (n < p) n else n - p }

    /**
     * Whether Android reset all counters between two readings: less time on battery says so directly;
     * otherwise clearly less CPU time over the uids both readings have (uninstalled apps do not count).
     */
    fun isReset(prev: Snapshot, next: Snapshot): Boolean {
        val pb = prev.batteryMs
        val nb = next.batteryMs
        if (pb != null && nb != null && nb < pb) return true
        val common = prev.byUid.keys.intersect(next.byUid.keys)
        val before = common.sumOf { prev.byUid.getValue(it).cpuMs }
        val after = common.sumOf { next.byUid.getValue(it).cpuMs }
        return before > 1000 && after < before * 0.9
    }

    /**
     * Per app between two readings, or null without a previous one. [packageOf] maps a uid to the app
     * it is shown as; null drops it (system uids, uninstalled apps). Wakelock and location time are
     * capped at the interval; overlapping wakelocks may still count more than once.
     */
    fun interval(prev: Snapshot?, next: Snapshot, packageOf: (Int) -> String?): Interval? {
        if (prev == null || next.timeMillis <= prev.timeMillis) return null
        val reset = isReset(prev, next)
        // After a reset the counters cover at most the time on battery since, not the whole gap.
        val from = if (reset && next.batteryMs != null) {
            (next.timeMillis - next.batteryMs).coerceIn(prev.timeMillis, next.timeMillis)
        } else prev.timeMillis
        val span = next.timeMillis - from
        val rows = LinkedHashMap<String, Counters>()
        for ((uid, n) in next.byUid) {
            val g = if (reset) n else growth(prev.byUid[uid], n)
            if (g.isZero) continue
            val pkg = packageOf(uid) ?: continue
            val capped = g.copy(wakelockMs = g.wakelockMs.coerceAtMost(span), gpsMs = g.gpsMs.coerceAtMost(span))
            rows[pkg] = (rows[pkg] ?: Counters.ZERO) + capped
        }
        return Interval(from, next.timeMillis, reset, rows.map { (p, c) -> AppCounters(p, c) })
    }

    /**
     * Splits [c] by [shares] (summing to 1) so the parts add up to exactly [c]: each part is the
     * rounded running total minus the one before, so no wakeup gets lost to rounding.
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

    /** The interval's apps per local day ("yyyy-MM-dd"), spread evenly over the time like the background report. */
    fun byDay(interval: Interval, zone: ZoneId): Map<String, List<AppCounters>> {
        val days = BackgroundDelta.splitByDay(interval.fromMillis, interval.toMillis, zone)
        val out = LinkedHashMap<String, MutableList<AppCounters>>()
        for (a in interval.apps) {
            split(a.counters, days.map { it.second }).forEachIndexed { i, part ->
                if (!part.isZero) out.getOrPut(days[i].first) { ArrayList() } += AppCounters(a.pkg, part)
            }
        }
        return out
    }

    /**
     * Last night: from local midnight to [morningMillis], the reading at the first screen-on after
     * 05:00 (the same morning as the background report). Null when that morning is not today's, or
     * when the first screen-on came after noon: then the "night" would be most of the day.
     */
    /** A first screen-on at or after this hour does not end a night. */
    const val LATEST_MORNING_HOUR = 12

    fun nightWindow(nowMillis: Long, morningMillis: Long?, zone: ZoneId): LongRange? {
        if (morningMillis == null || morningMillis > nowMillis) return null
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val morning = Instant.ofEpochMilli(morningMillis).atZone(zone)
        if (morning.toLocalDate() != today || morning.hour < BackgroundSchedule.MORNING_HOUR) return null
        if (morning.hour >= LATEST_MORNING_HOUR) return null
        return today.atStartOfDay(zone).toInstant().toEpochMilli()..morningMillis
    }

    /**
     * Per app inside [window], from the intervals that overlap it. An interval reaching outside counts
     * with the share of its time inside, as Android does not say when within it things happened.
     */
    fun inWindow(intervals: List<Interval>, window: LongRange): List<AppCounters> {
        val rows = LinkedHashMap<String, Counters>()
        for (i in intervals) {
            val from = maxOf(i.fromMillis, window.first)
            val to = minOf(i.toMillis, window.last)
            if (to <= from) continue
            val share = if (i.toMillis > i.fromMillis) (to - from).toDouble() / (i.toMillis - i.fromMillis) else 1.0
            for (a in i.apps) {
                val part = if (share >= 1.0) a.counters else split(a.counters, listOf(share, 1 - share))[0]
                rows[a.pkg] = (rows[a.pkg] ?: Counters.ZERO) + part
            }
        }
        return rows.map { (p, c) -> AppCounters(p, c) }
    }

    /**
     * Rough cost in seconds of an awake phone, to order apps. A wakeup costs a few seconds of awake
     * radio and CPU, more at night, when it breaks the deep sleep the phone would otherwise stay in.
     * CPU counts less than awake time: by day much of it is the app on screen, the user's choice; at
     * night it runs only inside wakeups and wakelocks, which already count.
     */
    fun score(c: Counters, night: Boolean): Double {
        val perWakeup = if (night) NIGHT_WAKEUP_S else DAY_WAKEUP_S
        val cpuWeight = if (night) 0.2 else 0.5
        return c.wakelockMs / 1000.0 + c.wakeups * perWakeup + c.cpuMs / 1000.0 * cpuWeight
    }

    private const val NIGHT_WAKEUP_S = 5.0
    private const val DAY_WAKEUP_S = 2.0

    /** Worst first; apps that did nothing worth a line are dropped. */
    fun rank(apps: List<AppCounters>, night: Boolean): List<AppCounters> =
        apps.filter { worthShowing(it.counters) }.sortedByDescending { score(it.counters, night) }

    /** At least one wakeup, half a minute awake or a minute of CPU. */
    fun worthShowing(c: Counters) = c.wakeups > 0 || c.wakelockMs >= 30_000 || c.cpuMs >= 60_000

    /**
     * "Unusual" when today's wakeups or awake time are at least 2.5 times the app's average over
     * [previous] days and clearly more than noise. Needs three earlier days, like the background report.
     */
    fun unusual(today: Counters, previous: List<Counters>): Boolean {
        if (previous.size < 3) return false
        val wakeups = previous.map { it.wakeups.toDouble() }.average()
        val awake = previous.map { it.wakelockMs.toDouble() }.average()
        return (today.wakeups >= 50 && today.wakeups >= 2.5 * wakeups) ||
            (today.wakelockMs >= 10 * 60_000 && today.wakelockMs >= 2.5 * awake)
    }

    /**
     * One app's values on the measured [days]: zero where it did nothing that day, left out where
     * nothing was measured at all (service off, charging all day), so gaps do not lower its usual day.
     */
    fun measuredValues(series: Map<String, Counters>?, days: List<String>, measured: Set<String>): List<Counters> =
        days.filter { it in measured }.map { series?.get(it) ?: Counters.ZERO }

    /** The app's usual day: the average per field, or null without any day. */
    fun average(days: List<Counters>): Counters? {
        if (days.isEmpty()) return null
        val sum = days.fold(Counters.ZERO) { a, b -> a + b }.toArray()
        return Counters.of(LongArray(Counters.FIELDS) { Math.round(sum[it].toDouble() / days.size) })
    }

    /** Worth a line in the "Last night" card: a hundred wakeups, or a quarter hour awake. */
    fun standsOut(c: Counters) = c.wakeups >= 100 || c.wakelockMs >= 15 * 60_000

    /** For the night line: whether the wakeups say more than the awake time. */
    fun wakeupsDominate(c: Counters) = c.wakeups >= 100 && c.wakeups * NIGHT_WAKEUP_S >= c.wakelockMs / 1000.0

    /**
     * Which of the packages sharing a uid stands for it: one with a launcher icon (what the user
     * knows), then the shortest name (usually the main package), then alphabetical for stability.
     */
    fun pickPackage(packages: List<String>, launchable: Set<String>): String? =
        packages.minWithOrNull(compareBy<String>({ it !in launchable }, { it.length }, { it }))

    /** "uid:cpu:wl:wlc:wk:jobs:jobms:syncs:gps;..." for the stored reading; zero rows are left out. */
    fun encode(byUid: Map<Int, Counters>): String =
        byUid.entries.filter { !it.value.isZero }.joinToString(";") { (uid, c) -> "$uid:" + c.toArray().joinToString(":") }

    fun decode(s: String): Map<Int, Counters> {
        val out = HashMap<Int, Counters>()
        for (part in s.split(';')) {
            val f = part.split(':')
            if (f.size != Counters.FIELDS + 1) continue
            val uid = f[0].toIntOrNull() ?: continue
            val values = f.drop(1).map { it.toLongOrNull() }
            if (values.any { it == null }) continue
            out[uid] = Counters.of(LongArray(Counters.FIELDS) { values[it]!! })
        }
        return out
    }
}

/** When the wakeup reading runs; one binder call each, so it can be frequent. */
object WakeupSchedule {
    enum class Trigger { PERIODIC, PLUG, UNPLUG, MORNING, SCREEN }

    const val PERIOD_MS = 30 * 60_000L
    /** Plug events wait a minute after the last reading: a loose cable fires many of them. */
    const val MIN_GAP_MS = 60_000L

    /**
     * Periodic readings only on battery: Android does not count while charging. Plug-in keeps what
     * happened before a possible reset, unplug starts the new count, morning closes the night.
     * An open screen reads when the last one is a period old, so the card is fresh without the service.
     */
    fun due(trigger: Trigger, nowMillis: Long, lastMillis: Long?, plugged: Boolean): Boolean {
        val since = if (lastMillis == null) Long.MAX_VALUE else nowMillis - lastMillis
        if (since < MIN_GAP_MS) return false
        return when (trigger) {
            Trigger.PLUG, Trigger.UNPLUG, Trigger.MORNING -> true
            Trigger.PERIODIC -> !plugged && since >= PERIOD_MS
            Trigger.SCREEN -> since >= PERIOD_MS
        }
    }
}
