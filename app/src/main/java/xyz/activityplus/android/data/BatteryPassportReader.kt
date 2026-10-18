package xyz.activityplus.android.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import xyz.activityplus.android.core.CapacityLevel
import xyz.activityplus.android.core.PassportRaw
import xyz.activityplus.android.pro.ProShell

/**
 * What Android keeps about the battery itself. The dates, charging policy and part status are
 * BatteryManager properties that throw SecurityException without BATTERY_STATS, so they are read only
 * with the grant. State of health may work without it on some builds, so it is tried every time.
 */
object BatteryPassportReader {
    // Property ids the public API does not name.
    private const val PROPERTY_MANUFACTURING_DATE = 7
    private const val PROPERTY_FIRST_USAGE_DATE = 8
    private const val PROPERTY_CHARGING_POLICY = 9
    private const val PROPERTY_STATE_OF_HEALTH = 10
    private const val PROPERTY_PART_STATUS = 12

    fun read(context: Context): PassportRaw {
        val battery = context.getSystemService(BatteryManager::class.java)
        val granted = ProShell.hasBatteryStats(context)
        // One property per try: a refused one must not hide the others.
        fun property(id: Int): Long? = try {
            battery.getLongProperty(id)
        } catch (_: SecurityException) {
            null
        }
        fun withGrant(id: Int): Long? = if (granted) property(id) else null

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        val level = if (Build.VERSION.SDK_INT >= 36) capacityLevel(intent) else null

        return PassportRaw(
            manufactureDate = withGrant(PROPERTY_MANUFACTURING_DATE),
            firstUseDate = withGrant(PROPERTY_FIRST_USAGE_DATE),
            chargingPolicy = withGrant(PROPERTY_CHARGING_POLICY),
            stateOfHealth = property(PROPERTY_STATE_OF_HEALTH),
            partStatus = withGrant(PROPERTY_PART_STATUS),
            capacityLevel = level,
            cycles = cycles,
            statsGranted = granted,
        )
    }

    /** Android 16 only; the caller checks the SDK. Unknown and unsupported give null. */
    private fun capacityLevel(intent: Intent?): CapacityLevel? =
        when (intent?.getIntExtra(BatteryManager.EXTRA_CAPACITY_LEVEL, -1)) {
            BatteryManager.BATTERY_CAPACITY_LEVEL_CRITICAL -> CapacityLevel.CRITICAL
            BatteryManager.BATTERY_CAPACITY_LEVEL_LOW -> CapacityLevel.LOW
            BatteryManager.BATTERY_CAPACITY_LEVEL_NORMAL -> CapacityLevel.NORMAL
            BatteryManager.BATTERY_CAPACITY_LEVEL_HIGH -> CapacityLevel.HIGH
            BatteryManager.BATTERY_CAPACITY_LEVEL_FULL -> CapacityLevel.FULL
            else -> null
        }
}
