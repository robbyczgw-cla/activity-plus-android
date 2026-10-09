package xyz.activityplus.android.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The weekly report: the last full week (Monday to Sunday, local time) against the week before.
 * Pure, so the loader, the screen and the notification share it and the tests drive it with plain numbers.
 */
object WeeklyReport {
    /** Fewer measured days than this and the week says too little to report. */
    const val MIN_DAYS = 3

    const val TOP = 5

    /** Plugging in for less than this is a wiggly cable, not a charge. */
    private const val MIN_CHARGE_SECONDS = 300.0

    /** Under this many hours of screen-off measuring, the drain per hour is noise. */
    private const val MIN_SCREEN_OFF_HOURS = 1.0

    /** The last full week, the week before it, and the Monday that ends the last week. */
    data class Weeks(val previous: LocalDate, val last: LocalDate, val next: LocalDate, val zone: ZoneId) {
        private fun millis(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val previousStart get() = millis(previous)
        val lastStart get() = millis(last)
        val lastEnd get() = millis(next)

        /** yyyy-MM-dd keys of the history's day column, inclusive. */
        val lastFirstDay get() = last.toString()
        val lastLastDay get() = next.minusDays(1).toString()
        val previousFirstDay get() = previous.toString()
        val previousLastDay get() = last.minusDays(1).toString()
    }

    fun weeks(now: Long, zone: ZoneId = ZoneId.systemDefault()): Weeks {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return Weeks(thisMonday.minusWeeks(2), thisMonday.minusWeeks(1), thisMonday, zone)
    }

    /** The overview card shows from Monday to Wednesday. */
    fun cardDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(now).atZone(zone).dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.WEDNESDAY

    /** The notification waits for the first sample on Monday from 08:00. */
    fun notifyTime(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return t.dayOfWeek == DayOfWeek.MONDAY && t.hour >= 8
    }

    /** What was measured in one week. Maps are per package; synthetic rows start with "#". */
    data class WeekData(
        /** pkg to (mWh, seconds), including [DrainTracker.SCREEN_OFF] and [DrainTracker.SCREEN_ON_OTHER]. */
        val energy: Map<String, Pair<Double, Double>>,
        val screenSeconds: Map<String, Long>,
        val dataBytes: Map<String, Long>,
        val sessions: List<BatterySession>,
        /** Days of the week with any energy measured. */
        val measuredDays: Int,
    )

    enum class Trend { NONE, NEW, UP, DOWN, FLAT }

    /** Change against the week before; [Trend.NONE] when there is nothing to compare with. [percent] is never negative. */
    data class Change(val trend: Trend, val percent: Int = 0)

    data class Entry(val pkg: String, val value: Double, val change: Change)

    data class Report(
        val enough: Boolean,
        val energy: List<Entry>,
        val screenTime: List<Entry>,
        val data: List<Entry>,
        /** mWh drawn with the screen on, all apps together. */
        val screenOnMwh: Double,
        val screenOnChange: Change,
        /** Average drain with the screen off, in % of the battery per hour. */
        val screenOffPercentPerHour: Double?,
        val screenOffChange: Change,
        val chargeSessions: Int,
        val chargeChange: Change,
    ) {
        /** The app named in the headline: most battery, else most screen time. */
        val headline: Headline?
            get() = energy.firstOrNull()?.let { Headline(Headline.Kind.BATTERY, it) }
                ?: screenTime.firstOrNull()?.let { Headline(Headline.Kind.SCREEN, it) }
    }

    data class Headline(val kind: Kind, val entry: Entry) {
        enum class Kind { BATTERY, SCREEN }
    }

    private val NO_CHANGE = Change(Trend.NONE)
    val EMPTY = Report(false, emptyList(), emptyList(), emptyList(), 0.0, NO_CHANGE, null, NO_CHANGE, 0, NO_CHANGE)

