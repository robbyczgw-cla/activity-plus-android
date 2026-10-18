package xyz.activityplus.android.core

import java.time.Instant
import java.time.ZoneId

/**
 * Throttling and heat per app. Android tells apps only when its thermal status changes (and that is
 * what [Event] stores); how hot the phone got and what drew the power comes from the minute samples
 * of the history. This puts the two side by side: which stretches the phone was throttled
 * ([episodes]) and, per app, how hot and how hungry it was while on screen and whether the phone
 * throttled during it ([perApp]). Pure, so the loader, the screens and the tests share it.
 */
object Thermal {
    /** From this status on Android reduces performance (MODERATE: "light throttling" in the AOSP docs). */
    val THROTTLING = ThermalStatus.MODERATE

    /** Two episodes closer than this are one: the status flaps around a threshold while the phone stays hot. */
    const val MERGE_GAP_MS = 2 * 60_000L

    /** Time on screen without a missing minute in between, to call it one stretch of use. */
    private const val RUN_GAP_MS = 2 * 60_000L

    /** Shorter stretches are glances, not sessions. */
    const val MIN_RUN_MINUTES = 5

    /** Fewer minutes on screen say nothing about an app's heat. */
    const val MIN_APP_MINUTES = 10

    /** An app "regularly" throttles the phone with this many episodes on at least two days ... */
    const val REGULAR_EPISODES = 3
    const val REGULAR_DAYS = 2
    /** ... and this much time throttled while on screen. */
    const val REGULAR_MINUTES = 10

    /**
     * One status change, as the thermal listener reported it. [status] null marks a gap: the monitor
     * was not running from here on, so nothing is known (an open episode ends there, not at the
     * next start). The rest is what was known at that moment.
     */
    data class Event(
        val timeMillis: Long,
        val status: ThermalStatus?,
        /** The app on screen. */
        val pkg: String? = null,
        /** 1.0: throttling starts. */
        val headroom: Double? = null,
        val batteryTempC: Double? = null,
        /** The sensor Android blamed, and the cooling devices that were limiting something (computer grant only). */
        val sensor: String? = null,
        val sensorC: Double? = null,
        val coolingActive: Int? = null,
    )

    /** A stretch with the status at [THROTTLING] or above. */
    data class Episode(
        val startMillis: Long,
        val endMillis: Long,
        val peak: ThermalStatus,
        /** The app on screen when it began. */
        val pkg: String?,
        /** Still running: [endMillis] is "now". */
        val ongoing: Boolean,
        val sensor: String? = null,
        val sensorC: Double? = null,
        val coolingActive: Int? = null,
    ) {
        val durationMillis get() = endMillis - startMillis
    }

    /** [events] oldest first. An episode still open at the end runs until [nowMillis]. */
    fun episodes(events: List<Event>, nowMillis: Long): List<Episode> {
        val out = ArrayList<Episode>()
        var open: Episode? = null
        for (e in events) {
            val hot = e.status != null && e.status >= THROTTLING
            val cur = open
            when {
                cur == null && hot -> open = Episode(
                    e.timeMillis, e.timeMillis, e.status!!, e.pkg, true, e.sensor, e.sensorC, e.coolingActive,
                )
                cur != null && hot -> {
                    // The worst moment names the reason; a later quieter event keeps it.
                    open = if (e.status!! > cur.peak) {
                        cur.copy(peak = e.status, sensor = e.sensor ?: cur.sensor, sensorC = e.sensorC ?: cur.sensorC,
                            coolingActive = e.coolingActive ?: cur.coolingActive)
                    } else cur
                }
                cur != null -> {
                    out += cur.copy(endMillis = e.timeMillis, ongoing = false)
                    open = null
                }
            }
        }
        open?.let { out += it.copy(endMillis = maxOf(nowMillis, it.startMillis), ongoing = true) }
        return merge(out)
    }

    private fun merge(list: List<Episode>): List<Episode> {
        val out = ArrayList<Episode>()
        for (e in list) {
            val prev = out.lastOrNull()
            if (prev != null && !prev.ongoing && e.startMillis - prev.endMillis < MERGE_GAP_MS) {
                val worse = if (e.peak > prev.peak) e else prev
                out[out.lastIndex] = prev.copy(
                    endMillis = e.endMillis, ongoing = e.ongoing, peak = worse.peak,
                    sensor = worse.sensor ?: prev.sensor, sensorC = worse.sensorC ?: prev.sensorC,
                    coolingActive = worse.coolingActive ?: prev.coolingActive,
                )
            } else out += e
        }
        return out
    }

    /** Episodes that touch [fromMillis] and later, the first one cut off at [fromMillis]. */
    fun since(episodes: List<Episode>, fromMillis: Long): List<Episode> =
        episodes.filter { it.endMillis > fromMillis }.map { if (it.startMillis < fromMillis) it.copy(startMillis = fromMillis) else it }

    data class Summary(val count: Int, val totalMillis: Long, val longestMillis: Long, val peak: ThermalStatus?)

