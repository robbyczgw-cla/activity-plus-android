package xyz.activityplus.android.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.core.EnergySlice
import xyz.activityplus.android.core.PowerRails
import xyz.activityplus.android.core.RailBaseline
import xyz.activityplus.android.core.RailReading
import xyz.activityplus.android.data.PowerMonitors
import xyz.activityplus.android.data.PowerRailStore
import xyz.activityplus.android.pro.ProShell

/**
 * Turns the power monitors' cumulative energy into slots of energy for the "Where the power goes" card. The
 * service calls [onTick] with every sample (one reading every 5 minutes is plenty for averages, and coarse
 * readings are blurred anyway); the card calls [sample] more often while it is on screen.
 */
object PowerRailSampler {
    private const val TAG = "PowerRails"
    const val PERIOD_MS = 300_000L
    @Volatile private var lastTick = 0L

    private val lock = Any()
    /** Last reading per monitor; read from the store once, so a restart of the app loses nothing. */
    private var baselines: Map<String, RailBaseline>? = null
    private var lastRecord = 0L
    private var lastPrune = 0L

    fun onTick(context: Context, scope: CoroutineScope, nowMillis: Long) {
        if (!PowerMonitors.possible() || nowMillis - lastTick < PERIOD_MS) return
        lastTick = nowMillis
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { runCatching { sample(appContext, PERIOD_MS / 2) } }
    }

    /**
     * Reads the monitors and stores the energy since the last stored reading, unless that was less than
     * [minGapMs] ago. Returns the readings (also when nothing was stored), or null when the phone has no monitors.
     */
    suspend fun sample(context: Context, minGapMs: Long): Map<String, RailReading>? {
        val readings = PowerMonitors.read(context) ?: return null
        try {
            record(context, readings, System.currentTimeMillis(), SystemClock.elapsedRealtime(), minGapMs)
        } catch (e: Exception) {
            Log.w(TAG, "power sample failed", e)
        }
        return readings
    }

    private fun record(context: Context, readings: Map<String, RailReading>, now: Long, elapsedNow: Long, minGapMs: Long) {
        synchronized(lock) {
            if (now - lastRecord < minGapMs) return
            val store = PowerRailStore.get(context)
            val old = baselines ?: store.baselines()
            val boot = now - elapsedNow
            val noise = if (ProShell.hasFinePowerMonitors(context)) 0L else PowerRails.COARSE_NOISE_UWS
            val next = HashMap(old)
            val slices = HashMap<String, List<EnergySlice>>()
            for ((key, reading) in readings) {
                val prev = old[key]
                if (prev == null || !PowerRails.sameBoot(prev.bootMillis, boot)) {
                    next[key] = RailBaseline(reading, boot)
                    continue
                }
                when (val step = PowerRails.step(prev.reading, reading, noise)) {
                    // Android handed out the cached value; the old reading stays the better start.
                    PowerRails.Step.Unchanged -> Unit
                    PowerRails.Step.Reset -> next[key] = RailBaseline(reading, boot)
                    is PowerRails.Step.Energy -> {
                        // The reading's own time, which with coarse readings is up to 20 s older than now.
                        slices[key] = PowerRails.spread(boot + reading.timestampMs, step.ms, step.uws)
                        next[key] = RailBaseline(reading, boot)
                    }
                }
            }
            store.record(slices, next)
            baselines = next
            lastRecord = now
            // Keep 30 days; once in a while is enough.
            if (now - lastPrune > 6 * 3_600_000L) {
                lastPrune = now
                store.prune(PowerRails.slotOf(now - PowerRails.RETENTION_DAYS * 86_400_000L))
            }
        }
    }
}
