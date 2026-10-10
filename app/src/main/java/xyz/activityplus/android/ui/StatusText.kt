package xyz.activityplus.android.ui

import android.content.Context
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.ColorMode
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.data.StatusItem
import kotlin.math.abs

/** Values, labels and colors of the status bar items, shared by notification, tile and settings. */
object StatusText {
    fun label(context: Context, item: StatusItem): String = context.getString(
        when (item) {
            StatusItem.POWER -> R.string.item_power
            StatusItem.BATTERY -> R.string.item_battery
            StatusItem.TEMPERATURE -> R.string.item_temperature
            StatusItem.MEMORY -> R.string.item_memory
            StatusItem.NETWORK -> R.string.item_network
            StatusItem.CLOCK -> R.string.item_clock
            StatusItem.STORAGE -> R.string.item_storage
            StatusItem.STORAGE_USED_PCT -> R.string.item_storage_used
            StatusItem.CHARGE_RATE -> R.string.item_charge_rate
            StatusItem.CURRENT -> R.string.item_current
            StatusItem.VOLTAGE -> R.string.item_voltage
            StatusItem.TIME_LEFT -> R.string.item_time_left
            StatusItem.RAM_FREE -> R.string.item_ram_free
            StatusItem.UPLOAD -> R.string.item_upload
        }
    )

    fun value(item: StatusItem, s: Snapshot, settings: Settings): Format.Scaled? = when (item) {
        // Averaged over ~30 s: the instant value jumps with every frame the screen draws.
        StatusItem.POWER -> s.battery.avgPowerMw?.let { mw ->
            val w = Format.watts(abs(mw))
            if (mw > 0) w.copy(value = "+" + w.value) else w
        }
        StatusItem.BATTERY -> Format.Scaled(Format.number(s.battery.levelFraction * 100, 0), "%")
        StatusItem.TEMPERATURE -> Format.temperature(s.battery.temperatureC, settings.fahrenheit)
        StatusItem.MEMORY -> Format.Scaled(Format.number(s.memory.usedFraction * 100, 0), "%")
        StatusItem.NETWORK -> Format.rate(s.network.rxBytesPerSecond, settings.bits)
        StatusItem.CLOCK -> s.cpu.averageMhz?.let { Format.ghz(it) }
        StatusItem.STORAGE -> Format.bytes(s.storage.freeBytes)
        StatusItem.STORAGE_USED_PCT -> Format.Scaled(Format.number(usedPercent(s), 0), "%")
        StatusItem.CHARGE_RATE -> s.battery.percentPerHour?.let {
            Format.Scaled((if (it > 0) "+" else "") + Format.number(it, 1), "%/h")
        }
        StatusItem.CURRENT -> (s.battery.avgCurrentMa ?: s.battery.currentMa)?.let { Format.Scaled(Format.number(it, 0), "mA") }
        StatusItem.VOLTAGE -> Format.Scaled(Format.number(s.battery.voltageV, 2), "V")
        StatusItem.TIME_LEFT -> s.battery.timeLeftSeconds?.let { Format.Scaled(Format.duration(it), "") }
        StatusItem.RAM_FREE -> Format.bytes(s.memory.availableBytes)
        StatusItem.UPLOAD -> Format.rate(s.network.txBytesPerSecond, settings.bits)
    }

