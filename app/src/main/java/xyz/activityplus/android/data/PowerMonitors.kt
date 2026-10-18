package xyz.activityplus.android.data

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.health.SystemHealthManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.activityplus.android.core.PowerRails
import xyz.activityplus.android.core.RailReading
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Android's power monitors (SystemHealthManager, Android 15+): modeled consumers and measured rails with
 * cumulative energy. No permission is needed; ACCESS_FINE_POWER_MONITORS only makes the readings exact.
 * Older Android versions and phones without monitors count as "not supported", and the UI stays away.
 */
object PowerMonitors {
    /** Null until the list has been asked for once; the list does not change while the phone runs. */
    @Volatile private var count: Int? = null

    /** False on Android 14 and older, where there is nothing to ask. */
    fun possible() = Build.VERSION.SDK_INT >= 35

    /** Whether this phone has any monitor at all. The first call asks Android, later ones answer from the cache. */
    suspend fun supported(context: Context): Boolean {
        if (!possible()) return false
        count?.let { return it > 0 }
        val list = runCatching { Api35.list(context.applicationContext) }.getOrNull() ?: return false
        count = list.size
        return list.isNotEmpty()
    }

    /** Cumulative energy of every monitor that has a value, by [PowerRails.key]; null when unsupported or failed. */
    suspend fun read(context: Context): Map<String, RailReading>? {
        if (!supported(context)) return null
        return runCatching { Api35.read(context.applicationContext) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Everything that names an Android 15 class sits here, so older phones never load it. */
    @RequiresApi(35)
    private object Api35 {
        private val direct = Executor { it.run() }
        private var monitors: List<Pair<String, PowerMonitor>>? = null

        suspend fun list(context: Context): List<Pair<String, PowerMonitor>> {
            monitors?.let { return it }
            val manager = context.getSystemService(SystemHealthManager::class.java) ?: return emptyList()
            val raw = suspendCancellableCoroutine<List<PowerMonitor>> { cont ->
                manager.getSupportedPowerMonitors(direct) { if (cont.isActive) cont.resume(it) }
            }
            val keys = PowerRails.uniqueKeys(raw.map { (it.type == PowerMonitor.POWER_MONITOR_TYPE_CONSUMER) to it.name })
            return keys.zip(raw).also { monitors = it }
        }

        suspend fun read(context: Context): Map<String, RailReading> {
            val list = list(context)
            val manager = context.getSystemService(SystemHealthManager::class.java) ?: return emptyMap()
            val readings = suspendCancellableCoroutine<PowerMonitorReadings?> { cont ->
                manager.getPowerMonitorReadings(list.map { it.second }, direct, object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
                    override fun onResult(result: PowerMonitorReadings) {
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onError(error: RuntimeException) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } ?: return emptyMap()
            val out = HashMap<String, RailReading>()
            for ((key, monitor) in list) {
                val energy = readings.getConsumedEnergy(monitor)
                // ENERGY_UNAVAILABLE (-1): this monitor has no value right now.
                if (energy < 0) continue
                out[key] = RailReading(energy, readings.getTimestampMillis(monitor))
            }
            return out
        }
    }
}
