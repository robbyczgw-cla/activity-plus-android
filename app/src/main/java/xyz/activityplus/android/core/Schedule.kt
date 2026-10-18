package xyz.activityplus.android.core

import xyz.activityplus.android.pro.AlarmParser
import xyz.activityplus.android.pro.JobParser
import java.time.Instant
import java.time.ZoneId

/**
 * "What apps have planned": which app will wake the phone next, and how often. The preventive side
 * of "Who woke the phone" (Wakeups), from the alarms and jobs apps have set right now
 * (AlarmParser, JobParser). Pure, no Android types.
 *
 * Why exact alarms allowed in Doze are the costly kind: in Doze, Android holds back ordinary alarms
 * and jobs and delivers them bundled in short maintenance windows, minutes to hours apart. An exact
 * alarm allowed while idle comes on time instead, so each one pulls the phone out of deep sleep on its own.
 */
object Schedule {
    /** "The next hours" of the card. */
    const val HORIZON_MS = 8 * 3_600_000L

    // The coming night in local time; the same for everyone, as the plan is about the future.
    const val NIGHT_START_HOUR = 23
    const val NIGHT_END_HOUR = 7

    /**
     * Exact, allowed in Doze and repeating at least this often: the costly pattern, and a Diagnosis finding.
     * 30 minutes: more than two wakeups an hour through the whole night, 16 or more in eight hours,
     * each breaking the deep sleep in which a phone uses least. Doze's own maintenance windows come
     * far less often after the first hour, so such an app keeps the phone from ever sleeping long.
     */
    const val SHORT_INTERVAL_MS = 30 * 60_000L

    /**
     * An interval read from Android's counts (one-time exact alarms an app sets again each time) needs
     * this many deliveries since the phone started, and the phone running this long: a fresh start
     * would otherwise make one alarm look like a storm.
     */
    const val MIN_SEEN = 4
    const val MIN_UPTIME_MS = 2 * 3_600_000L

    /** Android delivers repeating alarms at most once a minute (AlarmManager's min_interval). */
    const val MIN_REPEAT_MS = 60_000L

    /** A job on battery this often or more is a frequent one; Android's shortest period is 15 minutes. */
    const val FREQUENT_JOB_MS = 3_600_000L

    /** Wakeups tonight from which an app is worth naming even without the costly pattern: one an hour. */
    const val NIGHT_WAKEUPS = 8

    /**
     * Rough share of planned alarms and jobs that still cost a wakeup of their own at night when they
     * are not allowed in Doze: they wait for a maintenance window and share it. For ranking only.
     */
    const val DEFERRED_WEIGHT = 0.25

    /** All of an app's pending wakeup alarms with one tag, as one pattern. */
    data class AlarmPlan(
        val tag: String,
        val nextMillis: Long,
        /** How often it comes; null for a one-time alarm without a history. */
        val intervalMs: Long?,
        /** The interval is Android's count since start, not a repeat the app set: "about every". */
        val intervalMeasured: Boolean,
        val exact: Boolean,
        val allowWhileIdle: Boolean,
        /** The user's own alarm clock: always on time, and never the problem. */
        val alarmClock: Boolean,
        /** Planned deliveries in [Plan.night] and in the next [HORIZON_MS]. */
        val tonight: Int,
        val ahead: Int,
    ) {
        val costly get() = exact && allowWhileIdle && !alarmClock && intervalMs != null && intervalMs <= SHORT_INTERVAL_MS
    }

    data class JobPlan(
        val job: JobParser.Job,
        /** May run without a charger. */
        val onBattery: Boolean,
        val frequent: Boolean,
        val tonight: Int,
    )

    enum class Reason { EXACT_IN_DOZE, NIGHT_WAKEUPS, FREQUENT_JOBS }