    /** Short text for the status bar icon: two lines, value and unit. */
    fun iconText(item: StatusItem, s: Snapshot, settings: Settings): Pair<String, String>? = when (item) {
        StatusItem.POWER -> s.battery.avgPowerMw?.let { mw ->
            val w = abs(mw) / 1000
            (if (w >= 10) Format.number(w, 0) else Format.number(w, 1)) to (if (mw > 0) "+W" else "W")
        }
        StatusItem.BATTERY -> Format.number(s.battery.levelFraction * 100, 0) to "%"
        StatusItem.TEMPERATURE -> Format.temperature(s.battery.temperatureC, settings.fahrenheit).let { it.value to it.unit }
        StatusItem.MEMORY -> Format.number(s.memory.usedFraction * 100, 0) to "RAM"
        StatusItem.NETWORK -> Format.rate(s.network.rxBytesPerSecond, settings.bits).let { r ->
            val v = r.value.toDoubleOrNull()?.let { if (it >= 100) Format.number(it, 0) else r.value.take(3).trimEnd('.', ',') } ?: r.value
            v to r.unit.substringBefore("/")
        }
        StatusItem.CLOCK -> s.cpu.averageMhz?.let { Format.number(it / 1000, 1) to "GHz" }
        StatusItem.STORAGE -> Format.bytes(s.storage.freeBytes).let { it.value.substringBefore('.').substringBefore(',') to it.unit }
        StatusItem.STORAGE_USED_PCT -> Format.number(usedPercent(s), 0) to "DISK"
        StatusItem.CHARGE_RATE -> s.battery.percentPerHour?.let { Format.number(kotlin.math.abs(it), 0) to (if (it > 0) "+%/h" else "%/h") }
        StatusItem.CURRENT -> (s.battery.avgCurrentMa ?: s.battery.currentMa)?.let { Format.number(kotlin.math.abs(it), 0) to "mA" }
        StatusItem.VOLTAGE -> Format.number(s.battery.voltageV, 2) to "V"
        StatusItem.TIME_LEFT -> s.battery.timeLeftSeconds?.let { sec ->
            if (sec >= 3600) Format.number(sec / 3600.0, if (sec >= 36_000) 0 else 1) to "h" else (sec / 60).toString() to "min"
        }
        StatusItem.RAM_FREE -> Format.bytes(s.memory.availableBytes).let { it.value to it.unit }
        StatusItem.UPLOAD -> Format.rate(s.network.txBytesPerSecond, settings.bits).let { r ->
            r.value.take(3).trimEnd('.', ',') to "↑" + r.unit.substringBefore("/")
        }
    }

    private fun usedPercent(s: Snapshot): Double = (1 - s.storage.freeFraction) * 100

    /** 0..1 load for the green-to-red color mode; null where it does not apply. */
    fun load(item: StatusItem, s: Snapshot): Double? = when (item) {
        StatusItem.POWER -> s.battery.powerMw?.let { if (it < 0) (-it / 6000.0) else 0.0 }
        StatusItem.BATTERY -> 1 - s.battery.levelFraction
        StatusItem.TEMPERATURE -> ((s.battery.temperatureC - 30) / 15).coerceIn(0.0, 1.0)
        StatusItem.MEMORY -> ((s.memory.usedFraction - 0.5) / 0.45).coerceIn(0.0, 1.0)
        StatusItem.NETWORK -> null
        StatusItem.CLOCK -> s.cpu.clockFraction
        StatusItem.STORAGE, StatusItem.STORAGE_USED_PCT -> 1 - s.storage.freeFraction
        StatusItem.CHARGE_RATE, StatusItem.TIME_LEFT -> s.battery.percentPerHour?.let { if (it < 0) -it / 25 else 0.0 }
        StatusItem.CURRENT -> s.battery.avgCurrentMa?.let { if (it < 0) -it / 1500 else 0.0 }
        StatusItem.VOLTAGE, StatusItem.UPLOAD -> null
        StatusItem.RAM_FREE -> s.memory.usedFraction
    }?.coerceIn(0.0, 1.0)

    fun metricColor(item: StatusItem): Int = when (item) {
        StatusItem.POWER, StatusItem.BATTERY -> 0xFF26A862.toInt()
        StatusItem.TEMPERATURE -> 0xFFE5484D.toInt()
        StatusItem.MEMORY -> 0xFF8B5CF6.toInt()
        StatusItem.NETWORK -> 0xFF10A7AD.toInt()
        StatusItem.CLOCK -> 0xFF3E6BFF.toInt()
        StatusItem.STORAGE, StatusItem.STORAGE_USED_PCT -> 0xFFE09412.toInt()
        StatusItem.CHARGE_RATE, StatusItem.CURRENT, StatusItem.VOLTAGE, StatusItem.TIME_LEFT -> 0xFF26A862.toInt()
        StatusItem.RAM_FREE -> 0xFF8B5CF6.toInt()
        StatusItem.UPLOAD -> 0xFF10A7AD.toInt()
    }

    /** Green to amber to red by load, like the Mac menu bar's "green to red". */
    fun loadColor(load: Double): Int = when {
        load < 0.5 -> 0xFF26A862.toInt()
        load < 0.8 -> 0xFFF2A33A.toInt()
        else -> 0xFFE5484D.toInt()
    }

    fun color(item: StatusItem, s: Snapshot, mode: ColorMode): Int? = when (mode) {
        ColorMode.METRIC -> metricColor(item)
        ColorMode.LOAD -> load(item, s)?.let { loadColor(it) } ?: metricColor(item)
        ColorMode.MONO -> null
    }
}
