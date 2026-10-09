package xyz.activityplus.android.core

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Process
import android.os.storage.StorageManager

/** Everything Android lets an app know about other apps, per package, for one time range. */
data class AppUsage(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val screenSeconds: Long,
    val launches: Int,
    /** Time a foreground service of the app ran, i.e. it worked in the background with a notification. */
    val serviceSeconds: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val backgroundBytes: Long,
    val appBytes: Long?,
    val dataBytes: Long?,
    val cacheBytes: Long?,
    val lastUsedMillis: Long,
    val installedMillis: Long,
) {
    val totalBytes get() = wifiBytes + mobileBytes
    val storageBytes get() = listOfNotNull(appBytes, dataBytes).sum()
}

/** Shared uids (android, phone, bluetooth ...) are folded into one "Android system" row, like "macOS" on the Mac. */
const val SYSTEM_ROW = "#system"

// NetworkStatsManager still takes the old ConnectivityManager.TYPE_* constants below API 31.
@Suppress("DEPRECATION")
class AppUsageReader(private val context: Context) {
    private val pm = context.packageManager
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val netStats = context.getSystemService(NetworkStatsManager::class.java)
    private val storageStats = context.getSystemService(StorageStatsManager::class.java)

    fun read(fromMillis: Long, toMillis: Long, withStorage: Boolean): List<AppUsage> {
        if (!ForegroundTracker.hasUsageAccess(context)) return emptyList()
        val screen = screenTime(fromMillis, toMillis)
        val stats = usage.queryAndAggregateUsageStats(fromMillis, toMillis)
        val net = networkByUid(fromMillis, toMillis)

        val installed = pm.getInstalledApplications(0)
        val byUid = installed.groupBy { it.uid }
        val rows = HashMap<String, AppUsage>()

        for (app in installed) {
            val s = stats[app.packageName]
            val sc = screen[app.packageName]
            val shared = (byUid[app.uid]?.size ?: 1) > 1
            // Data is per uid; apps sharing a uid get it once, on the system row.
            val n = if (shared) null else net[app.uid]
            val used = (sc?.first ?: 0) > 0 || (s?.totalTimeForegroundServiceUsed ?: 0) > 0 || (n?.total ?: 0) > 0
            val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(app.packageName) == null
            if (!used && isSystem) continue
            val storage = if (withStorage) storage(app) else null
            rows[app.packageName] = AppUsage(
                pkg = app.packageName,
                label = pm.getApplicationLabel(app).toString(),
                system = isSystem,
                screenSeconds = (sc?.first ?: 0) / 1000,
                launches = sc?.second ?: 0,
                serviceSeconds = (s?.totalTimeForegroundServiceUsed ?: 0) / 1000,
                wifiBytes = n?.wifi ?: 0,
                mobileBytes = n?.mobile ?: 0,
                backgroundBytes = n?.background ?: 0,
                appBytes = storage?.appBytes,
                dataBytes = storage?.dataBytes,
                cacheBytes = storage?.cacheBytes,
                lastUsedMillis = s?.lastTimeUsed ?: 0,
                installedMillis = runCatching { pm.getPackageInfo(app.packageName, 0).firstInstallTime }.getOrDefault(0),
            )
        }

        // Shared system uids (1000 = android, 1001 = phone, ...) and uids without a package.
        val claimed = installed.filter { (byUid[it.uid]?.size ?: 1) == 1 }.map { it.uid }.toSet()
        val systemNet = net.filterKeys { it !in claimed }.values
        if (systemNet.isNotEmpty()) {
            rows[SYSTEM_ROW] = AppUsage(
                pkg = SYSTEM_ROW, label = context.getString(xyz.activityplus.android.R.string.row_system), system = true, screenSeconds = 0, launches = 0,
                serviceSeconds = 0,
                wifiBytes = systemNet.sumOf { it.wifi },
                mobileBytes = systemNet.sumOf { it.mobile },
                backgroundBytes = systemNet.sumOf { it.background },
                appBytes = null, dataBytes = null, cacheBytes = null, lastUsedMillis = 0, installedMillis = 0,
            )
        }
        return rows.values.toList()
    }

    /** Foreground time and launches from RESUMED/PAUSED pairs; more exact than the aggregated stats. */
    private fun screenTime(from: Long, to: Long): Map<String, Pair<Long, Int>> {
        val out = HashMap<String, Pair<Long, Int>>()
        val events = usage.queryEvents(from, to) ?: return out
        val e = UsageEvents.Event()
        var openPkg: String? = null
        var openClass: String? = null
        var openSince = 0L
        val lastClose = HashMap<String, Long>()
        fun close(at: Long) {
            val pkg = openPkg ?: return
            val (ms, n) = out[pkg] ?: (0L to 0)
            out[pkg] = (ms + (at - openSince).coerceAtLeast(0)) to n
            lastClose[pkg] = at
            openPkg = null
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (openPkg != e.packageName) {
                        close(e.timeStamp)
                        // A launch is a resume after a gap, not an activity change inside the app.
                        if (e.timeStamp - (lastClose[e.packageName] ?: 0L) > 2_000) {
                            val (ms, n) = out[e.packageName] ?: (0L to 0)
                            out[e.packageName] = ms to n + 1
                        }
                        openPkg = e.packageName
                        openSince = e.timeStamp
                    }
                    openClass = e.className
                }
                // Only the open activity ends the session; a late STOPPED of an earlier one does not.
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (e.packageName == openPkg && e.className == openClass) close(e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(e.timeStamp)
            }
        }
        close(minOf(to, System.currentTimeMillis()))
        return out
    }

    private class Net(var wifi: Long = 0, var mobile: Long = 0, var background: Long = 0) {
        val total get() = wifi + mobile
    }

    private fun networkByUid(from: Long, to: Long): Map<Int, Net> {
        val out = HashMap<Int, Net>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val summary = try {
                netStats.querySummary(type, null, from, to)
            } catch (_: Exception) {
                null
            } ?: continue
            val b = NetworkStats.Bucket()
            while (summary.hasNextBucket()) {
                summary.getNextBucket(b)
                val bytes = b.rxBytes + b.txBytes
                val n = out.getOrPut(b.uid) { Net() }
                if (type == ConnectivityManager.TYPE_WIFI) n.wifi += bytes else n.mobile += bytes
                if (b.state == NetworkStats.Bucket.STATE_DEFAULT) n.background += bytes
            }
            summary.close()
        }
        return out
    }

    /** Total bytes per network type for the whole device. */
    fun deviceData(from: Long, to: Long): Pair<Long, Long> {
        fun total(type: Int) = try {
            netStats.querySummaryForDevice(type, null, from, to).let { it.rxBytes + it.txBytes }
        } catch (_: Exception) {
            0L
        }
        return total(ConnectivityManager.TYPE_WIFI) to total(ConnectivityManager.TYPE_MOBILE)
    }

    private data class Storage(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)

    private fun storage(app: ApplicationInfo): Storage? = try {
        val s = storageStats.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle())
        Storage(s.appBytes, s.dataBytes, s.cacheBytes)
    } catch (_: Exception) {
        null
    }

    fun icon(pkg: String) = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
}
