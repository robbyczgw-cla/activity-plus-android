package xyz.activityplus.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.SystemSampler

/**
 * The one sampling loop of the app. Screens, the status notification and the tile register as
 * clients with the interval they need; the loop runs at the shortest one and stops when nobody
 * listens. Keeps the last minutes in memory for sparklines.
 */
class Monitor(context: Context) {
    private val sampler = SystemSampler(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = HashMap<String, Long>()
    private var loop: Job? = null

    private val _latest = MutableStateFlow<Snapshot?>(null)
    val latest: StateFlow<Snapshot?> = _latest.asStateFlow()

    private val _series = MutableStateFlow(Series())
    val series: StateFlow<Series> = _series.asStateFlow()

    @Synchronized
    fun register(client: String, intervalMillis: Long) {
        clients[client] = intervalMillis
        if (loop?.isActive != true) {
            loop = scope.launch { run() }
        }
    }

    @Synchronized
    fun unregister(client: String) {
        clients.remove(client)
        if (clients.isEmpty()) {
            loop?.cancel()
            loop = null
        }
    }

    /** A fresh battery reading outside the loop; battery() keeps no counters, so this is safe. */
    fun batteryNow() = sampler.battery()

    @Synchronized
    private fun interval(): Long = clients.values.minOrNull() ?: 5_000L

    private val recentCurrent = ArrayDeque<Pair<Long, Double>>()

    /** Adds the ~30 s average of the current; drops it after a plug change so rates do not mix. */
    private fun smoothed(s: Snapshot): Snapshot {
        val ma = s.battery.currentMa ?: return s
        val sign = ma >= 0
        if (recentCurrent.isNotEmpty() && (recentCurrent.last().second >= 0) != sign) recentCurrent.clear()
        recentCurrent.addLast(s.timeMillis to ma)
        while (recentCurrent.isNotEmpty() && s.timeMillis - recentCurrent.first().first > 30_000) recentCurrent.removeFirst()
        return s.copy(battery = s.battery.copy(avgCurrentMa = recentCurrent.map { it.second }.average()))
    }

    private suspend fun run() {
        while (scope.isActive) {
            val snapshot = runCatching { smoothed(sampler.sample()) }.getOrNull()
            if (snapshot != null) {
                _latest.value = snapshot
                _series.value = _series.value.adding(snapshot)
            }
            delay(interval())
        }
    }

    /** Sparkline data: one point per tick, capped at [CAPACITY]. */
    data class Series(
        val times: List<Long> = emptyList(),
        val power: List<Double?> = emptyList(),
        val current: List<Double?> = emptyList(),
        val memory: List<Double> = emptyList(),
        val clock: List<Double?> = emptyList(),
        val rx: List<Double> = emptyList(),
        val tx: List<Double> = emptyList(),
        val temperature: List<Double> = emptyList(),
        val level: List<Double> = emptyList(),
    ) {
        fun adding(s: Snapshot) = Series(
            times = (times + s.timeMillis).takeLast(CAPACITY),
            power = (power + s.battery.powerMw).takeLast(CAPACITY),
            current = (current + s.battery.currentMa).takeLast(CAPACITY),
            memory = (memory + s.memory.usedFraction).takeLast(CAPACITY),
            clock = (clock + s.cpu.clockFraction).takeLast(CAPACITY),
            rx = (rx + s.network.rxBytesPerSecond).takeLast(CAPACITY),
            tx = (tx + s.network.txBytesPerSecond).takeLast(CAPACITY),
            temperature = (temperature + s.battery.temperatureC).takeLast(CAPACITY),
            level = (level + s.battery.levelFraction).takeLast(CAPACITY),
        )

        companion object {
            const val CAPACITY = 150
        }
    }
}
