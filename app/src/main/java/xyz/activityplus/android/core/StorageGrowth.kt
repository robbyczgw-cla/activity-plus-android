package xyz.activityplus.android.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** The device's storage on one day. */
data class DeviceStorageDay(val day: LocalDate, val timeMillis: Long, val totalBytes: Long, val freeBytes: Long) {
    val usedBytes get() = totalBytes - freeBytes
}

/** One app's size as Android's StorageStats reports it. */
data class AppStorageSize(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    /** App and data only: the cache comes and goes and is reported on its own. */
    val sizeBytes get() = appBytes + dataBytes
}

/** All apps on one day; only on days with usage access. */
data class AppStorageSnapshot(val day: LocalDate, val timeMillis: Long, val apps: Map<String, AppStorageSize>)

data class AppGrowth(
    val pkg: String,
    val fromBytes: Long,
    val toBytes: Long,
    /** App + data, without the cache. */
    val growthBytes: Long,
    /** Null when the app was not there at the start. */
    val percent: Double?,
    val cacheGrowthBytes: Long,
    /** Installed within the span: its whole size counts as growth. */
    val isNew: Boolean,
    val spanDays: Double,
)

data class StorageForecast(val kind: Kind, /** Only for [Kind.FILLING]. */ val daysLeft: Int? = null, val bytesPerDay: Double = 0.0) {
    enum class Kind { TOO_FEW, STABLE, FREEING, FILLING }
}

/**
 * Growth per app between daily snapshots, unusual growth, and the "storage full in N days" forecast.
 * Pure, so the thresholds are tested without a phone.
 */
object StorageGrowth {
    const val DAY_MS = 86_400_000L
    const val MB = 1_000_000L
    /** Snapshots taken before this hour wait for the next day, unless the last one is older than yesterday. */
    const val SNAPSHOT_HOUR = 3
    const val RETENTION_DAYS = 90L

    /** Grows this much in a day or a week: unusual regardless of the app's history. */
    const val BIG_BYTES = 500 * MB
    const val FACTOR = 3.0
    /** The relative rule only counts growth that is worth looking at. */
    const val MIN_RELATIVE_BYTES = 100 * MB
    /** Days of history before the window that make a "usual" growth. */
    const val MIN_BASELINE_DAYS = 7.0

    const val MIN_POINTS = 7
    const val FORECAST_DAYS = 30
    const val MIN_R2 = 0.6
    const val MAX_DAYS_LEFT = 365

    /**
     * Whether a snapshot is due: one per day, the first chance after 03:00; at once on the very
     * first run and when yesterday was missed, so a phone that is off at night still gets one a day.
     */
    fun due(now: LocalDateTime, lastDay: LocalDate?): Boolean {
        val today = now.toLocalDate()
        if (lastDay == null) return true
        if (!lastDay.isBefore(today)) return false
        return now.hour >= SNAPSHOT_HOUR || lastDay.isBefore(today.minusDays(1))
    }

    /**
     * Growth of every app from the snapshot about [days] days before the latest one (or the oldest,
     * when the history is shorter) to the latest. Apps that are gone by now are left out. Biggest first.
     */
    fun growth(snapshots: List<AppStorageSnapshot>, days: Int): List<AppGrowth> {
        val latest = snapshots.maxByOrNull { it.timeMillis } ?: return emptyList()
        val base = baseFor(snapshots, latest, days.toDouble()) ?: return emptyList()
        return between(base, latest).sortedByDescending { it.growthBytes }
    }

    private fun baseFor(snapshots: List<AppStorageSnapshot>, latest: AppStorageSnapshot, days: Double): AppStorageSnapshot? {
        // Half a day of slack: a snapshot taken a little earlier in the day still counts as "N days ago".
        val target = latest.timeMillis - (days * DAY_MS).toLong() - DAY_MS / 2
        return snapshots.filter { it.timeMillis >= target && it.timeMillis < latest.timeMillis }.minByOrNull { it.timeMillis }
    }

    private fun between(base: AppStorageSnapshot, latest: AppStorageSnapshot): List<AppGrowth> {
        val span = (latest.timeMillis - base.timeMillis).toDouble() / DAY_MS
        return latest.apps.map { (pkg, now) ->
            val before = base.apps[pkg]
            val from = before?.sizeBytes ?: 0L
            AppGrowth(
                pkg = pkg,
                fromBytes = from,
                toBytes = now.sizeBytes,
                growthBytes = now.sizeBytes - from,
                percent = if (before != null && from > 0) (now.sizeBytes - from).toDouble() / from else null,
                cacheGrowthBytes = now.cacheBytes - (before?.cacheBytes ?: 0L),
                isNew = before == null,
                spanDays = span,
            )
        }
    }

