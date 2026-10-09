package xyz.activityplus.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the status bar notification can show; the Android counterpart of the Mac menu bar items. */
enum class StatusItem {
    POWER, BATTERY, TEMPERATURE, MEMORY, NETWORK, CLOCK, STORAGE,
    CHARGE_RATE, CURRENT, VOLTAGE, TIME_LEFT, RAM_FREE, UPLOAD,
}

/** Up to eight values in the expanded notification, the first four when collapsed. */
const val MAX_STATUS_ITEMS = 8

enum class ColorMode { METRIC, LOAD, MONO }

data class Settings(
    val live: Boolean = true,
    /** The value drawn into the status bar icon itself. */
    val iconItem: StatusItem = StatusItem.POWER,
    /** Up to [MAX_STATUS_ITEMS] values in the notification, in this order. */
    val items: List<StatusItem> = listOf(StatusItem.POWER, StatusItem.TEMPERATURE, StatusItem.MEMORY, StatusItem.NETWORK),
    val colorMode: ColorMode = ColorMode.METRIC,
    val fahrenheit: Boolean = false,
    val bits: Boolean = false,
    val intervalSeconds: Int = 2,
    val alerts: Boolean = true,
    /** "On screen: Chrome · drawing 2.1 W" under the values. */
    val appLine: Boolean = true,
    val onboarded: Boolean = false,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current get() = _settings.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            live = sp.getBoolean("live", d.live),
            iconItem = enumOr(sp.getString("iconItem", null), d.iconItem),
            items = sp.getString("items", null)?.split(",")?.mapNotNull { n -> StatusItem.entries.find { it.name == n } }
                ?: d.items,
            colorMode = enumOr(sp.getString("colorMode", null), d.colorMode),
            fahrenheit = sp.getBoolean("fahrenheit", d.fahrenheit),
            bits = sp.getBoolean("bits", d.bits),
            intervalSeconds = sp.getInt("interval", d.intervalSeconds),
            alerts = sp.getBoolean("alerts", d.alerts),
            appLine = sp.getBoolean("appLine", d.appLine),
            onboarded = sp.getBoolean("onboarded", d.onboarded),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        sp.edit()
            .putBoolean("live", s.live)
            .putString("iconItem", s.iconItem.name)
            .putString("items", s.items.joinToString(",") { it.name })
            .putString("colorMode", s.colorMode.name)
            .putBoolean("fahrenheit", s.fahrenheit)
            .putBoolean("bits", s.bits)
            .putInt("interval", s.intervalSeconds)
            .putBoolean("alerts", s.alerts)
            .putBoolean("appLine", s.appLine)
            .putBoolean("onboarded", s.onboarded)
            .apply()
        _settings.value = s
    }

    /** Last time an alert of [key] fired, for cooldowns. */
    fun lastAlert(key: String): Long = sp.getLong("alert.$key", 0)
    fun setLastAlert(key: String, time: Long) = sp.edit().putLong("alert.$key", time).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().find { it.name == name } ?: fallback
}
