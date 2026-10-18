package xyz.activityplus.android.core

/** Battery an app drew while it was on screen, and how long that was measured (HistoryStore app_energy). */
data class MeasuredApp(val pkg: String, val mwh: Double, val seconds: Double)

/** One app's cost per hour on screen. [percentPerHour] is null when the battery capacity is not known. */
data class AppCost(val pkg: String, val seconds: Double, val averageMw: Double, val percentPerHour: Double?)

/**
 * What an app costs per hour it is on screen, measured on battery by DrainTracker. Apps with only a few
 * minutes measured are left out: a short stretch swings with whatever else the phone was doing.
 */
object UsageCost {
    const val MIN_SECONDS = 20 * 60.0

    /** Most expensive first. Without the battery capacity the ranking falls back to average power. */
    fun rank(apps: List<MeasuredApp>, batteryMwh: Double?, limit: Int = 5): List<AppCost> {
        // "#screen_off" and friends are not apps, so they have no per-hour cost of their own.
        val rows = apps
            .filter { !it.pkg.startsWith("#") && it.mwh > 0 && it.seconds >= MIN_SECONDS }
            .map { a ->
                val hours = a.seconds / 3600
                AppCost(
                    pkg = a.pkg,
                    seconds = a.seconds,
                    averageMw = a.mwh / hours,
                    percentPerHour = batteryMwh?.takeIf { it > 0 }?.let { a.mwh / it * 100 / hours },
                )
            }
        val sorted = if (batteryMwh != null && batteryMwh > 0) {
            rows.sortedByDescending { it.percentPerHour ?: 0.0 }
        } else {
            rows.sortedByDescending { it.averageMw }
        }
        return sorted.take(limit)
    }
}
