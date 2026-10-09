package xyz.activityplus.android.pro

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns two readings of `dumpsys batterystats -c` into what each app used between them.
 * Android counts per uid since the last charge and starts again from zero when the phone charges,
 * so a reading either continues the last one (subtract) or starts fresh (take it as it is).
 */
object BackgroundDelta {
    /** One stored reading. */
    data class Snapshot(
        val timeMillis: Long,
        /** Time on battery since Android's last reset. */
        val onBatteryMs: Long?,
        /** Wall clock time of Android's last reset; a new value means the counters started again. */
        val startClockMillis: Long?,
        val byUid: Map<Int, ProParser.UidBattery>,
        /** Screen, radio, idle ... that Android does not charge to an app, in mAh. */
        val hardwareMah: Double,
    )

    data class Usage(val pkg: String, val mah: Double, val cpuMs: Long, val wakelockMs: Long) {
        fun scaled(f: Double) = Usage(pkg, mah * f, (cpuMs * f).toLong(), (wakelockMs * f).toLong())
    }

    data class Interval(
        val fromMillis: Long,
        val toMillis: Long,
        /** Android had reset its counters, so the new reading is the whole interval. */
        val reset: Boolean,
        val apps: List<Usage>,
        val hardwareMah: Double,
    ) {
        val totalMah get() = apps.sumOf { it.mah } + hardwareMah
    }

    /** The start clock lives in the "bt" line: 9,0,l,bt,<starts>,<battery ms>,<battery up>,<total>,<total up>,<start clock>,... */
    fun startClock(checkin: String): Long? {
        for (line in checkin.lineSequence()) {
            val f = line.split(',')
            if (f.size >= 10 && f[2] == "l" && f[3] == "bt") return f[9].toLongOrNull()?.takeIf { it > 0 }
        }
        return null
    }

    fun snapshot(timeMillis: Long, report: ProParser.BatteryReport, startClockMillis: Long?) =
        Snapshot(timeMillis, report.onBatteryMs, startClockMillis, report.byUid, report.system.values.sum())

    /**
     * Whether Android started counting again between [prev] and [next]. The start clock says so
     * directly; without it, less time on battery, a charge in between, or clearly smaller totals do.
     * The start clock wins over a charge: a short top-up does not always reset the counters.
     */
    fun isReset(prev: Snapshot, next: Snapshot, chargedBetween: Boolean): Boolean {
        val pb = prev.onBatteryMs
        val nb = next.onBatteryMs
        if (pb != null && nb != null && nb < pb) return true
        val ps = prev.startClockMillis
        val ns = next.startClockMillis
        // A minute of slack: Android may correct the start clock when the wall clock moves.
        if (ps != null && ns != null) return kotlin.math.abs(ns - ps) > 60_000
        if (chargedBetween) return true
        val prevTotal = prev.byUid.values.sumOf { it.mah }
        return prevTotal > 1 && next.byUid.values.sumOf { it.mah } < prevTotal * 0.9
    }

    /**
     * Usage per package between two readings, or null without a previous one. [packages] is the
     * new report's own uid list; shared and system uids become [ProReport.SYSTEM] like in [ProReport].
     */
    fun interval(prev: Snapshot?, next: Snapshot, packages: Map<Int, List<String>>, chargedBetween: Boolean): Interval? {
        if (prev == null || next.timeMillis <= prev.timeMillis) return null
        val reset = isReset(prev, next, chargedBetween)
        val rows = HashMap<String, Usage>()
        for ((uid, n) in next.byUid) {
            // Uids that vanished (uninstalled apps) have nothing new to count.
            val p = if (reset) null else prev.byUid[uid]
            val mah = (n.mah - (p?.mah ?: 0.0)).coerceAtLeast(0.0)
            val cpu = (n.cpuMs - (p?.cpuMs ?: 0)).coerceAtLeast(0)
            val wake = (n.wakelockMs - (p?.wakelockMs ?: 0)).coerceAtLeast(0)
            if (mah <= 0 && cpu == 0L && wake == 0L) continue
            val pkg = packageFor(uid, packages)
            val r = rows[pkg]
            rows[pkg] = if (r == null) Usage(pkg, mah, cpu, wake) else Usage(pkg, r.mah + mah, r.cpuMs + cpu, r.wakelockMs + wake)
        }
        val hardware = if (reset) next.hardwareMah else (next.hardwareMah - prev.hardwareMah).coerceAtLeast(0.0)
        // After a reset the counted time starts at the reset, not at the last reading.
        val from = if (reset) next.startClockMillis?.coerceIn(prev.timeMillis, next.timeMillis) ?: prev.timeMillis else prev.timeMillis
        return Interval(from, next.timeMillis, reset, rows.values.sortedByDescending { it.mah }, hardware)
    }

