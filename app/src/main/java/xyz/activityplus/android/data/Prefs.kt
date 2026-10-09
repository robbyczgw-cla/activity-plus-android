package xyz.activityplus.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.activityplus.android.core.Interruptions.LiveWindows

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
    /** The Monday morning report; needs [alerts] too. */
    val weekly: Boolean = true,
    /** "On screen: Chrome · drawing 2.1 W" under the values. */
    val appLine: Boolean = true,
    /** "Charged to N %" alarm: 0 is off, else 80, 85, 90 or 95. */
    val chargeLimit: Int = 0,
    val chargeFullAlarm: Boolean = false,
    val chargeWarmAlarm: Boolean = true,
    val onboarded: Boolean = false,
    /** Reads Android's battery report every few hours; only with the computer grant. */
    val backgroundMeasure: Boolean = true,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current get() = _settings.value

    init {
        syncLiveWindows(current.live)
    }

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
            weekly = sp.getBoolean("weekly", d.weekly),
            appLine = sp.getBoolean("appLine", d.appLine),
            chargeLimit = sp.getInt("chargeLimit", d.chargeLimit),
            chargeFullAlarm = sp.getBoolean("chargeFullAlarm", d.chargeFullAlarm),
            chargeWarmAlarm = sp.getBoolean("chargeWarmAlarm", d.chargeWarmAlarm),
            onboarded = sp.getBoolean("onboarded", d.onboarded),
            backgroundMeasure = sp.getBoolean("backgroundMeasure", d.backgroundMeasure),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        if (s.live != _settings.value.live) syncLiveWindows(s.live)
        sp.edit()
            .putBoolean("live", s.live)
            .putString("iconItem", s.iconItem.name)
            .putString("items", s.items.joinToString(",") { it.name })
            .putString("colorMode", s.colorMode.name)
            .putBoolean("fahrenheit", s.fahrenheit)
            .putBoolean("bits", s.bits)
            .putInt("interval", s.intervalSeconds)
            .putBoolean("alerts", s.alerts)
            .putBoolean("weekly", s.weekly)
            .putBoolean("appLine", s.appLine)
            .putInt("chargeLimit", s.chargeLimit)
            .putBoolean("chargeFullAlarm", s.chargeFullAlarm)
            .putBoolean("chargeWarmAlarm", s.chargeWarmAlarm)
            .putBoolean("onboarded", s.onboarded)
            .putBoolean("backgroundMeasure", s.backgroundMeasure)
            .apply()
        _settings.value = s
    }

    /** Last time an alert of [key] fired, for cooldowns. */
    fun lastAlert(key: String): Long = sp.getLong("alert.$key", 0)
    fun setLastAlert(key: String, time: Long) = sp.edit().putLong("alert.$key", time).apply()

    /** When the live setting was on, so a process kill counts as an interruption only then. */
    private fun syncLiveWindows(live: Boolean) {
        val windows = LiveWindows.sync(LiveWindows.decode(sp.getString("liveWindows", null)), live, System.currentTimeMillis())
        sp.edit().putString("liveWindows", LiveWindows.encode(windows)).apply()
    }

    fun wasLive(time: Long): Boolean = LiveWindows.wasOn(LiveWindows.decode(sp.getString("liveWindows", null)), time)

    /** When the user hid the "measuring was interrupted" card, for a week. */
    fun interruptionDismissed(): Long = sp.getLong("interruptionDismissed", 0)
    fun setInterruptionDismissed(time: Long) = sp.edit().putLong("interruptionDismissed", time).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().find { it.name == name } ?: fallback
}
