package xyz.activityplus.android.core

/**
 * "Measuring was interrupted": some makers stop apps in the background, and Android records why a
 * process ended. Only kills by the system count; crashes, ANRs and closing the app by hand do not.
 */
object Interruptions {
    const val WINDOW_MILLIS = 7 * 86_400_000L

    /** Below this the Overview stays quiet: one kill is not a pattern. */
    const val MIN_COUNT = 2

    /** One entry of ActivityManager.getHistoricalProcessExitReasons; [reason] is ApplicationExitInfo.REASON_*. */
    data class Exit(val reason: Int, val timestampMillis: Long)

    data class Count(val count: Int, val latestMillis: Long?)

    // ApplicationExitInfo: LOW_MEMORY (3), EXCESSIVE_RESOURCE_USAGE (9), OTHER (13), FREEZER (14, API 34).
    // Newer values are plain ints here, so no SDK guard is needed: older systems never report them.
    private val SYSTEM_KILLS = setOf(3, 9, 13, 14)

    /** System kills in the last week that happened while [wasLive] said measuring was on. */
    fun count(exits: List<Exit>, now: Long, wasLive: (Long) -> Boolean = { true }): Count {
        val kills = exits.filter {
            it.reason in SYSTEM_KILLS && it.timestampMillis in (now - WINDOW_MILLIS)..now && wasLive(it.timestampMillis)
        }
        return Count(kills.size, kills.maxOfOrNull { it.timestampMillis })
    }

    /** Makers with their own battery menu; OnePlus, Oppo and Realme share one, Huawei and Honor have none yet. */
    enum class Maker { VIVO, XIAOMI, SAMSUNG, ONEPLUS, OPPO, REALME, HUAWEI, HONOR, OTHER }

    /** From Build.MANUFACTURER. */
    fun maker(manufacturer: String): Maker = when (manufacturer.trim().lowercase()) {
        "vivo" -> Maker.VIVO
        "xiaomi", "redmi", "poco" -> Maker.XIAOMI
        "samsung" -> Maker.SAMSUNG
        "oneplus" -> Maker.ONEPLUS
        "oppo" -> Maker.OPPO
        "realme" -> Maker.REALME
        "huawei" -> Maker.HUAWEI
        "honor" -> Maker.HONOR
        else -> Maker.OTHER
    }

    /**
     * Times when the live status setting was on. The setting decides whether a kill counts, not whether the
     * service runs: a service the system killed is exactly what we want to see.
     */
    object LiveWindows {
        /** [end] is null while the setting is still on. */
        data class Window(val start: Long, val end: Long? = null)

        /** Opens a window while [live] is on, closes it when off, and drops windows older than the week. */
        fun sync(windows: List<Window>, live: Boolean, now: Long): List<Window> {
            val kept = windows.filter { (it.end ?: now) >= now - WINDOW_MILLIS }
            val open = kept.lastOrNull()?.takeIf { it.end == null }
            return when {
                live && open == null -> kept + Window(now)
                !live && open != null -> kept.dropLast(1) + open.copy(end = now)
                else -> kept
            }
        }

        fun wasOn(windows: List<Window>, time: Long): Boolean =
            windows.any { time >= it.start && (it.end == null || time <= it.end) }

        /** "start:end;start:" as stored in preferences; an open window has no end. */
        fun encode(windows: List<Window>): String = windows.joinToString(";") { "${it.start}:${it.end ?: ""}" }

        fun decode(text: String?): List<Window> = text.orEmpty().split(";").mapNotNull { w ->
            val parts = w.split(":")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Window(start, parts.getOrNull(1)?.toLongOrNull())
        }
    }
}
