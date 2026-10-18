package xyz.activityplus.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.data.NightStore

/**
 * Records each screen-off span for the night check: wall time, elapsed and awake time, battery
 * and mobile signal at both ends, and the Doze changes in between. Event-driven only: it reads at
 * screen-off, screen-on and plug changes, and notes Doze broadcasts in memory. No polling, no
 * alarm, no wake lock; the phone is awake anyway whenever one of these broadcasts arrives. The
 * span is written when the screen comes on, so nothing touches the disk while the phone sleeps.
 */
class NightRecorder(private val context: Context, private val scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)

    /** The span running since the screen went off; only touched on the main thread (receiver). */
    private var open: Open? = null

    private class Open(
        val startMillis: Long,
        val startElapsed: Long,
        val startUptime: Long,
        val startLevel: Double,
        val startMah: Double?,
        val plugged: Boolean,
        val startSignal: Int?,
    ) {
        val doze = ArrayList<NightCheck.Doze>()
        var deep = false
        var light = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { onEvent(intent.action) }.onFailure { Log.w(TAG, "night event failed (${intent.action})", it) }
        }
    }

    /** Registers for the screen, plug and Doze broadcasts; Doze changes reach only receivers registered at runtime. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        // Started (or restarted by Android) with the screen off: the span begins now.
        if (!power.isInteractive) begin(System.currentTimeMillis(), plugged = null)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        open = null
    }

    private fun onEvent(action: String?) {
        val now = System.currentTimeMillis()
        when (action) {
            Intent.ACTION_SCREEN_OFF -> begin(now, plugged = null)
            Intent.ACTION_SCREEN_ON -> finish(now, plugged = null)
            // A plug change splits the span, so the unplugged part of a night still counts.
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val connected = action == Intent.ACTION_POWER_CONNECTED
                if (open != null) {
                    // The part before was on the other side of the plug change, whatever the gauge says yet.
                    finish(now, plugged = open?.plugged == true || !connected)
                    begin(now, plugged = connected)
                }
            }
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> open?.let { o ->
                val deep = power.isDeviceIdleMode
                if (deep != o.deep) {
                    o.deep = deep
                    note(o, NightCheck.Doze(now, deep = true, enter = deep))
                }
            }
            else -> if (Build.VERSION.SDK_INT >= 33 && action == PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED) {
                open?.let { o ->
                    val light = power.isDeviceLightIdleMode
                    if (light != o.light) {
                        o.light = light
                        note(o, NightCheck.Doze(now, deep = false, enter = light))
                    }
                }
            }
        }
    }

    /** A flapping Doze must not grow without end; a night has a few dozen changes. */
    private fun note(o: Open, d: NightCheck.Doze) {
        if (o.doze.size < MAX_EVENTS) o.doze += d
    }

    /** [plugged] null: as the battery says now. */
    private fun begin(now: Long, plugged: Boolean?) {
        val b = ActivityPlusApp.instance.monitor.batteryNow()
        open = Open(
            startMillis = now,
            startElapsed = SystemClock.elapsedRealtime(),
            startUptime = SystemClock.uptimeMillis(),
            startLevel = b.levelFraction,
            startMah = b.chargeMah,
            plugged = plugged ?: b.plugged,
            startSignal = signalLevel(),
        ).apply {
            // Doze that is already on when the span starts (a restart in the night) is an entry too.
            if (power.isDeviceIdleMode) { deep = true; doze += NightCheck.Doze(now, deep = true, enter = true) }
            if (Build.VERSION.SDK_INT >= 33 && power.isDeviceLightIdleMode) { light = true; doze += NightCheck.Doze(now, deep = false, enter = true) }
        }
    }

    /** [plugged] null: plugged if it was at the start or is now. */
    private fun finish(now: Long, plugged: Boolean?) {
        val o = open ?: return
        open = null
        val b = ActivityPlusApp.instance.monitor.batteryNow()
        val span = NightCheck.Span(
            startMillis = o.startMillis,
            endMillis = now,
            startElapsed = o.startElapsed,
            endElapsed = SystemClock.elapsedRealtime(),
            startUptime = o.startUptime,
            endUptime = SystemClock.uptimeMillis(),
            startLevel = o.startLevel,
            endLevel = b.levelFraction,
            startMah = o.startMah,
            endMah = b.chargeMah,
            plugged = plugged ?: (o.plugged || b.plugged),
            doze = o.doze.toList(),
            startSignal = o.startSignal,
            endSignal = signalLevel(),
        )
        // A glance at the lock screen is no sleep; the night check merges over such gaps anyway.
        if (span.durationMs < MIN_SPAN_MS || !NightCheck.isValid(span)) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = NightStore.get(app)
                store.save(span)
                val pruned = store.meta(NightStore.META_PRUNED)?.toLongOrNull() ?: 0L
                if (now - pruned > 86_400_000L) {
                    store.prune(now)
                    store.setMeta(NightStore.META_PRUNED, now.toString())
                }
            }.onFailure { Log.w(TAG, "saving the span failed", it) }
        }
    }

    /**
     * The mobile signal level, 0 to 4, without a permission (API 28+). Null without telephony or
     * without any cell in reach report (no SIM, airplane mode): then there is nothing to blame.
     */
    private fun signalLevel(): Int? = runCatching {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null
        val s = context.getSystemService(TelephonyManager::class.java)?.signalStrength ?: return null
        if (s.cellSignalStrengths.isEmpty()) null else s.level
    }.getOrNull()

    companion object {
        private const val TAG = "NightRecorder"
        private const val MIN_SPAN_MS = 60_000L
        private const val MAX_EVENTS = 200
    }
}
