package xyz.activityplus.android.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Night check": did the phone sleep well last night, and if not, who is to blame?
 * Works on the screen-off spans NightRecorder stores: the night is picked from them, measured
 * (deep sleep, Doze, drain), compared with the user's usual night and judged. Pure, no Android types.
 */
object NightCheck {
    /** A Doze change Android broadcast while the screen was off: [deep] or light Doze, entered or left. */
    data class Doze(val timeMillis: Long, val deep: Boolean, val enter: Boolean)

    /** One screen-off span, read at screen-off and screen-on (or split at a plug change). */
    data class Span(
        val startMillis: Long,
        val endMillis: Long,
        /** SystemClock.elapsedRealtime() at both ends: runs on in CPU suspend. */
        val startElapsed: Long,
        val endElapsed: Long,
        /** SystemClock.uptimeMillis() at both ends: stops while the CPU is suspended. */
        val startUptime: Long,
        val endUptime: Long,
        val startLevel: Double,
        val endLevel: Double,
        /** Charge counter in mAh; null when the phone does not report a usable one. */
        val startMah: Double?,
        val endMah: Double?,
        /** On a charger at any time: Android does not Doze while charging, so this is no sleep span. */
        val plugged: Boolean,
        val doze: List<Doze> = emptyList(),
        /** Mobile signal level 0 (none) .. 4 (great) at both ends; null without a mobile network. */
        val startSignal: Int? = null,
        val endSignal: Int? = null,
    ) {
        val durationMs get() = (endElapsed - startElapsed).coerceAtLeast(0)

        /** Time the CPU was suspended: elapsed time grew, awake time did not. */
        val deepSleepMs get() = ((endElapsed - endUptime) - (startElapsed - startUptime)).coerceIn(0, durationMs)
    }

    /**
     * Whether the four clocks fit together. A reboot restarts both monotonic clocks, and a wall
     * clock that moved by more than a few minutes (time zone, manual change) would misplace the span.
     */
    fun isValid(s: Span): Boolean {
        if (s.endElapsed <= s.startElapsed || s.endUptime < s.startUptime) return false
        if (s.endUptime - s.startUptime > s.endElapsed - s.startElapsed) return false
        return kotlin.math.abs((s.endMillis - s.startMillis) - (s.endElapsed - s.startElapsed)) <= CLOCK_SLACK_MS
    }

    private const val CLOCK_SLACK_MS = 5 * 60_000L

    // The night window, in local time of the morning's date.

    /** Spans reaching into the evening from this hour on belong to the night. */
    const val EVENING_HOUR = 20
    /** A night has to begin before this hour of the morning; later is a nap. Same as the background report's morning. */
    const val MORNING_HOUR = 5
    /** And end before noon, like the wakeup night: a first screen-on after that ends no night. */
    const val NOON_HOUR = Wakeups.LATEST_MORNING_HOUR

    /**
     * Screen-on breaks up to this long do not end the night: a look at the clock, an alarm snoozed,
     * a message read in bed. Doze starts over after each, but the night goes on.
     */
    const val MAX_GAP_MS = 10 * 60_000L

    /** Below three hours with the screen off there is too little to judge: Doze needs an hour to go deep. */
    const val MIN_NIGHT_MS = 3 * 3_600_000L

    /** The night that ends on [nowMillis]'s morning; before 05:00 the night still runs, so the one before. */
    fun nightDate(nowMillis: Long, zone: ZoneId): LocalDate {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return if (t.hour < MORNING_HOUR) t.toLocalDate().minusDays(1) else t.toLocalDate()
    }

    private fun at(date: LocalDate, hour: Int, zone: ZoneId) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 20:00 the evening before .. noon of [date]. */
    fun window(date: LocalDate, zone: ZoneId): LongRange = at(date.minusDays(1), EVENING_HOUR, zone)..at(date, NOON_HOUR, zone)