    data class AppPlan(
        val pkg: String,
        /** Soonest first. */
        val alarms: List<AlarmPlan>,
        val jobs: List<JobPlan>,
        /** Wakeups planned for the coming night and the next hours; alarm clocks left out. */
        val wakeupsTonight: Int,
        val wakeupsAhead: Int,
        val nextWakeupMillis: Long?,
        /** Wakeups Android delivered to the app since the phone started; null when the report has none. */
        val pastWakeups: Long?,
        /** Job starts over [Plan.runsSpanMs]; null when the report has none. */
        val jobRuns: Int?,
        val reasons: Set<Reason>,
        /** Order of the "noisiest planners"; 0 for an app that plans nothing for the night. */
        val score: Double,
    ) {
        /** The pattern to name first: the costly one with the shortest interval, else the next wakeup. */
        val headline: AlarmPlan?
            get() = alarms.filter { it.costly }.minByOrNull { it.intervalMs!! }
                ?: alarms.filter { !it.alarmClock }.minByOrNull { it.nextMillis }
    }

    data class Plan(
        val nowMillis: Long,
        /** The coming night, from now when it has already begun. */
        val night: LongRange,
        val sinceBootMs: Long?,
        val runsSpanMs: Long?,
        /** Every app with a wakeup alarm or a job, noisiest first. */
        val apps: List<AppPlan>,
    )

    /** For the Diagnosis finding: an app with the costly pattern. */
    data class Costly(val pkg: String, val intervalMs: Long, val tonight: Int)

    /**
     * Builds the plan. [nowMillis] is used when the alarm report has no clock of its own; packages in
     * [ignore] (Android itself, Activity+) are left out.
     */
    fun plan(
        alarms: AlarmParser.Report, jobs: JobParser.Report?, nowMillis: Long, zone: ZoneId, ignore: Set<String> = emptySet(),
    ): Plan {
        val now = alarms.nowMillis ?: nowMillis
        val night = comingNight(now, zone)
        val horizon = now until now + HORIZON_MS
        val byPkg = alarms.alarms
            .filter { it.wakeup && it.whenMillis != null && it.pkg !in ignore }
            .groupBy { it.pkg }
        val jobsByPkg = jobs?.jobs.orEmpty().filter { it.pkg !in ignore }.groupBy { it.pkg }
        val apps = (byPkg.keys + jobsByPkg.keys).map { pkg ->
            val stats = alarms.stats[pkg]
            val plans = byPkg[pkg].orEmpty().groupBy { it.tag }.map { (tag, list) ->
                alarmPlan(tag, list, stats, alarms.sinceBootMs, now, night, horizon)
            }.sortedBy { it.nextMillis }
            val jobPlans = jobsByPkg[pkg].orEmpty().map { jobPlan(it, now, night) }
            appPlan(pkg, plans, jobPlans, stats?.wakeups, jobs?.runs?.get(pkg))
        }
        return Plan(
            now, night, alarms.sinceBootMs, jobs?.runsSpanMs,
            apps.sortedWith(compareByDescending<AppPlan> { it.score }.thenByDescending { it.wakeupsAhead }.thenBy { it.pkg }),
        )
    }

    internal fun alarmPlan(
        tag: String, list: List<AlarmParser.Alarm>, stats: AlarmParser.PackageStats?, sinceBootMs: Long?,
        now: Long, night: LongRange, horizon: LongRange,
    ): AlarmPlan {
        // Overdue ones are about to go off.
        val times = list.map { maxOf(it.whenMillis!!, now) }.sorted()
        val first = list.minBy { it.whenMillis!! }
        val repeat = list.map { it.repeatMs }.filter { it > 0 }.minOrNull()
        val seen = stats?.tags?.filter { it.tag == tag }?.sumOf { it.count } ?: 0
        val measured = repeat == null && seen >= MIN_SEEN && sinceBootMs != null && sinceBootMs >= MIN_UPTIME_MS
        val interval = when {
            repeat != null -> repeat.coerceAtLeast(MIN_REPEAT_MS)
            measured -> (sinceBootMs!! / seen).coerceAtLeast(MIN_REPEAT_MS)
            else -> null
        }
        fun count(range: LongRange) =
            if (interval != null) occurrences(times.first(), interval, range.first, range.last + 1)
            else times.count { it in range }
        return AlarmPlan(
            tag, times.first(), interval, measured, list.any { it.exact }, list.any { it.allowWhileIdle },
            first.alarmClock, count(night), count(horizon),
        )
    }

