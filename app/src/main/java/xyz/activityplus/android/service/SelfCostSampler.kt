package xyz.activityplus.android.service

import android.content.Context
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.core.SelfCost
import xyz.activityplus.android.data.SelfCostStore
import xyz.activityplus.android.pro.HealthReader
import xyz.activityplus.android.pro.HealthReader.measurement
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads Android's counters for Activity+'s own uid once an hour (and when the plug changes) and
 * books what grew per day, so the "Activity+ itself" card can say what the app costs. One binder
 * call, no permission (takeMyUidSnapshot is open to the own uid), no shell. The card also reads
 * when it opens. Failures are logged and otherwise ignored.
 */
object SelfCostSampler {
    private const val TAG = "SelfCostSampler"
    /** Ticks come every few seconds; the time is checked at most once a minute. */
    private const val CHECK_MS = 60_000L

    private val running = AtomicBoolean(false)
    @Volatile private var lastRun: Long? = null
    @Volatile private var loaded = false
    @Volatile private var lastCheck = 0L
    @Volatile private var lastPlugged: Boolean? = null
    /** After a failure, a period's rest, so a phone that refuses is not asked every minute. */
    @Volatile private var retryAfter = 0L

    /** From every sample of the service; cheap unless an hour has passed. */
    fun onTick(context: Context, scope: CoroutineScope, nowMillis: Long, plugged: Boolean) {
        if (nowMillis - lastCheck < CHECK_MS || nowMillis < retryAfter) return
        lastCheck = nowMillis
        val plugChanged = lastPlugged.let { it != null && it != plugged }
        lastPlugged = plugged
        if (loaded && !SelfCost.due(nowMillis, lastRun, plugged, plugChanged)) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { record(appContext, nowMillis) }
    }

    /** Blocking; call off the main thread. Reads unless the last reading is younger than [minGapMs]. True when it stored one. */
    fun record(context: Context, nowMillis: Long = System.currentTimeMillis(), minGapMs: Long = 0): Boolean {
        // Never two at a time; whoever comes second simply skips.
        if (!running.compareAndSet(false, true)) return false
        return try {
            val store = SelfCostStore.get(context)
            val prev = store.lastReading()
            if (!loaded) {
                lastRun = prev?.timeMillis
                loaded = true
            }
            if (prev != null && nowMillis - prev.timeMillis < minGapMs) return false
            val reading = read(context, nowMillis)
            val zone = ZoneId.systemDefault()
            store.save(reading, SelfCost.interval(prev, reading), zone)
            lastRun = nowMillis
            val pruned = store.meta(SelfCostStore.META_PRUNED)?.toLongOrNull() ?: 0L
            if (nowMillis - pruned > 86_400_000L) {
                store.prune(nowMillis, zone)
                store.setMeta(SelfCostStore.META_PRUNED, nowMillis.toString())
            }
            true
        } catch (e: Exception) {
            retryAfter = nowMillis + SelfCost.PERIOD_MS
            Log.w(TAG, "own counters not readable", e)
            false
        } finally {
            running.set(false)
        }
    }

    /** Whether this phone gives the app its own counters at all; the card says so when it does not. */
    fun readable(context: Context): Boolean = runCatching { read(context, System.currentTimeMillis()) }.isSuccess

    /** Throws when the phone refuses (a vendor's HealthStats service may). */
    fun read(context: Context, nowMillis: Long): SelfCost.Reading {
        val manager = context.getSystemService(SystemHealthManager::class.java) ?: error("no SystemHealthManager")
        val s = manager.takeMyUidSnapshot()
        val base = HealthReader.counters(s)
        val data = listOf(
            UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES, UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES,
            UidHealthStats.MEASUREMENT_WIFI_RX_BYTES, UidHealthStats.MEASUREMENT_WIFI_TX_BYTES,
        ).sumOf { s.measurement(it) ?: 0L }
        return SelfCost.Reading(
            nowMillis,
            s.measurement(UidHealthStats.MEASUREMENT_REALTIME_BATTERY_MS),
            SelfCost.Counters(
                cpuMs = base.cpuMs, wakelockMs = base.wakelockMs, wakelocks = base.wakelocks, wakeups = base.wakeups,
                jobs = base.jobs, dataBytes = data,
                serviceMs = s.timeOrMeasurement(UidHealthStats.TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS) ?: 0L,
            ),
        )
    }

    /** The process-state times are documented as timers on some versions and measurements on others. */
    private fun HealthStats.timeOrMeasurement(key: Int): Long? = when {
        hasMeasurement(key) -> getMeasurement(key)
        hasTimer(key) -> getTimerTime(key)
        else -> null
    }
}