    /** Unplugged spans in time order, merged where the screen was on for at most [MAX_GAP_MS] in between. */
    fun clusters(spans: List<Span>): List<List<Span>> {
        val out = ArrayList<MutableList<Span>>()
        for (s in spans.filter { !it.plugged }.sortedBy { it.startMillis }) {
            val last = out.lastOrNull()
            if (last != null && s.startMillis - last.last().endMillis <= MAX_GAP_MS) last += s else out += mutableListOf(s)
        }
        return out
    }

    /**
     * Last night for the morning of [date]: of the unplugged screen-off stretches (spans merged over
     * short screen-ons) that reach past 20:00 the evening before, begin before 05:00 and end before
     * noon, the longest. Null when there is none.
     */
    fun pick(spans: List<Span>, date: LocalDate, zone: ZoneId): List<Span>? {
        val evening = at(date.minusDays(1), EVENING_HOUR, zone)
        val morning = at(date, MORNING_HOUR, zone)
        val noon = at(date, NOON_HOUR, zone)
        val near = spans.filter { it.endMillis > evening - MAX_GAP_MS && it.startMillis < noon }
        return clusters(near)
            .filter { it.first().startMillis < morning && it.last().endMillis > evening && it.last().endMillis <= noon }
            .maxByOrNull { c -> c.sumOf { it.durationMs } }
    }

    enum class Verdict { SLEPT_WELL, APP_KEPT_AWAKE, SYSTEM_DID_NOT_SLEEP, HIGH_DRAIN_WHILE_ASLEEP, NOT_ENOUGH_DATA }

    /** Why, in more detail than the verdict; the card words it. */
    enum class Reason {
        GOOD,
        /** An app held wakelocks for long. */
        APP_AWAKE,
        /** An app's alarms pulled the phone out of sleep again and again. */
        APP_WAKEUPS,
        /** Android never entered deep Doze and the CPU was often awake. */
        NO_DEEP_DOZE,
        LOW_DEEP_SLEEP,
        HIGH_DRAIN,
        DRAIN_ABOVE_USUAL,
        /** Nothing recorded at all around the night: the service was not running. */
        NO_RECORD,
        CHARGED,
        SHORT,
    }

    data class Measured(
        val fromMillis: Long,
        val toMillis: Long,
        /** Screen-off time, without the short screen-ons in between. */
        val asleepMs: Long,
        /** Share of [asleepMs] with the CPU suspended. */
        val deepShare: Double,
        /** From the start of the night to the first deep Doze; null when Android never got there. */
        val firstDeepAfterMs: Long?,
        val lightReached: Boolean,
        /** Times deep Doze was left with the screen still off: maintenance windows, motion, alarms. */
        val interruptions: Int,
        /** Percent of the battery per hour, never negative. */
        val drainPerHour: Double,
        /** From the charge counter, when it was usable all night. */
        val mahPerHour: Double?,
        val weakSignal: Boolean,
    ) {
        val deepReached get() = firstDeepAfterMs != null
    }

    /**
     * A Doze exit this close to the end of a span is the screen turning on (Android leaves Doze on
     * screen-on, and the two broadcasts can arrive in either order), not an interruption.
     */
    private const val SCREEN_ON_GRACE_MS = 60_000L

    data class DozeStats(val firstDeepMillis: Long?, val lightReached: Boolean, val deepExits: Int)

    /** Deep Doze entries and exits of one span. An exit without an entry before it is not counted. */
    fun dozeStats(span: Span): DozeStats {
        var firstDeep: Long? = null
        var light = false
        var inDeep = false
        var exits = 0
        for (d in span.doze.sortedBy { it.timeMillis }) {
            if (!d.deep) {
                if (d.enter) light = true
                continue
            }
            if (d.enter) {
                if (firstDeep == null) firstDeep = d.timeMillis
                inDeep = true
            } else if (inDeep) {
                inDeep = false
                if (span.endMillis - d.timeMillis > SCREEN_ON_GRACE_MS) exits++
            }
        }
        return DozeStats(firstDeep, light || firstDeep != null, exits)
    }

