package xyz.activityplus.android.service

import xyz.activityplus.android.core.BatterySession
import xyz.activityplus.android.core.ChargeHealth
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.VoltageAverage
import xyz.activityplus.android.data.ChargingStore

/**
 * Turns every finished charge into one estimate of the battery's full capacity for the health trend.
 * The session only knows the energy, so the voltage is averaged here while the current flows in.
 */
class ChargeHealthRecorder(private val store: ChargingStore) {
    private val voltage = VoltageAverage()
    private var designMah: Double? = null

    fun onSample(s: Snapshot) {
        val b = s.battery
        designMah = b.designMah ?: designMah
        if (b.plugged && (b.currentMa ?: 0.0) > 0) voltage.add(b.voltageV)
    }

    fun onSessionEnd(session: BatterySession) {
        if (!session.charging) return
        val estimate = ChargeHealth.estimate(session, voltage.mean(), designMah)
        voltage.reset()
        if (estimate != null) runCatching { store.saveCapacity(estimate) }
    }
}