    /**
     * Apps that grew unusually in the last day or the last week: by [BIG_BYTES] or more, or by at
     * least [FACTOR] times their usual daily growth (from the history before that window, at least
     * [MIN_BASELINE_DAYS] long) and [MIN_RELATIVE_BYTES]. Fresh installs are not "growth".
     */
    fun unusual(snapshots: List<AppStorageSnapshot>): Set<String> {
        val sorted = snapshots.sortedBy { it.timeMillis }
        val latest = sorted.lastOrNull() ?: return emptySet()
        val out = HashSet<String>()
        for (window in listOf(1.0, 7.0)) {
            val start = baseFor(sorted, latest, window) ?: continue
            val windowDays = (latest.timeMillis - start.timeMillis).toDouble() / DAY_MS
            if (windowDays <= 0) continue
            val first = sorted.first()
            val baselineDays = (start.timeMillis - first.timeMillis).toDouble() / DAY_MS
            for ((pkg, now) in latest.apps) {
                val then = start.apps[pkg] ?: continue
                val grown = now.sizeBytes - then.sizeBytes
                if (grown >= BIG_BYTES) {
                    out += pkg; continue
                }
                if (grown < MIN_RELATIVE_BYTES || baselineDays < MIN_BASELINE_DAYS) continue
                val old = first.apps[pkg] ?: continue
                val usualPerDay = ((then.sizeBytes - old.sizeBytes) / baselineDays).coerceAtLeast(0.0)
                if (grown >= FACTOR * usualPerDay * windowDays) out += pkg
            }
        }
        return out
    }

    /**
     * Linear regression of the free bytes over the last [FORECAST_DAYS] days. "Full in N days" only
     * for a clear downward trend (enough points, a good fit) that ends within a year.
     */
    fun forecast(days: List<DeviceStorageDay>): StorageForecast {
        val latest = days.maxByOrNull { it.timeMillis } ?: return StorageForecast(StorageForecast.Kind.TOO_FEW)
        val from = latest.timeMillis - FORECAST_DAYS * DAY_MS - DAY_MS / 2
        val points = days.filter { it.timeMillis >= from }.sortedBy { it.timeMillis }
        if (points.size < MIN_POINTS) return StorageForecast(StorageForecast.Kind.TOO_FEW)
        val t0 = points.first().timeMillis
        val fit = fit(points.map { (it.timeMillis - t0).toDouble() / DAY_MS }, points.map { it.freeBytes.toDouble() })
            ?: return StorageForecast(StorageForecast.Kind.STABLE)
        val slope = fit.first
        val r2 = fit.second
        // Noise (a poor fit) reads as stable: there is no trend to project.
        if (r2 < MIN_R2) return StorageForecast(StorageForecast.Kind.STABLE, bytesPerDay = slope)
        if (slope >= 0) {
            // Less than 1 MB a day either way is no news.
            return StorageForecast(if (slope > MB) StorageForecast.Kind.FREEING else StorageForecast.Kind.STABLE, bytesPerDay = slope)
        }
        val left = latest.freeBytes / -slope
        if (left >= MAX_DAYS_LEFT) return StorageForecast(StorageForecast.Kind.STABLE, bytesPerDay = slope)
        return StorageForecast(StorageForecast.Kind.FILLING, left.roundToInt().coerceAtLeast(0), slope)
    }

    /** Least squares: slope per x unit and R²; null when x does not vary. A flat y fits perfectly with slope 0. */
    fun fit(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val n = x.size
        if (n < 2 || y.size != n) return null
        val mx = x.average()
        val my = y.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        val r2 = if (syy == 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
        return slope to r2
    }

    /** Used bytes for each of the last [count] days up to [today], oldest first; null where no snapshot exists. */
    fun usedSeries(days: List<DeviceStorageDay>, today: LocalDate, count: Int): List<Double?> {
        val byDay = days.associateBy { it.day }
        return (count - 1 downTo 0).map { back -> byDay[today.minusDays(back.toLong())]?.usedBytes?.toDouble() }
    }
}