    /**
     * Whether the charge counter tells the night's drain: both ends known, not rising, and close to
     * what the level says. The level moves in 1 % steps, hence the slack of 1.5 points plus half.
     * Some gauges report a counter that never moves or jumps; those fall back to the level.
     */
    fun counterUsable(mahDrop: Double, levelDrop: Double, capacityMah: Double?): Boolean {
        if (mahDrop < -1.0) return false
        if (capacityMah == null || capacityMah <= 0) return mahDrop >= 0
        val counterDrop = mahDrop / capacityMah
        return kotlin.math.abs(counterDrop - levelDrop) <= 0.015 + 0.5 * levelDrop
    }

    /** Mobile signal at night: weak when the middle reading was level 0 or 1 of 4. Wi-Fi only phones say nothing. */
    fun weakSignal(levels: List<Int>): Boolean {
        if (levels.isEmpty()) return false
        val sorted = levels.sorted()
        return sorted[(sorted.size - 1) / 2] <= 1
    }

    /**
     * What happened in the night [spans] (one cluster from [pick]). Drain per hour from the charge
     * counter when it is usable all night and the capacity is known (finer than 1 % steps), else
     * from the level. Both only over the screen-off time: the screen-ons in between are not sleep.
     */
    fun measure(spans: List<Span>, capacityMah: Double?): Measured {
        val asleep = spans.sumOf { it.durationMs }
        val hours = asleep / 3_600_000.0
        val deep = spans.sumOf { it.deepSleepMs }
        val stats = spans.map { dozeStats(it) }
        val start = spans.first().startMillis
        val firstDeep = stats.firstNotNullOfOrNull { it.firstDeepMillis }
        val levelDrop = spans.sumOf { (it.startLevel - it.endLevel).coerceAtLeast(0.0) }
        val counters = spans.all { it.startMah != null && it.endMah != null }
        val mahDrop = if (counters) spans.sumOf { it.startMah!! - it.endMah!! } else null
        val usable = mahDrop != null && counterUsable(mahDrop, levelDrop, capacityMah)
        val mahPerHour = if (usable && hours > 0) mahDrop!!.coerceAtLeast(0.0) / hours else null
        val drain = when {
            hours <= 0 -> 0.0
            mahPerHour != null && capacityMah != null && capacityMah > 0 -> mahPerHour / capacityMah * 100
            else -> levelDrop * 100 / hours
        }
        return Measured(
            fromMillis = start,
            toMillis = spans.last().endMillis,
            asleepMs = asleep,
            deepShare = if (asleep > 0) deep.toDouble() / asleep else 0.0,
            firstDeepAfterMs = firstDeep?.let { (it - start).coerceAtLeast(0) },
            lightReached = stats.any { it.lightReached },
            interruptions = stats.sumOf { it.deepExits },
            drainPerHour = drain,
            mahPerHour = mahPerHour,
            weakSignal = weakSignal(spans.flatMap { listOfNotNull(it.startSignal, it.endSignal) }),
        )
    }

    /** The user's usual night: medians, so one bad night does not move it. */
    data class Usual(val nights: Int, val drainPerHour: Double, val deepShare: Double, val interruptions: Double)

    /** Nights the usual one is the median of; two weeks follow a changed habit within days. */
    const val USUAL_NIGHTS = 14
    /** Fewer measured nights than this have no usual night: one or two say little. */
    const val MIN_USUAL_NIGHTS = 3

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        require(n > 0)
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** From the measured nights before, oldest first; the last [USUAL_NIGHTS] count. */
    fun usual(previous: List<Measured>): Usual? {
        val last = previous.takeLast(USUAL_NIGHTS)
        if (last.size < MIN_USUAL_NIGHTS) return null
        return Usual(
            last.size,
            median(last.map { it.drainPerHour }),
            median(last.map { it.deepShare }),
            median(last.map { it.interruptions.toDouble() }),
        )
    }

