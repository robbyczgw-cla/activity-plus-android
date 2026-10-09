package xyz.activityplus.android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.pro.BackgroundDelta
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.BackgroundSchedule.Trigger
import xyz.activityplus.android.pro.ProParser
import xyz.activityplus.android.pro.ProShell
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads Android's battery report on its own: at plug-in (before Android resets its counters),
 * at the first screen-on of the morning, and every 3 hours on battery. Only with the computer
 * grant and the setting on; never through Shizuku. Failures are logged and otherwise ignored.
 */
class BackgroundSampler(private val context: Context, private val scope: CoroutineScope) {
    private val app get() = ActivityPlusApp.instance
    private val running = AtomicBoolean(false)
    /** Time of the last stored reading; read from the store on the first run. */
    @Volatile private var lastRun: Long? = null
    @Volatile private var loaded = false
    /** Last periodic check, so a phone without the grant is not asked every tick. */
    @Volatile private var lastCheck = 0L
    /** After a failed reading the periodic one waits a full period, so a broken report costs little. */
    @Volatile private var retryAfter = 0L
    /** Day of the last morning reading, so later screen-ons that day cost nothing. */
    @Volatile private var morningDay: String? = null

    private val store get() = BackgroundStore.get(context)

    /** From the service's receiver, on the main thread: only launches. */
    fun onBroadcast(action: String?, nowMillis: Long) {
        if (!app.prefs.current.backgroundMeasure) return
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> launch(Trigger.PLUG, nowMillis, plugged = true)
            Intent.ACTION_SCREEN_ON ->
                if (BackgroundSchedule.dayKey(nowMillis, ZoneId.systemDefault()) != morningDay) launch(Trigger.MORNING, nowMillis, plugged = null)
            // Remembered so a charge in between is known even when Android hides its start clock.
            Intent.ACTION_POWER_DISCONNECTED -> scope.launch(Dispatchers.IO) {
                runCatching { if (ProShell.hasComputerGrant(context)) store.setMeta(BackgroundStore.META_UNPLUGGED, nowMillis.toString()) }
            }
        }
    }

    /** From every sample; cheap unless 3 hours have passed. */
    fun onTick(nowMillis: Long, plugged: Boolean) {
        if (plugged || !app.prefs.current.backgroundMeasure) return
        val last = lastRun
        if (loaded && last != null && nowMillis - last < BackgroundSchedule.PERIOD_MS) return
        if (nowMillis - lastCheck < CHECK_MS || nowMillis < retryAfter) return
        lastCheck = nowMillis
        launch(Trigger.PERIODIC, nowMillis, plugged = false)
    }

    private fun launch(trigger: Trigger, nowMillis: Long, plugged: Boolean?) {
        // Never two at a time; a trigger that comes during a run is simply dropped.
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                run(trigger, nowMillis, plugged)
            } catch (e: Exception) {
                retryAfter = nowMillis + BackgroundSchedule.PERIOD_MS
                Log.w(TAG, "background reading failed ($trigger)", e)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun run(trigger: Trigger, nowMillis: Long, pluggedHint: Boolean?) {
        if (!ProShell.hasComputerGrant(context)) return
        val zone = ZoneId.systemDefault()
        if (!loaded) {
            lastRun = store.lastSnapshot()?.timeMillis
            loaded = true
        }
        val plugged = pluggedHint ?: runCatching { app.monitor.batteryNow().plugged }.getOrDefault(false)
        if (trigger == Trigger.MORNING) {
            val stored = store.meta(BackgroundStore.META_MORNING_DAY)
            if (!BackgroundSchedule.isMorning(nowMillis, stored, zone)) {
                morningDay = stored
                return
            }
            // Marked before reading: a failing report must not run again at every screen-on.
            val today = BackgroundSchedule.dayKey(nowMillis, zone)
            morningDay = today
            store.setMeta(BackgroundStore.META_MORNING_DAY, today)
            // A reading a few minutes old already marks the morning.
            if (!BackgroundSchedule.due(trigger, nowMillis, lastRun, plugged)) {
                lastRun?.let { store.setMeta(BackgroundStore.META_MORNING_TS, it.toString()) }
                return
            }
        } else if (!BackgroundSchedule.due(trigger, nowMillis, lastRun, plugged)) return

        val text = ProShell.batteryWithGrant(context)
        val time = System.currentTimeMillis()
        val report = ProParser.batteryCheckin(text)
        check(report.byUid.isNotEmpty()) { "empty report" }
        val next = BackgroundDelta.snapshot(time, report, BackgroundDelta.startClock(text))
        val prev = store.lastSnapshot()
        val unplugged = store.meta(BackgroundStore.META_UNPLUGGED)?.toLongOrNull() ?: 0L
        val charged = prev != null && unplugged > prev.timeMillis
        val interval = BackgroundDelta.interval(prev, next, report.packages, charged)
        store.save(next, interval, zone)
        lastRun = time
        if (trigger == Trigger.MORNING) store.setMeta(BackgroundStore.META_MORNING_TS, time.toString())
        val pruned = store.meta(BackgroundStore.META_PRUNED)?.toLongOrNull() ?: 0L
        if (time - pruned > 86_400_000L) {
            store.prune(time, zone)
            store.setMeta(BackgroundStore.META_PRUNED, time.toString())
        }
    }

    companion object {
        private const val TAG = "BackgroundSampler"
        private const val CHECK_MS = 15 * 60_000L

        /** Whether the feature runs at all: the setting and the computer grant. */
        fun enabled(context: Context) = ActivityPlusApp.instance.prefs.current.backgroundMeasure && ProShell.hasComputerGrant(context)
    }
}