    fun build(weeks: Weeks, last: WeekData, previous: WeekData, capacityMah: Double?, voltageV: Double?, ownPackage: String?): Report {
        if (last.measuredDays < MIN_DAYS) return EMPTY
        fun keep(pkg: String) = !pkg.startsWith("#") && pkg != ownPackage
        fun <V : Number> Map<String, V>.apps() = filterKeys(::keep).mapValues { it.value.toDouble() }

        // Usage stats keep only a few days of events: an empty week before means "unknown", not "everything is new".
        val energyBefore = previous.energy.mapValues { it.value.first }.apps()
        val screenBefore = previous.screenSeconds.apps()
        val dataBefore = previous.dataBytes.apps()

        fun screenOn(w: WeekData) = w.energy.filterKeys { it != DrainTracker.SCREEN_OFF }.values.sumOf { it.first }
        val onNow = screenOn(last)
        val onBefore = screenOn(previous)

        val offNow = screenOffRate(last, capacityMah, voltageV)
        val offBefore = screenOffRate(previous, capacityMah, voltageV)

        val chargesNow = countCharges(last.sessions, weeks.lastStart, weeks.lastEnd)
        val chargesBefore = countCharges(previous.sessions, weeks.previousStart, weeks.lastStart)

        return Report(
            enough = true,
            energy = top(last.energy.mapValues { it.value.first }.apps(), energyBefore, energyBefore.values.any { it > 0 }),
            screenTime = top(last.screenSeconds.apps(), screenBefore, screenBefore.values.any { it > 0 }),
            data = top(last.dataBytes.apps(), dataBefore, dataBefore.values.any { it > 0 }),
            screenOnMwh = onNow,
            screenOnChange = change(onNow, onBefore, onBefore > 0, allowNew = false),
            screenOffPercentPerHour = offNow,
            screenOffChange = if (offNow != null && offBefore != null) change(offNow, offBefore, true, allowNew = false) else NO_CHANGE,
            chargeSessions = chargesNow,
            chargeChange = change(chargesNow.toDouble(), chargesBefore.toDouble(), previous.measuredDays > 0, allowNew = false),
        )
    }

    /** The biggest values, each with its change; [known] says whether the week before has data at all. */
    fun top(now: Map<String, Double>, before: Map<String, Double>, known: Boolean, count: Int = TOP): List<Entry> =
        now.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(count)
            .map { (pkg, v) -> Entry(pkg, v, change(v, before[pkg] ?: 0.0, known, allowNew = true)) }

    /** Under 5 % counts as unchanged. A zero before is "new" for apps and no comparison for totals. */
    fun change(now: Double, before: Double, known: Boolean, allowNew: Boolean): Change {
        if (!known) return NO_CHANGE
        if (before <= 0) return if (allowNew && now > 0) Change(Trend.NEW) else NO_CHANGE
        val pct = ((now - before) / before * 100).roundToInt()
        return when {
            abs(pct) < 5 -> Change(Trend.FLAT)
            pct > 0 -> Change(Trend.UP, pct)
            else -> Change(Trend.DOWN, -pct)
        }
    }

    /** Average % of the battery per hour lost with the screen off, from the "#screen_off" energy row. */
    fun screenOffRate(week: WeekData, capacityMah: Double?, voltageV: Double?): Double? {
        val (mwh, seconds) = week.energy[DrainTracker.SCREEN_OFF] ?: return null
        val hours = seconds / 3600.0
        if (hours < MIN_SCREEN_OFF_HOURS || capacityMah == null || voltageV == null || capacityMah <= 0 || voltageV <= 0) return null
        return (mwh / hours) / (capacityMah * voltageV) * 100
    }

    fun countCharges(sessions: List<BatterySession>, fromMillis: Long, toMillis: Long): Int =
        sessions.count { it.charging && it.startMillis >= fromMillis && it.startMillis < toMillis && it.seconds >= MIN_CHARGE_SECONDS }
}
