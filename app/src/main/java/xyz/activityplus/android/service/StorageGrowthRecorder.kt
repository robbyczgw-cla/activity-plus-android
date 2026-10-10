package xyz.activityplus.android.service

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.core.AppStorageSize
import xyz.activityplus.android.core.ForegroundTracker
import xyz.activityplus.android.core.StorageGrowth
import xyz.activityplus.android.data.StorageGrowthStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Takes the daily storage snapshot for the growth history. The service calls [onTick] with every
 * sample (cheap: one date check a minute); the storage screen calls [recordIfDue] too, so the
 * history also grows while the live monitor is off.
 */
object StorageGrowthRecorder {
    private const val TAG = "StorageGrowth"
    private val running = AtomicBoolean(false)
    @Volatile private var lastDay: java.time.LocalDate? = null
    @Volatile private var loaded = false
    @Volatile private var lastCheck = 0L
    /** After a failure, an hour's rest, so a broken reading does not run every minute. */
    @Volatile private var retryAfter = 0L

    fun onTick(context: Context, scope: CoroutineScope, nowMillis: Long) {
        if (nowMillis - lastCheck < 60_000 || nowMillis < retryAfter || running.get()) return
        lastCheck = nowMillis
        if (loaded && !StorageGrowth.due(local(nowMillis), lastDay)) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { recordIfDue(appContext, nowMillis) }
    }

    /** Blocking; call off the main thread. Takes a few seconds on phones with many apps. True when it wrote one. */
    fun recordIfDue(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        // Never two at a time; whoever comes second simply skips.
        if (!running.compareAndSet(false, true)) return false
        return try {
            val store = StorageGrowthStore.get(context)
            if (!loaded) {
                lastDay = store.lastDay()
                loaded = true
            }
            val now = local(nowMillis)
            if (!StorageGrowth.due(now, lastDay)) return false
            val (total, free) = device(context)
            val apps = if (ForegroundTracker.hasUsageAccess(context)) apps(context) else null
            val today = now.toLocalDate()
            store.save(today, nowMillis, total, free, apps)
            store.prune(today.minusDays(StorageGrowth.RETENTION_DAYS - 1))
            lastDay = today
            true
        } catch (e: Exception) {
            retryAfter = nowMillis + 3_600_000
            Log.w(TAG, "storage snapshot failed", e)
            false
        } finally {
            running.set(false)
        }
    }

    private fun local(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun device(context: Context): Pair<Long, Long> = try {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
    } catch (_: Exception) {
        val fs = StatFs(Environment.getDataDirectory().path)
        fs.totalBytes to fs.availableBytes
    }

    /** Only the storage of every installed package; nothing of AppUsageReader's events and network work. */
    private fun apps(context: Context): Map<String, AppStorageSize> {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val out = HashMap<String, AppStorageSize>()
        for (app in context.packageManager.getInstalledApplications(0)) {
            // A package that disappears or refuses in between is skipped, not the whole snapshot.
            runCatching { stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, user) }.getOrNull()?.let {
                out[app.packageName] = AppStorageSize(it.appBytes, it.dataBytes, it.cacheBytes)
            }
        }
        return out
    }
}
