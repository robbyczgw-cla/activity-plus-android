package xyz.activityplus.android.core

/**
 * Splits battery drain across apps. Android does not give apps per-app battery figures, so this
 * measures instead: while the screen is on, the power drawn each tick goes to the app on screen;
 * while it is off, the fuel gauge's drop between screen off and on goes to [SCREEN_OFF].
 * It is an estimate: background work of other apps during screen-on time counts toward the app on screen.
 */
class DrainTracker(private val maxTickSeconds: Double = 30.0) {
    private var lastTick: Long? = null
    private val pending = HashMap<String, DoubleArray>()
    private var offBaseline: Baseline? = null

    private data class Baseline(val time: Long, val chargeMah: Double?, val level: Double, val voltage: Double)

    /** Called every sample. [powerMw] is negative while discharging. */
    @Synchronized
    fun onTick(now: Long, screenOn: Boolean, discharging: Boolean, powerMw: Double?, foreground: String?) {
        val prev = lastTick
        lastTick = now
        if (prev == null || !screenOn || !discharging || powerMw == null || powerMw >= 0) return
        // A long gap means the phone slept; that time belongs to the screen-off measurement.
        val seconds = ((now - prev) / 1000.0).coerceAtMost(maxTickSeconds)
        if (seconds <= 0) return
        add(foreground ?: SCREEN_ON_OTHER, -powerMw * seconds / 3600.0, seconds)
    }

    @Synchronized
    fun onScreenOff(now: Long, chargeMah: Double?, level: Double, voltage: Double, discharging: Boolean) {
        offBaseline = if (discharging) Baseline(now, chargeMah, level, voltage) else null
    }

    /** Plugging in or out during screen off spoils the measurement. */
    @Synchronized
    fun onPowerChanged() {
        offBaseline = null
    }

    @Synchronized
    fun onScreenOn(now: Long, chargeMah: Double?, level: Double, voltage: Double, discharging: Boolean, capacityMah: Double?) {
        val base = offBaseline ?: return
        offBaseline = null
        lastTick = now
        if (!discharging) return
        val seconds = (now - base.time) / 1000.0
        if (seconds < 60) return
        val usedMah = if (base.chargeMah != null && chargeMah != null) {
            base.chargeMah - chargeMah
        } else if (capacityMah != null) {
            (base.level - level) * capacityMah
        } else return
        if (usedMah <= 0) return
        val volts = (base.voltage + voltage) / 2
        add(SCREEN_OFF, usedMah * volts, seconds)
    }

    private fun add(key: String, mwh: Double, seconds: Double) {
        val v = pending.getOrPut(key) { DoubleArray(2) }
        v[0] += mwh
        v[1] += seconds
    }

    /** Returns what was measured since the last call: package to (mWh, seconds). */
    @Synchronized
    fun drain(): Map<String, Pair<Double, Double>> {
        val out = pending.mapValues { it.value[0] to it.value[1] }
        pending.clear()
        return out
    }

    companion object {
        const val SCREEN_OFF = "#screen_off"
        const val SCREEN_ON_OTHER = "#screen_on"
    }
}
