package xyz.activityplus.android.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * "Remaining time by your pattern". The estimate at the current rate assumes the next hours look
 * like the last minutes; a phone does not work that way (a night costs little, an evening a lot).
 * This learns how fast the battery usually falls in each hour of the day from the last 14 days of
 * the history, on weekdays and at weekends if there is enough data, and walks that profile forward
 * from the level now. Pure, so the tests drive it with made-up days.
 */
object Forecast {
    const val WINDOW_DAYS = 14

    /** Longer gaps between two samples are a switched-off phone, not a quiet one. */
    private const val MAX_GAP_MS = 6 * 3_600_000L

    /** The level is an integer percent and bounces by one; more than this upwards is a recalibration. */
    private const val MAX_RISE = 1.5

    /** An hour of the day needs this long on battery in its bucket to be trusted ... */
    const val MIN_BUCKET_SECONDS = 1800.0

    /** ... and the profile this much in total, over at least [MIN_HOURS] hours of the day, to be used at all. */
    const val MIN_TOTAL_SECONDS = 24 * 3600.0
    const val MIN_HOURS = 16

    /** No hour is cheaper than this, so a quiet bucket cannot promise a week. */
    private const val MIN_RATE = 0.1

    /** Beyond three days the answer is "more than three days". */
    const val HORIZON_HOURS = 72

    /** The "running low" mark next to empty. */
    const val LOW = 0.15

    /** Both estimates are shown only when they are this far apart: at least 30 min and 20 % of the time left. */
    private const val DIFFERENT_MS = 30 * 60_000L
    private const val DIFFERENT_SHARE = 0.2

    /** One history row: [level] is a fraction, [charging] the battery status at that minute. */
    data class Sample(val ts: Long, val level: Double, val charging: Boolean = false)

    /**
     * Percent per hour by hour of the day (index 0..23); null where there is no trustworthy data.
     * [weekday] and [weekend] split by the local day of the week, [all] is both together.
     */
    class Profile(
        val weekday: List<Double?>,
        val weekend: List<Double?>,
        val all: List<Double?>,
        /** The mean over everything observed, for the hours nothing is known about. */
        val mean: Double?,
        val observedSeconds: Double,
    ) {
        /** Enough time on battery, spread over enough of the day. */
        val ready get() = mean != null && observedSeconds >= MIN_TOTAL_SECONDS && all.count { it != null } >= MIN_HOURS

        /** The weekend table if that hour has data there, else the usual one, else everyone's. */
        fun rate(hour: Int, weekend: Boolean): Double {
            val own = if (weekend) this.weekend[hour] else weekday[hour]
            return maxOf(MIN_RATE, own ?: all[hour] ?: mean ?: MIN_RATE)
        }

        /** The 24 hours of a day of the given kind, for a chart. */
        fun day(weekend: Boolean): List<Double?> = (0 until 24).map { h ->
            if (ready) rate(h, weekend) else null
        }
    }

    private class Bucket {
        var points = 0.0
        var seconds = 0.0
        fun rate(): Double? = if (seconds >= MIN_BUCKET_SECONDS) points / (seconds / 3600) else null
    }

    /**
     * The profile from [samples] (oldest first) that are not in a [plugged] range. Only time on
     * battery counts: charging, or sitting full on a charger, is not use. A drop between two samples
     * is spread evenly over the time between them and split at the hour boundaries, so the hours the
     * phone slept through (samples hours apart) get their share as well.
     */
    fun profile(samples: List<Sample>, plugged: List<LongRange>, zone: ZoneId = ZoneId.systemDefault()): Profile {
        val weekday = List(24) { Bucket() }
        val weekend = List(24) { Bucket() }
        val everything = List(24) { Bucket() }
        var seconds = 0.0
        var points = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.ts - a.ts
            if (dt <= 0 || dt > MAX_GAP_MS || a.charging || b.charging) continue
            if (plugged.any { a.ts <= it.last && b.ts >= it.first }) continue
            val drop = (a.level - b.level) * 100
            if (drop < -MAX_RISE) continue
            val pointsPerMs = drop.coerceAtLeast(0.0) / dt
            var from = a.ts
            while (from < b.ts) {
                val start = Instant.ofEpochMilli(from).atZone(zone)
                val boundary = start.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val to = minOf(b.ts, boundary)
                val s = (to - from) / 1000.0
                val p = pointsPerMs * (to - from)
                val own = (if (isWeekend(start)) weekend else weekday)[start.hour]
                val any = everything[start.hour]
                own.seconds += s; own.points += p
                any.seconds += s; any.points += p
                seconds += s
                points += p
                from = to
            }
        }
        return Profile(
            weekday.map { it.rate() }, weekend.map { it.rate() }, everything.map { it.rate() },
            if (seconds > 0) points / (seconds / 3600) else null, seconds,
        )
    }

    private fun isWeekend(t: ZonedDateTime) = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

    /**
     * When the level reaches [target] (a fraction) if the phone is used as [profile] says, starting
     * at [nowMillis] with [level]; [nowMillis] itself if it is there already, null beyond [HORIZON_HOURS].
     */
    fun reach(profile: Profile, nowMillis: Long, level: Double, target: Double, zone: ZoneId = ZoneId.systemDefault()): Long? {
        var remaining = (level - target) * 100
        if (remaining <= 0) return nowMillis
        val end = nowMillis + HORIZON_HOURS * 3_600_000L
        var cursor = nowMillis
        while (cursor < end) {
            val t = Instant.ofEpochMilli(cursor).atZone(zone)
            val boundary = minOf(end, t.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli())
            val rate = profile.rate(t.hour, isWeekend(t))
            val hours = (boundary - cursor) / 3_600_000.0
            if (rate * hours >= remaining) return cursor + (remaining / rate * 3_600_000.0).toLong()
            remaining -= rate * hours
            cursor = boundary
        }
        return null
    }

    /** The same from the current rate: [percentPerHour] is negative while discharging. */
    fun reachAtCurrentRate(nowMillis: Long, level: Double, target: Double, percentPerHour: Double?): Long? {
        val remaining = (level - target) * 100
        if (remaining <= 0) return nowMillis
        if (percentPerHour == null || percentPerHour > -0.2) return null
        val hours = remaining / -percentPerHour
        return if (hours > HORIZON_HOURS) null else nowMillis + (hours * 3_600_000.0).toLong()
    }

    /**
     * What the card shows for one [target]. [pattern] is null with [patternReady] when even the
     * pattern says "more than three days"; without [patternReady] there is too little history and
     * the current rate is all there is.
     */
    data class Outcome(
        val patternReady: Boolean,
        val pattern: Long?,
        val current: Long?,
    ) {
        /** Show the current-rate time next to the pattern time: only if it says something else. */
        fun differs(nowMillis: Long): Boolean {
            if (pattern == null || current == null) return false
            val left = pattern - nowMillis
            return abs(pattern - current) >= maxOf(DIFFERENT_MS, (DIFFERENT_SHARE * left).toLong())
        }
    }

    fun outcome(
        profile: Profile?, nowMillis: Long, level: Double, target: Double, percentPerHour: Double?, zone: ZoneId = ZoneId.systemDefault(),
    ): Outcome {
        val current = reachAtCurrentRate(nowMillis, level, target, percentPerHour)
        if (profile == null || !profile.ready) return Outcome(false, null, current)
        return Outcome(true, reach(profile, nowMillis, level, target, zone), current)
    }
}
