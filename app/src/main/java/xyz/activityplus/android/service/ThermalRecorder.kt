package xyz.activityplus.android.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.Thermal
import xyz.activityplus.android.core.ThermalStatus
import xyz.activityplus.android.data.ThermalStore
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.pro.ShellService
import xyz.activityplus.android.pro.ThermalParser
import java.util.concurrent.Executors

/**
 * Writes down every change of Android's thermal status with the app on screen at that moment
 * (the throttling log, see core/Thermal). Event-driven: Android calls the listener when the status
 * changes (no permission, no polling, no wake lock), and the samples the monitor takes anyway
 * catch a change the listener missed. With the computer grant it also reads the thermal service
 * when throttling starts, to name the sensor Android blamed and the cooling devices at work.
 */
class ThermalRecorder(private val context: Context, private val scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)

    // The listener's own thread: the writes keep the order of the changes, and the main thread stays free.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "thermal-log").apply { isDaemon = true } }
    private val listener = PowerManager.OnThermalStatusChangedListener { status -> note(statusOf(status), System.currentTimeMillis()) }

    @Volatile private var last: ThermalStatus? = null
    @Volatile private var lastAlive = 0L
    private var lastPrune = 0L

    private val store get() = ThermalStore.get(context)

    fun start() {
        executor.execute {
            runCatching { closeOpenEpisode() }.onFailure { Log.w(TAG, "closing the last episode failed", it) }
            // Called once with the current status right after registering.
            power.addThermalStatusListener(executor, listener)
        }
    }

    fun stop() {
        power.removeThermalStatusListener(listener)
        executor.shutdown()
    }

    /** From every sample of the service: a missed change, and the sign of life of a running episode. */
    fun onSample(s: Snapshot) {
        val status = s.thermal.status
        if (status != last) executor.execute { note(status, s.timeMillis) }
        else if (status >= Thermal.THROTTLING && s.timeMillis - lastAlive >= ALIVE_MS) {
            lastAlive = s.timeMillis
            executor.execute { runCatching { store.setMeta(ThermalStore.META_ALIVE, s.timeMillis.toString()) } }
        }
    }

    /**
     * The service died (or the phone was switched off) while throttling: nothing is known after the
     * last sign of life, so the episode ends there and not at the first status of the next start.
     */
    private fun closeOpenEpisode() {
        val prev = store.last()
        last = prev?.status
        if (prev?.status != null && prev.status >= Thermal.THROTTLING) {
            val alive = store.meta(ThermalStore.META_ALIVE)?.toLongOrNull()?.takeIf { it > prev.timeMillis } ?: prev.timeMillis
            store.save(Thermal.Event(alive + 1, status = null))
            last = null
        }
        store.prune(System.currentTimeMillis())
    }

    private fun note(status: ThermalStatus, now: Long) {
        synchronized(this) {
            val before = last
            if (status == before) return
            val rising = status >= Thermal.THROTTLING && (before == null || before < Thermal.THROTTLING)
            last = status
            lastAlive = now
            // The latest sample: fresh while the screen is on, and the screen is what names the app.
            val snap = ActivityPlusApp.instance.monitor.latest.value?.takeIf { now - it.timeMillis < SNAPSHOT_MAX_AGE_MS }
            runCatching {
                store.save(
                    Thermal.Event(
                        now, status, snap?.foregroundPackage, snap?.thermal?.headroom, snap?.battery?.temperatureC,
                    )
                )
                if (now - lastPrune > 86_400_000L) {
                    lastPrune = now
                    store.prune(now)
                }
            }.onFailure { Log.w(TAG, "saving the status failed", it) }
            if (rising) scope.launch(Dispatchers.IO) { readDetail(now) }
        }
    }

    /** The thermal service names the sensor and the cooling devices; only the computer grant can ask it unattended. */
    private suspend fun readDetail(ts: Long) {
        if (!ProShell.hasComputerGrant(context)) return
        runCatching {
            val report = ThermalParser.parse(ProShell.readWithGrant(context, ShellService.THERMAL))
            val hot = report.hottest
            store.saveDetail(ts, hot?.name, hot?.celsius, report.activeCooling)
        }.onFailure { Log.w(TAG, "reading the thermal service failed", it) }
    }

    private fun statusOf(raw: Int) = ThermalStatus.entries.getOrElse(raw) { ThermalStatus.NONE }

    private companion object {
        const val TAG = "ThermalRecorder"
        const val ALIVE_MS = 60_000L
        /** The monitor samples at least once a minute while the screen is on or off alike. */
        const val SNAPSHOT_MAX_AGE_MS = 90_000L
    }
}