    // Thresholds. A phone in good deep sleep loses about 0.3 to 0.8 % per hour overnight and spends
    // over 90 % of the night with the CPU suspended; the broken Doze of 2026 ("Draingate") cost 2.3 %/h.

    /** Below this share of deep sleep the CPU was awake for more than an hour in a seven-hour night. */
    const val LOW_DEEP_SHARE = 0.70
    /** Without deep Doze, a share below this counts as not sleeping; above it the CPU slept anyway. */
    const val GOOD_DEEP_SHARE = 0.85
    /** Fifteen points below the usual night: two to five times the usual awake time. */
    const val DEEP_DROP_FROM_USUAL = 0.15
    /** Clearly above any healthy idle drain, whatever the user's usual night. */
    const val HIGH_DRAIN_PER_HOUR = 1.5
    /** Double the usual night counts as high from this rate on; below it doubling is still little. */
    const val ABOVE_USUAL_MIN_PER_HOUR = 1.0
    const val ABOVE_USUAL_FACTOR = 2.0

    /** The CPU stayed awake too much: low share, far below the usual night, or no deep Doze and not a good share. */
    fun sleptBadly(m: Measured, usual: Usual?): Boolean =
        m.deepShare < LOW_DEEP_SHARE ||
            (usual != null && m.deepShare < usual.deepShare - DEEP_DROP_FROM_USUAL) ||
            (!m.deepReached && m.deepShare < GOOD_DEEP_SHARE)

    fun drainHigh(m: Measured, usual: Usual?): Boolean =
        m.drainPerHour >= HIGH_DRAIN_PER_HOUR ||
            (usual != null && m.drainPerHour >= ABOVE_USUAL_MIN_PER_HOUR && m.drainPerHour >= ABOVE_USUAL_FACTOR * usual.drainPerHour)

    /** An app's night from Android's counters (wakeups, wakelocks) or its battery report (mAh). */
    data class Suspect(val pkg: String, val wakelockMs: Long, val wakeups: Long, val mah: Double? = null)

    /**
     * Worst first, by the same cost as the wakeup card: awake seconds plus five per wakeup. Only apps:
     * "#" rows are Android's own shared uids, and Android itself is the system verdict, not a suspect.
     */
    fun rank(suspects: List<Suspect>): List<Suspect> =
        suspects.filter { !it.pkg.startsWith("#") }.sortedWith(
            compareByDescending<Suspect> { Wakeups.score(Wakeups.Counters(wakelockMs = it.wakelockMs, wakeups = it.wakeups), night = true) }
                .thenByDescending { it.mah ?: 0.0 }
        )

    /** Enough to blame: the same bar as the wakeup card's night line, a hundred wakeups or a quarter hour awake. */
    fun standsOut(s: Suspect) = Wakeups.standsOut(Wakeups.Counters(wakelockMs = s.wakelockMs, wakeups = s.wakeups))

    /** Google Play flags a partial wakelock of two hours with the screen off as excessive. */
    const val EXCESSIVE_WAKELOCK_MS = 2 * 3_600_000L

    data class Result(
        val verdict: Verdict,
        val reason: Reason,
        val measured: Measured? = null,
        val usual: Usual? = null,
        /** The app to blame, for APP_KEPT_AWAKE. */
        val suspect: Suspect? = null,
        /** Null when no app data was there (no grant, or nothing read that night). */
        val suspects: List<Suspect>? = null,
    ) {
        /** Without app data an app may still be the cause; the card says so instead of blaming Android. */
        val appsChecked get() = suspects != null
    }