    internal fun jobPlan(job: JobParser.Job, now: Long, night: LongRange): JobPlan {
        val onBattery = !job.requiresCharging
        val period = job.periodicMs?.takeIf { it > 0 }
        val tonight = when {
            !onBattery -> 0
            // A periodic job's start within its period is up to Android, so count whole periods.
            period != null -> ((night.last + 1 - night.first) / period).toInt()
            else -> if (now + (job.earliestInMs ?: 0).coerceAtLeast(0) in night) 1 else 0
        }
        return JobPlan(job, onBattery, onBattery && period != null && period <= FREQUENT_JOB_MS, tonight)
    }

    internal fun appPlan(pkg: String, alarms: List<AlarmPlan>, jobs: List<JobPlan>, pastWakeups: Long?, jobRuns: Int?): AppPlan {
        val counted = alarms.filter { !it.alarmClock }
        val tonight = counted.sumOf { it.tonight }
        val reasons = buildSet {
            if (counted.any { it.costly }) add(Reason.EXACT_IN_DOZE)
            if (tonight >= NIGHT_WAKEUPS) add(Reason.NIGHT_WAKEUPS)
            if (jobs.any { it.frequent }) add(Reason.FREQUENT_JOBS)
        }
        val score = counted.sumOf { it.tonight * if (it.allowWhileIdle) 1.0 else DEFERRED_WEIGHT } +
            jobs.sumOf { it.tonight * DEFERRED_WEIGHT }
        return AppPlan(
            pkg, alarms, jobs, tonight, counted.sumOf { it.ahead }, counted.minOfOrNull { it.nextMillis },
            pastWakeups, jobRuns, reasons, score,
        )
    }

    /** The apps worth a line on the card: something planned for the night, noisiest first. */
    fun noisiest(plan: Plan, limit: Int): List<AppPlan> = plan.apps.filter { it.score > 0 || it.reasons.isNotEmpty() }.take(limit)

    /** Apps with the costly pattern, shortest interval first. */
    fun costly(plan: Plan): List<Costly> = plan.apps.mapNotNull { a ->
        a.alarms.filter { it.costly }.minByOrNull { it.intervalMs!! }?.let { Costly(a.pkg, it.intervalMs!!, it.tonight) }
    }.sortedBy { it.intervalMs }

    /**
     * From [NIGHT_START_HOUR] to [NIGHT_END_HOUR] local time: tonight's, or the current one from now on
     * when it has begun.
     */
    fun comingNight(nowMillis: Long, zone: ZoneId): LongRange {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val startDay = if (t.hour < NIGHT_END_HOUR) t.toLocalDate().minusDays(1) else t.toLocalDate()
        val start = startDay.atTime(NIGHT_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val end = startDay.plusDays(1).atTime(NIGHT_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return maxOf(start, nowMillis) until end
    }

    /** How many of first, first + interval, ... fall in [from, to). Without an interval: whether first does. */
    fun occurrences(first: Long, intervalMs: Long?, from: Long, to: Long): Int {
        if (to <= from) return 0
        if (intervalMs == null || intervalMs <= 0) return if (first in from until to) 1 else 0
        if (first >= to) return 0
        val skip = if (first >= from) 0 else (from - first + intervalMs - 1) / intervalMs
        val start = first + skip * intervalMs
        if (start >= to) return 0
        return (1 + (to - 1 - start) / intervalMs).toInt()
    }

    /**
     * A readable name for an alarm tag or job service: "com.app.intent.action.SYNC_NOW" → "SYNC_NOW",
     * "com.app/.sync.SyncService" → "SyncService". Names with spaces are already readable; a very short
     * last part keeps the one before it ("wlan0.RENEW").
     */
    fun shortName(name: String): String {
        val n = name.trim()
        if (' ' in n) return n
        val parts = n.substringAfterLast('/').split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return n
        val last = parts.last()
        return if (last.length < 6 && parts.size >= 2) parts[parts.size - 2] + "." + last else last
    }
}
