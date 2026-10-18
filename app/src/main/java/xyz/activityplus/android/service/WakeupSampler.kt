package xyz.activityplus.android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.WakeupSchedule
import xyz.activityplus.android.core.WakeupSchedule.Trigger
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.HealthReader
import xyz.activityplus.android.pro.ProShell
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads Android's per-app counters (HealthReader) every 30 minutes on battery, at plug-in and
 * unplug, and at the first screen-on of the morning, which closes the night. One binder call per
 * reading, no shell; only with BATTERY_STATS. The Battery screen also reads when it opens, so the
 * card is fresh while the service is off. Failures are logged and otherwise ignored.
 */
object WakeupSampler {
    private const val TAG = "WakeupSampler"
    /** Ticks come every few seconds; the grant and the time are checked at most once a minute. */
    private const val CHECK_MS = 60_000L

    private val running = AtomicBoolean(false)
    @Volatile private var lastRun: Long? = null
    @Volatile private var loaded = false
    @Volatile private var lastCheck = 0L
    /** After a failure, a period's rest, so a broken reading costs little. */
    @Volatile private var retryAfter = 0L
    /** Day of the last morning reading, so later screen-ons that day cost nothing. */
    @Volatile private var morningDay: String? = null

    /** From every sample of the service; cheap unless half an hour has passed. */
    fun onTick(context: Context, scope: CoroutineScope, nowMillis: Long, plugged: Boolean) {
        if (plugged || nowMillis - lastCheck < CHECK_MS || nowMillis < retryAfter) return
        lastCheck = nowMillis
        val last = lastRun
        if (loaded && last != null && nowMillis - last < WakeupSchedule.PERIOD_MS) return
        launch(context, scope, Trigger.PERIODIC, nowMillis, plugged = false)
    }

    /** From the service's receiver, on the main thread: only launches. */
    fun onBroadcast(context: Context, scope: CoroutineScope, action: String?, nowMillis: Long) {
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> launch(context, scope, Trigger.PLUG, nowMillis, plugged = true)
            Intent.ACTION_POWER_DISCONNECTED -> launch(context, scope, Trigger.UNPLUG, nowMillis, plugged = false)
            Intent.ACTION_SCREEN_ON ->
                if (BackgroundSchedule.dayKey(nowMillis, ZoneId.systemDefault()) != morningDay) {
                    launch(context, scope, Trigger.MORNING, nowMillis, plugged = null)
                }
        }
    }

    private fun launch(context: Context, scope: CoroutineScope, trigger: Trigger, nowMillis: Long, plugged: Boolean?) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { recordIfDue(appContext, trigger, nowMillis, plugged) }
    }

    /** Blocking; call off the main thread. True when it stored a reading. */
    fun recordIfDue(context: Context, trigger: Trigger, nowMillis: Long = System.currentTimeMillis(), plugged: Boolean? = null): Boolean {
        if (!ProShell.hasBatteryStats(context)) return false
        // Never two at a time; whoever comes second simply skips.
        if (!running.compareAndSet(false, true)) return false
        return try {
            run(context, trigger, nowMillis, plugged)
        } catch (e: Exception) {
            retryAfter = nowMillis + WakeupSchedule.PERIOD_MS
            Log.w(TAG, "wakeup reading failed ($trigger)", e)
            false
        } finally {
            running.set(false)
        }
    }

    private fun run(context: Context, trigger: Trigger, nowMillis: Long, pluggedHint: Boolean?): Boolean {
        val store = WakeupStore.get(context)
        val zone = ZoneId.systemDefault()
        if (!loaded) {
            lastRun = store.lastSnapshot()?.timeMillis
            loaded = true
        }
        val plugged = pluggedHint ?: runCatching { ActivityPlusApp.instance.monitor.batteryNow().plugged }.getOrDefault(false)
        if (trigger == Trigger.MORNING) {
            val stored = store.meta(WakeupStore.META_MORNING_DAY)
            if (!BackgroundSchedule.isMorning(nowMillis, stored, zone)) {
                morningDay = stored
                return false
            }
            // Marked before reading: a failing reading must not run again at every screen-on.
            val today = BackgroundSchedule.dayKey(nowMillis, zone)
            morningDay = today
            store.setMeta(WakeupStore.META_MORNING_DAY, today)
            // A reading a minute old already closes the night.
            if (!WakeupSchedule.due(trigger, nowMillis, lastRun, plugged)) {
                lastRun?.let { store.setMeta(WakeupStore.META_MORNING_TS, it.toString()) }
                return false
            }
        } else if (!WakeupSchedule.due(trigger, nowMillis, lastRun, plugged)) return false

        val reading = HealthReader.read(context) ?: return false
        val next = reading.snapshot
        val interval = Wakeups.interval(store.lastSnapshot(), next) { reading.packages[it] }
        store.save(next, interval, zone)
        lastRun = next.timeMillis
        if (trigger == Trigger.MORNING) store.setMeta(WakeupStore.META_MORNING_TS, next.timeMillis.toString())
        val pruned = store.meta(WakeupStore.META_PRUNED)?.toLongOrNull() ?: 0L
        if (next.timeMillis - pruned > 86_400_000L) {
            store.prune(next.timeMillis, zone)
            store.setMeta(WakeupStore.META_PRUNED, next.timeMillis.toString())
        }
        return true
    }
}
