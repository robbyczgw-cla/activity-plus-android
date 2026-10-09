package xyz.activityplus.android.core

/** One stretch on the charger or on battery, from plug change to plug change. */
data class BatterySession(
    val charging: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val startLevel: Double,
    val endLevel: Double,
    /** Energy into (charging) or out of (on battery) the battery, measured from the current. */
    val energyMwh: Double,
    val peakMw: Double,
    val screenOnSeconds: Double,
    val screenOffSeconds: Double,
    val maxTempC: Double,
) {
    val seconds get() = (endMillis - startMillis) / 1000.0
    val averageMw get() = if (seconds > 0) energyMwh / (seconds / 3600) else 0.0
    val levelChange get() = endLevel - startLevel

    /** Percent per hour over the whole session; positive while charging. */
    val percentPerHour get() = if (seconds >= 60) levelChange * 100 / (seconds / 3600) else null
}

/**
 * Follows plug changes and integrates the power of every sample into the running session.
 * Pure, so the service and the tests drive it the same way.
 */
class SessionTracker(private val maxTickSeconds: Double = 120.0) {
    var current: BatterySession? = null
        private set
    private var lastTick = 0L

    /** Returns the session that just ended when the plug state changed, else null. */
    @Synchronized
    fun onSample(now: Long, plugged: Boolean, level: Double, powerMw: Double?, screenOn: Boolean, tempC: Double): BatterySession? {
        val cur = current
        if (cur == null || cur.charging != plugged) {
            val finished = cur?.takeIf { it.endMillis > it.startMillis }
            current = BatterySession(plugged, now, now, level, level, 0.0, 0.0, 0.0, 0.0, tempC)
            lastTick = now
            return finished
        }
        // Sleep gaps count as screen-off time but add no energy; the level change still shows them.
        val dt = (now - lastTick) / 1000.0
        lastTick = now
        val counted = dt.coerceIn(0.0, maxTickSeconds)
        val p = powerMw?.let { if (plugged) it.coerceAtLeast(0.0) else (-it).coerceAtLeast(0.0) } ?: 0.0
        current = cur.copy(
            endMillis = now,
            endLevel = level,
            energyMwh = cur.energyMwh + p * counted / 3600,
            peakMw = maxOf(cur.peakMw, p),
            screenOnSeconds = cur.screenOnSeconds + if (screenOn) counted else 0.0,
            screenOffSeconds = cur.screenOffSeconds + if (screenOn) 0.0 else dt.coerceAtLeast(0.0),
            maxTempC = maxOf(cur.maxTempC, tempC),
        )
        return null
    }

    /** Continues a session saved before the service restarted. */
    @Synchronized
    fun resume(session: BatterySession) {
        current = session
        lastTick = session.endMillis
    }
}
