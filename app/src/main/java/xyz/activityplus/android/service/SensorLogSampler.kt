package xyz.activityplus.android.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.activityplus.android.data.SensorLogStore
import xyz.activityplus.android.pro.AppOpsParser
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.pro.ShellService
import java.time.ZoneId

/**
 * Reads Android's app-ops report (who used location, camera, microphone) every 30 minutes while the
 * service runs and stores the accesses that are new, so the log reaches further back than Android's
 * own memory of "the latest access". Only with the computer grant; never through Shizuku. The
 * report is 700 kB and more on a full phone, so it is parsed as it streams in and only the sensor
 * ops are kept. It runs on the service's ticks, which pause in deep sleep: it never wakes the phone, and
 * with the screen off on battery it waits for the next screen-on (see [HeavyWork]).
 * Failures are logged and otherwise ignored.
 */
object SensorLogSampler {
    private const val TAG = "SensorLogSampler"
    const val PERIOD_MS = 30 * 60_000L
    /** Ticks come every few seconds; the time is checked at most once a minute. */
    private const val CHECK_MS = 60_000L

    /** One report at a time: the service and an open Sensors page may ask together. */
    private val mutex = Mutex()
    @Volatile private var lastRun = 0L
    @Volatile private var lastCheck = 0L
    /** After a failure, a period's rest, so a broken report costs little. */
    @Volatile private var retryAfter = 0L

    /** From every sample of the service; cheap unless half an hour has passed. */
    fun onTick(context: Context, scope: CoroutineScope, nowMillis: Long) {
        if (nowMillis - lastCheck < CHECK_MS || nowMillis < retryAfter) return
        lastCheck = nowMillis
        if (nowMillis - lastRun < PERIOD_MS) return
        // No grant: nothing to read, so do not start a coroutine every minute to find out.
        if (!ProShell.hasComputerGrant(context)) return
        // 700 kB of text: not in Doze, and with the screen off on battery only when long overdue; it waits for the screen.
        if (!HeavyWork.mayRun(context, lastRun.takeIf { it > 0 }?.let { nowMillis - it }, PERIOD_MS)) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                read(appContext)
            } catch (e: Exception) {
                retryAfter = nowMillis + PERIOD_MS
                Log.w(TAG, "app ops report failed", e)
            }
        }
    }

    /**
     * Reads the report now and stores what is new; returns what it showed, or null without the grant.
     * Also used by the Sensors page, which wants the state of this moment ("in use right now").
     */
    suspend fun read(context: Context): AppOpsParser.Report? {
        if (!ProShell.hasComputerGrant(context)) return null
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val report = ProShell.streamWithGrant(context, ShellService.APP_OPS) { lines ->
                AppOpsParser.parse(lines, now, ZoneId.systemDefault())
            }
            // A phone that gives no sensor line at all is not one this log can work on; do not book that as a reading.
            check(report.packages.isNotEmpty()) { "empty report" }
            val store = SensorLogStore.get(context)
            store.save(report, now)
            lastRun = now
            if (store.meta(SensorLogStore.META_SINCE) == null) store.setMeta(SensorLogStore.META_SINCE, now.toString())
            val pruned = store.meta(SensorLogStore.META_PRUNED)?.toLongOrNull() ?: 0L
            if (now - pruned > 86_400_000L) {
                store.prune(now)
                store.setMeta(SensorLogStore.META_PRUNED, now.toString())
            }
            report
        }
    }
}
