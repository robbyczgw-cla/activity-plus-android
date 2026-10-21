package xyz.activityplus.android.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import xyz.activityplus.android.core.SelfCost

/**
 * Whether a heavy periodic job (a 700 kB system report, a scan of all apps) may run now; the decision
 * itself is [SelfCost.mayRunHeavy]. Reads the state from Android each time: three cheap calls, made
 * only when a job is due. Jobs the user starts (a page that asks for its data) do not ask.
 */
object HeavyWork {
    fun mayRun(context: Context, sinceLastMs: Long?, periodMs: Long): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        return SelfCost.mayRunHeavy(power.isInteractive, plugged, power.isDeviceIdleMode, sinceLastMs, periodMs)
    }
}