    /**
     * The verdict. Order matters: missing data first; then an app that stands out, when sleep or drain
     * went wrong; then Android not sleeping; then drain with good sleep, which points at radio or hardware.
     * A standing-out app on a good night is no verdict: the night was fine.
     */
    fun judge(night: List<Span>?, anySpan: Boolean, chargedMs: Long, capacityMah: Double?, usual: Usual?, suspects: List<Suspect>?): Result {
        val m = night?.let { measure(it, capacityMah) }
        if (m == null || m.asleepMs < MIN_NIGHT_MS) {
            val reason = when {
                !anySpan -> Reason.NO_RECORD
                chargedMs >= MIN_NIGHT_MS || chargedMs > (m?.asleepMs ?: 0) -> Reason.CHARGED
                else -> Reason.SHORT
            }
            return Result(Verdict.NOT_ENOUGH_DATA, reason, m, usual)
        }
        val ranked = suspects?.let(::rank)
        val bad = sleptBadly(m, usual)
        val high = drainHigh(m, usual)
        val culprit = ranked?.firstOrNull()?.takeIf { standsOut(it) }
        return when {
            (bad || high) && culprit != null -> Result(
                Verdict.APP_KEPT_AWAKE,
                if (Wakeups.wakeupsDominate(Wakeups.Counters(wakelockMs = culprit.wakelockMs, wakeups = culprit.wakeups))) Reason.APP_WAKEUPS
                else Reason.APP_AWAKE,
                m, usual, culprit, ranked,
            )
            bad -> Result(
                Verdict.SYSTEM_DID_NOT_SLEEP,
                if (!m.deepReached) Reason.NO_DEEP_DOZE else Reason.LOW_DEEP_SLEEP,
                m, usual, null, ranked,
            )
            high -> Result(
                Verdict.HIGH_DRAIN_WHILE_ASLEEP,
                if (m.drainPerHour >= HIGH_DRAIN_PER_HOUR) Reason.HIGH_DRAIN else Reason.DRAIN_ABOVE_USUAL,
                m, usual, null, ranked,
            )
            else -> Result(Verdict.SLEPT_WELL, Reason.GOOD, m, usual, null, ranked)
        }
    }

    data class Entry(val date: LocalDate, val result: Result)

    /**
     * The nights of [dates] (oldest first), each judged against the measured nights before it.
     * Only the last [judged] dates are returned and asked for their apps ([suspectsFor], a store
     * lookup); the earlier ones only feed the usual night.
     */
    fun history(
        spans: List<Span>,
        dates: List<LocalDate>,
        zone: ZoneId,
        capacityMah: Double?,
        judged: Int,
        suspectsFor: (Measured) -> List<Suspect>?,
    ): List<Entry> {
        val previous = ArrayList<Measured>()
        val out = ArrayList<Entry>()
        dates.forEachIndexed { i, date ->
            val w = window(date, zone)
            val inWindow = spans.filter { it.endMillis > w.first && it.startMillis < w.last }
            val night = pick(spans, date, zone)
            val charged = inWindow.filter { it.plugged }.sumOf { it.durationMs }
            val usual = usual(previous)
            val m = night?.let { measure(it, capacityMah) }
            val enough = m != null && m.asleepMs >= MIN_NIGHT_MS
            if (i >= dates.size - judged) {
                val suspects = if (enough) suspectsFor(m!!) else null
                out += Entry(date, judge(night, inWindow.isNotEmpty(), charged, capacityMah, usual, suspects))
            }
            if (enough) previous += m!!
        }
        return out
    }

    /** "ts,D,1;ts,L,0": deep or light, entered or left. Short, as a span holds a few dozen at most. */
    fun encodeDoze(events: List<Doze>): String =
        events.joinToString(";") { "${it.timeMillis},${if (it.deep) "D" else "L"},${if (it.enter) 1 else 0}" }

    fun decodeDoze(s: String?): List<Doze> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 3) return@mapNotNull null
            val t = f[0].toLongOrNull() ?: return@mapNotNull null
            val deep = when (f[1]) { "D" -> true; "L" -> false; else -> return@mapNotNull null }
            val enter = when (f[2]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
            Doze(t, deep, enter)
        }
    }
}