    fun packageFor(uid: Int, packages: Map<Int, List<String>>): String {
        val pkgs = packages[uid].orEmpty()
        return if (uid >= 10_000 && pkgs.size == 1) pkgs[0] else ProReport.SYSTEM
    }

    /**
     * Splits [fromMillis]..[toMillis] over local days ("yyyy-MM-dd" to share of the time).
     * Batterystats says nothing about when inside the interval the energy went, so evenly.
     */
    fun splitByDay(fromMillis: Long, toMillis: Long, zone: ZoneId): List<Pair<String, Double>> {
        val end = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
        if (toMillis <= fromMillis) return listOf(end.toString() to 1.0)
        val out = ArrayList<Pair<String, Double>>()
        var day = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        var t = fromMillis
        val span = (toMillis - fromMillis).toDouble()
        while (day <= end) {
            val next = minOf(toMillis, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            if (next > t) out += day.toString() to (next - t) / span
            t = next
            day = day.plusDays(1)
        }
        return out
    }

    /** Battery fraction lost while not charging, from (level, charging) points in time order. */
    fun levelLost(points: List<Pair<Double, Boolean>>): Double {
        var lost = 0.0
        for (i in 1 until points.size) {
            val (a, aCharging) = points[i - 1]
            val (b, bCharging) = points[i]
            if (!aCharging && !bCharging && b < a) lost += a - b
        }
        return lost
    }

    /**
     * "Unusual" when today is at least 2.5 times the app's average over [previous] days and at
     * least 20 mAh. Needs three earlier days with readings, or every first day would look unusual.
     */
    fun unusual(todayMah: Double, previous: List<Double>): Boolean =
        previous.size >= 3 && todayMah >= 20 && todayMah >= 2.5 * previous.average()

    /** "uid:mah:cpu:wake;..." for the stored snapshot; no JSON needed for four numbers per uid. */
    fun encode(byUid: Map<Int, ProParser.UidBattery>): String =
        byUid.entries.joinToString(";") { (uid, u) -> "$uid:${u.mah}:${u.cpuMs}:${u.wakelockMs}" }

    fun decode(s: String): Map<Int, ProParser.UidBattery> {
        val out = HashMap<Int, ProParser.UidBattery>()
        for (part in s.split(';')) {
            val f = part.split(':')
            if (f.size != 4) continue
            val uid = f[0].toIntOrNull() ?: continue
            out[uid] = ProParser.UidBattery(f[1].toDoubleOrNull() ?: continue, f[2].toLongOrNull() ?: 0, f[3].toLongOrNull() ?: 0)
        }
        return out
    }
}

/** When the background reading runs; the dumpsys call is its only cost. */
object BackgroundSchedule {
    enum class Trigger { PLUG, MORNING, PERIODIC }

    const val PERIOD_MS = 3 * 3_600_000L
    /** Even plug-in and morning wait this long after the last reading: a loose cable fires many plug events. */
    const val MIN_GAP_MS = 5 * 60_000L
    const val MORNING_HOUR = 5

    fun due(trigger: Trigger, nowMillis: Long, lastMillis: Long?, plugged: Boolean): Boolean {
        val since = if (lastMillis == null) Long.MAX_VALUE else nowMillis - lastMillis
        if (since < MIN_GAP_MS) return false
        return when (trigger) {
            Trigger.PLUG, Trigger.MORNING -> true
            Trigger.PERIODIC -> !plugged && since >= PERIOD_MS
        }
    }

    /** The first screen-on after 05:00 local time each day. */
    fun isMorning(nowMillis: Long, lastMorningDay: String?, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return t.hour >= MORNING_HOUR && t.toLocalDate().toString() != lastMorningDay
    }

    fun dayKey(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /** The [n] day keys ending with [today], oldest first. */
    fun lastDays(today: LocalDate, n: Int): List<String> = (n - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
}
