package xyz.activityplus.android.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.ChargeUnit
import xyz.activityplus.android.core.SinceUpdate
import xyz.activityplus.android.core.SinceUpdate.Metric
import xyz.activityplus.android.core.SinceUpdate.StorageAt
import xyz.activityplus.android.core.WakeLimit

/**
 * Reads what Activity+ already keeps per app and day (background battery, wakeups and wakelocks,
 * mobile and Wi-Fi data, storage snapshots, energy on screen) and hands it to [SinceUpdate], one
 * result per recent update. A store that was never written simply contributes no metric.
 * Blocking: call it off the main thread.
 */
object UpdateLoader {
    private const val DAY_MS = 86_400_000L
    /** Overview, apps page and diagnosis all ask; the numbers move by the day, so ten minutes of reuse is plenty. */
    private const val CACHE_MS = 10 * 60_000L

    /** StorageGrowthStore's file; asked for before opening it, so a phone without snapshots does not get an empty one. */
    private const val STORAGE_FILE = "storage.db"

    @Volatile private var cached: Pair<Long, List<SinceUpdate.Result>>? = null

    /** The updates of the last three weeks, newest first, without Activity+'s own. */
    fun load(context: Context, now: Long = System.currentTimeMillis()): List<SinceUpdate.Result> {
        cached?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        return compute(context, now).also { cached = now to it }
    }

    private fun compute(context: Context, now: Long): List<SinceUpdate.Result> {
        if (!UpdateLog.exists(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        val log = UpdateLog.get(context)
        val all = log.events(now - 90 * DAY_MS)
        val recent = all.filter { it.pkg != context.packageName && now - it.timeMillis <= SinceUpdate.RECENT_DAYS * DAY_MS }
        if (recent.isEmpty()) return emptyList()
        val times = all.groupBy { it.pkg }.mapValues { (_, events) -> events.map { it.timeMillis } }

        // One read per store for the whole span of all updates, not one per update.
        val firstDay = day(recent.minOf { it.timeMillis }).minusDays(SinceUpdate.BEFORE_DAYS.toLong())
        val from = firstDay.toString()
        val to = today.toString()
        val daysInSpan = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it <= today }.map { it.toString() }.toList()

        val background = if (BackgroundStore.exists(context)) BackgroundStore.get(context).let { it.measuredDays(from, to) to it } else null
        val wakeups = if (WakeupStore.exists(context)) WakeupStore.get(context).allSeries(from, to) else null
        val wakeupDays = wakeups?.let { WakeLimit.measuredDays(it, daysInSpan) }
        val netDays = log.netDays(from, to)
        val net = log.netByDay(from, to)
        val storage = storageSnapshots(context, firstDay.minusDays(10))

        /** A day the phone was measured counts as 0 for an app without a row; other days stay unknown. */
        fun series(measured: Set<String>, value: (String) -> Double?) =
            measured.associateWith { value(it) ?: 0.0 }

        return recent.map { e ->
            val (previous, next) = SinceUpdate.neighbours(times[e.pkg].orEmpty(), e.timeMillis)
            val w = SinceUpdate.windows(day(e.timeMillis), today, previous?.let(::day), next?.let(::day))
            val daily = HashMap<Metric, Map<String, Double>>()
            // Not while the stored rows are in an unconfirmed unit: millions of "mAh" would compare as huge jumps.
            background?.takeIf { !ChargeUnit.implausible(it.second.maxDayMah(), null) }?.let { (measured, store) ->
                val mah = store.series(e.pkg, from, to)
                daily[Metric.BACKGROUND] = series(measured) { mah[it] }
            }
            if (wakeups != null && wakeupDays != null) {
                val mine = wakeups[e.pkg].orEmpty()
                daily[Metric.WAKEUPS] = series(wakeupDays) { mine[it]?.wakeups?.toDouble() }
                daily[Metric.WAKELOCK] = series(wakeupDays) { mine[it]?.wakelockMs?.toDouble() }
            }
            if (netDays.isNotEmpty()) {
                val mine = net[e.pkg].orEmpty()
                daily[Metric.WIFI] = series(netDays) { mine[it]?.first?.toDouble() }
                daily[Metric.MOBILE] = series(netDays) { mine[it]?.second?.toDouble() }
            }
            val sums = if (w.before.isNotEmpty() && w.after.isNotEmpty()) energy(w, e.pkg) else null
            val size = storage[e.pkg]?.let { SinceUpdate.pickStorage(it, e.timeMillis, next) }
            SinceUpdate.evaluate(e, w, daily, sums, size)
        }
    }

    /** Energy and screen seconds of one app, summed over the two windows (each a run of consecutive days). */
    private fun energy(w: SinceUpdate.Windows, pkg: String): SinceUpdate.Energy? {
        val history = ActivityPlusApp.instance.history
        fun sum(days: List<String>) = history.energy(days.first(), days.last()).firstOrNull { it.pkg == pkg }
        val before = sum(w.before)
        val after = sum(w.after)
        return SinceUpdate.Energy(before?.mwh ?: 0.0, before?.seconds ?: 0.0, after?.mwh ?: 0.0, after?.seconds ?: 0.0)
    }

    /** Every app's app-plus-data size per snapshot; empty if no snapshot with app rows was ever taken. */
    private fun storageSnapshots(context: Context, from: LocalDate): Map<String, List<StorageAt>> {
        if (!context.getDatabasePath(STORAGE_FILE).exists()) return emptyMap()
        val out = HashMap<String, ArrayList<StorageAt>>()
        for (snapshot in StorageGrowthStore.get(context).apps(from)) {
            for ((pkg, size) in snapshot.apps) out.getOrPut(pkg) { ArrayList() } += StorageAt(snapshot.timeMillis, size.sizeBytes)
        }
        return out
    }
}
