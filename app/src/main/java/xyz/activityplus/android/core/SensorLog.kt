package xyz.activityplus.android.core

import xyz.activityplus.android.pro.AppOpsParser
import xyz.activityplus.android.pro.AppOpsParser.Sensor
import xyz.activityplus.android.pro.AppOpsParser.Where
import java.time.Instant
import java.time.ZoneId

/**
 * The sensor log in numbers: who used location, camera, microphone and the like, and whether
 * in the foreground or in the background. Pure; the accesses come from [AppOpsParser] reports, collected
 * over time by SensorLogStore.
 *
 * Android keeps only the latest access per op, so a count here is "seen at least that many
 * times": accesses closer than [CLUSTER_MS] are one use, and a use between two reports of the
 * service is only seen when it was the latest one.
 */
object SensorLog {
    /** An access of the same app and sensor within this time belongs to the same use (a location request plus its first fix). */
    const val CLUSTER_MS = 10_000L

    /** "Right now": an access this young, or one that has not finished. */
    const val NOW_MS = 60_000L

    /** Background uses in 24 hours from which an app is worth a finding; see [manyInBackground]. */
    const val MANY_BACKGROUND = 12

    /** One access with the package it belongs to. */
    data class Access(val pkg: String, val event: AppOpsParser.Event)

    data class Live(val pkg: String, val sensor: Sensor, val where: Where, val sinceMillis: Long)

    /** One app and sensor over a span. */
    data class SensorUse(
        val uses: Int,
        val background: Int,
        val service: Int,
        val lastMillis: Long,
        val lastWhere: Where,
        /** Uses Android stopped because the permission was missing, e.g. location in the background. */
        val blocked: Int,
        /** Longest-running part of each use, summed: how long a request ran, not how often. */
        val activeMs: Long,
    )

    data class AppUse(val pkg: String, val sensors: Map<Sensor, SensorUse>) {
        val background get() = sensors.values.sumOf { it.background }
        val lastMillis get() = sensors.values.maxOf { it.lastMillis }
    }

    /** An app whose location use in the background is worth a finding. */
    data class BackgroundUse(val pkg: String, val uses: Int, val lastMillis: Long)

    /** In the order that matters to a person: the ones that watch or listen first. */
    val ORDER = listOf(Sensor.CAMERA, Sensor.MICROPHONE, Sensor.LOCATION, Sensor.BODY, Sensor.NEARBY)

    fun accesses(report: AppOpsParser.Report): List<Access> = report.events().map { (pkg, e) -> Access(pkg, e) }

    /** Splits sorted-by-time events into uses: a new one starts when the gap to the previous event is [CLUSTER_MS] or more. */
    fun clusters(events: List<AppOpsParser.Event>): List<List<AppOpsParser.Event>> {
        val out = ArrayList<MutableList<AppOpsParser.Event>>()
        for (e in events.sortedBy { it.timeMillis }) {
            val last = out.lastOrNull()
            if (last != null && e.timeMillis - last.last().timeMillis < CLUSTER_MS) last += e else out += mutableListOf(e)
        }
        return out
    }

    /** Whichever matters most for a person: background before service before screen. */
    private fun whereOf(cluster: List<AppOpsParser.Event>): Where =
        listOf(Where.BACKGROUND, Where.SERVICE, Where.SCREEN, Where.SYSTEM).firstOrNull { w -> cluster.any { it.where == w } }
            ?: Where.UNKNOWN

    /** Accesses from [fromMillis] to [toMillis] per app, the app with most background use first, then the latest. */
    fun summarize(accesses: List<Access>, fromMillis: Long, toMillis: Long): List<AppUse> {
        val real = accesses.filter { it.event.timeMillis in fromMillis..toMillis && !it.event.forOthers }
        return real.groupBy { it.pkg }.map { (pkg, list) ->
            val sensors = LinkedHashMap<Sensor, SensorUse>()
            for ((sensor, events) in list.map { it.event }.groupBy { it.op.sensor }) {
                val (rejected, granted) = events.partition { it.rejected }
                val uses = clusters(granted)
                val last = uses.lastOrNull()
                sensors[sensor] = SensorUse(
                    uses = uses.size,
                    background = uses.count { whereOf(it) == Where.BACKGROUND },
                    service = uses.count { whereOf(it) == Where.SERVICE },
                    lastMillis = last?.maxOf { it.timeMillis } ?: rejected.maxOf { it.timeMillis },
                    lastWhere = last?.let { whereOf(it) } ?: Where.UNKNOWN,
                    blocked = clusters(rejected).size,
                    activeMs = uses.sumOf { u -> u.maxOf { it.durationMs ?: 0L } },
                )
            }
            AppUse(pkg, sensors)
        }.sortedWith(compareByDescending<AppUse> { it.background }.thenByDescending { it.lastMillis })
    }

    /** What is in use at [nowMillis], from a fresh report: running accesses and those of the last minute. */
    fun rightNow(events: List<Access>, nowMillis: Long): List<Live> =
        events.filter { a ->
            val e = a.event
            !e.rejected && !e.forOthers && (e.running || nowMillis - e.timeMillis in 0..NOW_MS)
        }.groupBy { it.pkg to it.event.op.sensor }.map { (key, list) ->
            Live(key.first, key.second, whereOf(list.map { it.event }), list.minOf { it.event.timeMillis })
        }.sortedWith(compareBy<Live> { ORDER.indexOf(it.sensor) }.thenBy { it.pkg })

    /** Uses in the background by app for one sensor, most first; for the "last night" line and the finding. */
    fun backgroundUses(apps: List<AppUse>, sensor: Sensor): List<BackgroundUse> =
        apps.mapNotNull { a -> a.sensors[sensor]?.takeIf { it.background > 0 }?.let { BackgroundUse(a.pkg, it.background, it.lastMillis) } }
            .sortedByDescending { it.uses }

    /**
     * Apps that used location in the background [MANY_BACKGROUND] times or more within the span [apps] covers
     * (24 hours). The service reads every 30 minutes, so 12 means new background use was seen in a quarter
     * of the day's readings: not a geofence that fired twice, but an app that keeps asking, and
     * background location costs battery (GPS or the network radio wakes the phone each time).
     */
    fun manyInBackground(apps: List<AppUse>): List<BackgroundUse> =
        backgroundUses(apps, Sensor.LOCATION).filter { it.uses >= MANY_BACKGROUND }

    /**
     * "Last night" for this log: 00:00 to 06:00 of today, or of yesterday while it is before 06:00.
     * A fixed window and no sleep detection: the log has no screen state, and most people are not
     * using their phone then.
     */
    fun nightWindow(nowMillis: Long, zone: ZoneId): LongRange {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val day = if (now.hour < NIGHT_END_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val start = day.atStartOfDay(zone)
        return start.toInstant().toEpochMilli()..start.plusHours(NIGHT_END_HOUR.toLong()).toInstant().toEpochMilli()
    }

    const val NIGHT_END_HOUR = 6
}
