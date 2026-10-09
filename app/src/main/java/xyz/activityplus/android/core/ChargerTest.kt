package xyz.activityplus.android.core

/** What a charger and cable delivered during one test, measured at the battery. */
data class ChargerMeasurement(
    val timeMillis: Long,
    val seconds: Int,
    val avgMw: Double,
    val peakMw: Double,
    val avgVoltageV: Double,
    val avgCurrentMa: Double,
    val startLevel: Double,
    val startTempC: Double,
)

/**
 * One charger test: collects the live snapshots for [durationSeconds] and averages them.
 * Pure; the screen feeds it every new snapshot and stops when it says so.
 */
class ChargerTestRun(
    private val startMillis: Long,
    private val startLevel: Double,
    private val startTempC: Double,
    val durationSeconds: Int = 30,
    private val minSamples: Int = 5,
) {
    enum class State { RUNNING, DONE, ABORTED }

    private var count = 0
    private var powerSum = 0.0
    private var voltSum = 0.0
    private var currentSum = 0.0
    private var peak = 0.0
    private var lastTime = startMillis
    var state = State.RUNNING
        private set

    fun elapsedSeconds(now: Long) = ((now - startMillis) / 1000).toInt().coerceIn(0, durationSeconds)

    /** [charging] false (unplugged, or the phone stopped charging) ends the test. */
    fun add(now: Long, charging: Boolean, powerMw: Double?, voltageV: Double, currentMa: Double?): State {
        if (state != State.RUNNING) return state
        if (!charging) {
            state = State.ABORTED
            return state
        }
        // The start snapshot itself is skipped (now == lastTime); repeats of one snapshot as well.
        if (now > lastTime && powerMw != null && currentMa != null) {
            lastTime = now
            count++
            powerSum += powerMw
            voltSum += voltageV
            currentSum += currentMa
            peak = maxOf(peak, powerMw)
        }
        if (now - startMillis >= durationSeconds * 1000L) state = State.DONE
        return state
    }

    /** Null while running or when too few samples carried a current reading. */
    fun result(): ChargerMeasurement? {
        if (state != State.DONE || count < minSamples) return null
        return ChargerMeasurement(
            lastTime, durationSeconds, powerSum / count, peak, voltSum / count, currentSum / count, startLevel, startTempC,
        )
    }
}
