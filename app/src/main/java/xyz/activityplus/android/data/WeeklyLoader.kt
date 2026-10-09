package xyz.activityplus.android.data

import android.content.Context
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.ForegroundTracker
import xyz.activityplus.android.core.WeeklyReport
import xyz.activityplus.android.core.WeeklyReport.WeekData

/** Reads the history and the usage stats of two weeks and hands them to [WeeklyReport]. Blocking: call it off the main thread. */
object WeeklyLoader {
    class Loaded(val report: WeeklyReport.Report, val weeks: WeeklyReport.Weeks, private val labels: Map<String, String>) {
        fun label(pkg: String): String = labels[pkg] ?: pkg
    }

    private data class Key(val lastStart: Long, val usageAccess: Boolean)

    // The finished week never changes, so the overview does not read two weeks of stats on every visit.
    @Volatile private var cached: Pair<Key, Loaded>? = null

    fun load(context: Context, now: Long = System.currentTimeMillis()): Loaded {
        val app = ActivityPlusApp.instance
        val weeks = WeeklyReport.weeks(now)
        val key = Key(weeks.lastStart, ForegroundTracker.hasUsageAccess(context))
        cached?.takeIf { it.first == key }?.let { return it.second }

        fun energy(from: String, to: String) = app.history.energy(from, to).associate { it.pkg to (it.mwh to it.seconds) }
        val byDay = app.history.energyByDay(weeks.previousFirstDay)
        fun measuredDays(from: String, to: String) =
            byDay.values.flatMap { days -> days.filter { it.key in from..to && it.value > 0 }.keys }.toSet().size

        val labels = HashMap<String, String>()
        fun usage(from: Long, to: Long): Pair<Map<String, Long>, Map<String, Long>> {
            val rows = app.usage.read(from, to, withStorage = false)
            rows.forEach { labels[it.pkg] = it.label }
            return rows.filter { it.screenSeconds > 0 }.associate { it.pkg to it.screenSeconds } to
                rows.filter { it.totalBytes > 0 }.associate { it.pkg to it.totalBytes }
        }
        val (screenLast, dataLast) = usage(weeks.lastStart, weeks.lastEnd)
        val (screenBefore, dataBefore) = usage(weeks.previousStart, weeks.lastStart)
        val sessions = app.history.sessions(500, charging = true)

        val last = WeekData(energy(weeks.lastFirstDay, weeks.lastLastDay), screenLast, dataLast, sessions,
            measuredDays(weeks.lastFirstDay, weeks.lastLastDay))
        val before = WeekData(energy(weeks.previousFirstDay, weeks.previousLastDay), screenBefore, dataBefore, sessions,
            measuredDays(weeks.previousFirstDay, weeks.previousLastDay))

        val battery = app.monitor.latest.value?.battery ?: runCatching { app.monitor.batteryNow() }.getOrNull()
        val report = WeeklyReport.build(weeks, last, before, battery?.capacityMah, battery?.voltageV, context.packageName)

        // Energy rows have no label of their own; apps with usage got theirs above.
        val pm = context.packageManager
        (report.energy + report.screenTime + report.data).map { it.pkg }.filter { it !in labels }.forEach { pkg ->
            labels[pkg] = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        // Without a battery reading the capacity is missing, so do not keep this one.
        return Loaded(report, weeks, labels).also { if (battery != null) cached = key to it }
    }
}