    fun summary(episodes: List<Episode>) = Summary(
        episodes.size, episodes.sumOf { it.durationMillis }, episodes.maxOfOrNull { it.durationMillis } ?: 0L,
        episodes.maxOfOrNull { it.peak },
    )

    /** One history row, reduced to what the heat of an app needs. */
    data class Minute(
        val ts: Long,
        /** The app on screen (null: unknown, or the screen was off). */
        val pkg: String?,
        val screenOn: Boolean,
        val tempC: Double,
        /** Negative while discharging. */
        val powerMw: Double?,
        val charging: Boolean,
    )

    /** A stretch of use of one app. */
    data class Run(
        val startMillis: Long,
        val minutes: Int,
        val avgPowerMw: Double?,
        val maxTempC: Double,
        /** Minutes into the run when the phone started throttling; null: it did not. */
        val throttledAfterMinutes: Int?,
    )

    data class AppHeat(
        val pkg: String,
        val minutes: Int,
        val avgTempC: Double,
        val maxTempC: Double,
        /** Mean draw over the discharging minutes, positive. */
        val avgPowerMw: Double?,
        /** Minutes on screen during which the phone was throttled. */
        val throttledMinutes: Int,
        /** Episodes that ran while the app was on screen, and on how many days. */
        val episodes: Int,
        val episodeDays: Int,
        /** Stretches of at least [MIN_RUN_MINUTES], newest first. */
        val runs: List<Run>,
    ) {
        /** The usual time to throttling over the runs that throttled; the middle one, so one odd run does not decide. */
        val throttledAfterMinutes: Int?
            get() = runs.mapNotNull { it.throttledAfterMinutes }.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }

        /** The run to quote: the hottest. */
        val hottestRun: Run? get() = runs.maxByOrNull { it.maxTempC }

        val regularly get() = regularly(episodes, episodeDays, throttledMinutes)
    }

    fun regularly(episodes: Int, days: Int, throttledMinutes: Int) =
        episodes >= REGULAR_EPISODES && days >= REGULAR_DAYS && throttledMinutes >= REGULAR_MINUTES

    /**
     * Heat per app from the minute samples (oldest first) and the [episodes] of the same time.
     * Only minutes with the screen on and a known app count. The result is ranked: most time
     * throttled first, then the hotter average.
     */
    fun perApp(minutes: List<Minute>, episodes: List<Episode>, zone: ZoneId = ZoneId.systemDefault()): List<AppHeat> {
        val onScreen = minutes.filter { it.screenOn && it.pkg != null }
        val out = ArrayList<AppHeat>()
        for ((pkg, rows) in onScreen.groupBy { it.pkg!! }) {
            if (rows.size < MIN_APP_MINUTES) continue
            val hit = HashSet<Episode>()
            var throttled = 0
            for (m in rows) {
                val e = episodes.firstOrNull { overlaps(it, m.ts, m.ts + 60_000L) } ?: continue
                throttled++
                hit += e
            }
            val days = hit.mapTo(HashSet()) { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }.size
            out += AppHeat(
                pkg, rows.size, rows.sumOf { it.tempC } / rows.size, rows.maxOf { it.tempC }, avgDraw(rows),
                throttled, hit.size, days, runs(rows, episodes).reversed(),
            )
        }
        return out.sortedWith(
            compareByDescending<AppHeat> { it.throttledMinutes }.thenByDescending { it.avgTempC }.thenBy { it.pkg },
        )
    }

    /** The apps that throttle the phone regularly, worst first. */
    fun regular(apps: List<AppHeat>): List<AppHeat> = apps.filter { it.regularly }

    private fun overlaps(e: Episode, from: Long, to: Long) = e.startMillis < to && e.endMillis > from

    /** Positive mW over the minutes on battery with a reading. */
    private fun avgDraw(rows: List<Minute>): Double? =
        rows.filter { !it.charging && it.powerMw != null && it.powerMw < 0 }.map { -it.powerMw!! }.takeIf { it.isNotEmpty() }?.average()

    /** [rows] of one app, oldest first, cut into stretches wherever a minute is missing. */
    private fun runs(rows: List<Minute>, episodes: List<Episode>): List<Run> {
        val out = ArrayList<Run>()
        var current = ArrayList<Minute>()
        fun close() {
            if (current.size >= MIN_RUN_MINUTES) out += run(current, episodes)
            current = ArrayList()
        }
        for (m in rows) {
            if (current.isNotEmpty() && m.ts - current.last().ts > RUN_GAP_MS) close()
            current += m
        }
        close()
        return out
    }

    private fun run(rows: List<Minute>, episodes: List<Episode>): Run {
        val start = rows.first().ts
        val end = rows.last().ts + 60_000L
        val first = episodes.firstOrNull { overlaps(it, start, end) }
        return Run(
            start, rows.size, avgDraw(rows), rows.maxOf { it.tempC },
            first?.let { ((it.startMillis - start).coerceAtLeast(0) / 60_000L).toInt() },
        )
    }
}
