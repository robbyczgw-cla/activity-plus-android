package xyz.activityplus.android.pro

import android.content.Context
import android.os.Process
import android.os.health.HealthStats
import android.os.health.PackageHealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import xyz.activityplus.android.core.Wakeups

/**
 * Android's per-app counters without a shell: one SystemHealthManager call for the uids of all
 * apps Activity+ can see. Needs BATTERY_STATS (pro page); without it [read] returns null.
 */
object HealthReader {
    class Reading(
        val snapshot: Wakeups.Snapshot,
        /** uid to the package it is shown as; see [Wakeups.pickPackage]. */
        val packages: Map<Int, String>,
    )

    fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): Reading? {
        if (!ProShell.hasBatteryStats(context)) return null
        val packages = packagesByUid(context)
        if (packages.isEmpty()) return null
        val uids = packages.keys.toIntArray()
        val manager = context.getSystemService(SystemHealthManager::class.java) ?: return null
        val stats = manager.takeUidSnapshots(uids)
        val byUid = HashMap<Int, Wakeups.Counters>()
        var batteryMs: Long? = null
        stats.forEachIndexed { i, s ->
            if (s == null) return@forEachIndexed
            byUid[uids[i]] = counters(s)
            // The same device-wide value in every uid's snapshot.
            if (batteryMs == null) batteryMs = s.measurement(UidHealthStats.MEASUREMENT_REALTIME_BATTERY_MS)
        }
        return Reading(Wakeups.Snapshot(nowMillis, batteryMs, byUid), packages)
    }

    /**
     * Apps only (uids from 10000 on): the system's own uids are nothing the user can act on.
     * Packages without a launcher icon are mostly invisible to Activity+ anyway (no QUERY_ALL_PACKAGES).
     */
    private fun packagesByUid(context: Context): Map<Int, String> {
        val pm = context.packageManager
        val groups = pm.getInstalledApplications(0)
            .filter { it.uid >= Process.FIRST_APPLICATION_UID }
            .groupBy({ it.uid }, { it.packageName })
        return groups.mapNotNull { (uid, pkgs) ->
            // Only shared uids need the launcher check; it costs a query per package.
            val launchable = if (pkgs.size > 1) pkgs.filter { pm.getLaunchIntentForPackage(it) != null }.toSet() else emptySet()
            Wakeups.pickPackage(pkgs, launchable)?.let { uid to it }
        }.toMap()
    }

    private fun counters(s: HealthStats): Wakeups.Counters {
        val partial = s.timers(UidHealthStats.TIMERS_WAKELOCKS_PARTIAL)
        val jobs = s.timers(UidHealthStats.TIMERS_JOBS)
        val syncs = s.timers(UidHealthStats.TIMERS_SYNCS)
        // Wakeup alarms are counted per package inside the uid.
        val wakeups = if (s.hasStats(UidHealthStats.STATS_PACKAGES)) {
            s.getStats(UidHealthStats.STATS_PACKAGES).values.sumOf { p ->
                if (p.hasMeasurements(PackageHealthStats.MEASUREMENTS_WAKEUP_ALARMS_COUNT)) {
                    p.getMeasurements(PackageHealthStats.MEASUREMENTS_WAKEUP_ALARMS_COUNT).values.sum()
                } else 0L
            }
        } else 0L
        return Wakeups.Counters(
            cpuMs = (s.measurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) ?: 0) +
                (s.measurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS) ?: 0),
            wakelockMs = partial.sumOf { it.time },
            wakelocks = partial.sumOf { it.count.toLong() },
            wakeups = wakeups,
            jobs = jobs.sumOf { it.count.toLong() },
            jobMs = jobs.sumOf { it.time },
            syncs = syncs.sumOf { it.count.toLong() },
            gpsMs = if (s.hasTimer(UidHealthStats.TIMER_GPS_SENSOR)) s.getTimerTime(UidHealthStats.TIMER_GPS_SENSOR) else 0,
        )
    }

    // HealthStats throws for keys a snapshot does not have, so every read checks first.
    private fun HealthStats.measurement(key: Int): Long? = if (hasMeasurement(key)) getMeasurement(key) else null

    private fun HealthStats.timers(key: Int) = if (hasTimers(key)) getTimers(key).values else emptyList()
}
