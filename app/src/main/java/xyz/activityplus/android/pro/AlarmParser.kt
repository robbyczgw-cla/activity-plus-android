package xyz.activityplus.android.pro

/**
 * Reads `dumpsys alarm`: the alarms each app has set (pending), and Android's per-app alarm counts
 * since the phone started. Pure text parsing, tested against output of the Android 15 emulator and
 * the older layout of AOSP's dump code (Android 10/11). Unknown lines are skipped, a missing
 * section gives an empty result: phone makers add their own lines here.
 */
object AlarmParser {
    enum class Type(val wakeup: Boolean) { RTC_WAKEUP(true), RTC(false), ELAPSED_WAKEUP(true), ELAPSED(false) }

    // AlarmManager's flags as dumped in "flags=0x..". Only the ones that change when an alarm goes off.
    /** setAlarmClock(): the user's own alarm, always on time. */
    const val FLAG_WAKE_FROM_IDLE = 0x2
    /** setAndAllowWhileIdle() / setExactAndAllowWhileIdle(). */
    const val FLAG_ALLOW_WHILE_IDLE = 0x4
    /** The same for Android itself and allow-listed apps, without the quota. */
    const val FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED = 0x8
    /** Allow-while-idle under the older, stricter quota (apps on Doze's allow list since Android 12). */
    const val FLAG_ALLOW_WHILE_IDLE_COMPAT = 0x20

    data class Alarm(
        val pkg: String,
        val type: Type,
        /** Wall clock time it is due; null when the report has no clock or it lies more than a year ahead. */
        val whenMillis: Long?,
        /** 0 for a one-time alarm. */
        val repeatMs: Long,
        /** How much later Android may deliver it to bundle alarms: 0 is an exact alarm; null when not reported. */
        val windowMs: Long?,
        val flags: Int,
        /** The app's own name for it, without Android's "*walarm*:" prefix. */
        val tag: String,
    ) {
        val wakeup get() = type.wakeup
        val exact get() = windowMs == 0L
        /** Goes off in Doze too; ordinary alarms wait for Doze's maintenance windows. */
        val allowWhileIdle get() =
            flags and (FLAG_ALLOW_WHILE_IDLE or FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED or FLAG_ALLOW_WHILE_IDLE_COMPAT) != 0
        val alarmClock get() = flags and FLAG_WAKE_FROM_IDLE != 0
    }

    /** One alarm tag's deliveries since the phone started. */
    data class TagStats(val tag: String, val runningMs: Long, val wakeups: Long, val count: Long)

    data class PackageStats(val pkg: String, val runningMs: Long, val wakeups: Long, val count: Long, val tags: List<TagStats>)

    data class Report(
        /** Wall clock time of the report; null when the header is missing. */
        val nowMillis: Long?,
        /** Time since the phone started: what [stats] cover. */
        val sinceBootMs: Long?,
        val alarms: List<Alarm>,
        /** By package. Several uids with the same package (work profile) are added up. */
        val stats: Map<String, PackageStats>,
    )

    /** Further ahead than this is "never" in practice (apps park alarms ten years out). */
    private const val MAX_AHEAD_MS = 400L * 86_400_000

    private val nowLine = Regex("""nowRTC=(\d+).*?\bnowELAPSED=(\d+)""")
    // Android 12+: Alarm{c9094d0 type 2 origWhen 146526 whenElapsed 146526 android}
    // Android 10/11: Alarm{c9094d0 type 2 when 146526 android}
    private val alarmHeader = Regex(
        """^\s*(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED)\s+#\d+:\s+Alarm\{\S+ type (\d+) (?:origWhen|when) (-?\d+)(?: whenElapsed (-?\d+))? ([^\s}]+)\}"""
    )
    private val windowField = Regex("""(?:^|\s)window=(\S+)""")
    private val repeatField = Regex("""(?:^|\s)repeatInterval=(\d+)""")
    private val flagsField = Regex("""(?:^|\s)flags=0x([0-9a-fA-F]+)""")
    // "  u0a101:com.android.providers.calendar +97ms running, 1 wakeups:"
    private val statsPackage = Regex("""^\s*([0-9a-z]+):(\S+) (\S+) running, (\d+) wakeups:\s*$""")
    // "    +97ms 1 wakes 1 alarms, last -1m40s219ms:"
    private val statsTag = Regex("""^\s*(\S+) (\d+) wakes (\d+) alarms(?:,.*)?:\s*$""")
    // "    +97ms running, 1 wakeups, 1 alarms: u0a101:com.android.providers.calendar"
    private val topLine = Regex("""^\s*(\S+) running, (\d+) wakeups, (\d+) alarms: ([0-9a-z]+):(\S+)\s*$""")

    fun parse(text: String): Report {
        val lines = text.lines()
        var nowRtc: Long? = null
        var nowElapsed: Long? = null
        lines.firstNotNullOfOrNull { nowLine.find(it) }?.let {
            nowRtc = it.groupValues[1].toLongOrNull()
            nowElapsed = it.groupValues[2].toLongOrNull()
        }
        return Report(
            nowMillis = nowRtc,
            sinceBootMs = nowElapsed,
            alarms = alarms(lines, nowRtc, nowElapsed),
            stats = stats(lines, "Alarm Stats:").ifEmpty { topAlarms(lines) },
        )
    }

    private fun alarms(lines: List<String>, nowRtc: Long?, nowElapsed: Long?): List<Alarm> {
        val out = ArrayList<Alarm>()
        var i = 0
        while (i < lines.size) {
            val m = alarmHeader.find(lines[i])
            if (m == null) {
                i++; continue
            }
            val type = m.groupValues[2].toIntOrNull()?.let { Type.entries.getOrNull(it) } ?: Type.valueOf(m.groupValues[1])
            var tag = ""
            var window: Long? = null
            var repeat = 0L
            var flags = 0
            // The detail lines run up to the next alarm or the end of the indented block.
            val indent = indentOf(lines[i])
            i++
            while (i < lines.size && indentOf(lines[i]) > indent && alarmHeader.find(lines[i]) == null) {
                val line = lines[i]
                val t = line.trim()
                if (t.startsWith("tag=")) tag = cleanTag(t.removePrefix("tag="))
                windowField.find(line)?.let { window = dumpsysMillis(it.groupValues[1]) }
                repeatField.find(line)?.let { repeat = it.groupValues[1].toLongOrNull() ?: 0 }
                flagsField.find(line)?.let { flags = it.groupValues[1].toIntOrNull(16) ?: 0 }
                i++
            }
            val whenMillis = wallClock(type, m.groupValues[3].toLongOrNull(), m.groupValues[4].toLongOrNull(), nowRtc, nowElapsed)
            out += Alarm(m.groupValues[5], type, whenMillis, repeat, window, flags, tag)
        }
        return out
    }

    /**
     * When an alarm is due on the wall clock. Android 12+ dumps the delivery time on the elapsed clock
     * (after its own deferrals); older versions only the requested time, on the alarm's own clock.
     */
    internal fun wallClock(type: Type, requested: Long?, whenElapsed: Long?, nowRtc: Long?, nowElapsed: Long?): Long? {
        val rtc = type == Type.RTC || type == Type.RTC_WAKEUP
        return when {
            whenElapsed != null && nowRtc != null && nowElapsed != null -> ahead(whenElapsed - nowElapsed, nowRtc)
            requested == null -> null
            rtc -> requested.takeIf { nowRtc == null || it - nowRtc <= MAX_AHEAD_MS }
            nowRtc != null && nowElapsed != null -> ahead(requested - nowElapsed, nowRtc)
            else -> null
        }
    }

    private fun ahead(delta: Long, nowRtc: Long) = if (delta > MAX_AHEAD_MS) null else nowRtc + delta

    /** "*walarm*:com.app.SYNC" → "com.app.SYNC". */
    fun cleanTag(tag: String): String = tag.trim().removePrefix("*walarm*:").removePrefix("*alarm*:")

    private fun stats(lines: List<String>, title: String): Map<String, PackageStats> {
        val start = lines.indexOfFirst { it.trim() == title }
        if (start < 0) return emptyMap()
        val sums = LinkedHashMap<String, PackageStats>()
        var pkg: String? = null
        var running = 0L
        var wakeups = 0L
        val tags = ArrayList<TagStats>()
        fun flush() {
            val p = pkg ?: return
            val prev = sums[p]
            sums[p] = PackageStats(
                p, running + (prev?.runningMs ?: 0), wakeups + (prev?.wakeups ?: 0),
                tags.sumOf { it.count } + (prev?.count ?: 0), (prev?.tags ?: emptyList()) + tags,
            )
            tags.clear()
        }
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            // The block ends at the first blank line.
            if (line.isBlank()) break
            val p = statsPackage.find(line)
            val t = statsTag.find(line)
            when {
                p != null -> {
                    flush()
                    pkg = p.groupValues[2]
                    running = dumpsysMillis(p.groupValues[3]) ?: 0
                    wakeups = p.groupValues[4].toLongOrNull() ?: 0
                }
                t != null && pkg != null && i + 1 < lines.size -> {
                    tags += TagStats(
                        cleanTag(lines[i + 1]), dumpsysMillis(t.groupValues[1]) ?: 0,
                        t.groupValues[2].toLongOrNull() ?: 0, t.groupValues[3].toLongOrNull() ?: 0,
                    )
                    i++
                }
            }
            i++
        }
        flush()
        return sums
    }

    /** Only the ten busiest tags, but some builds leave out "Alarm Stats": better than nothing. */
    private fun topAlarms(lines: List<String>): Map<String, PackageStats> {
        val start = lines.indexOfFirst { it.trim() == "Top Alarms:" }
        if (start < 0) return emptyMap()
        val out = LinkedHashMap<String, PackageStats>()
        var i = start + 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val m = topLine.find(lines[i])
            if (m != null && i + 1 < lines.size) {
                val pkg = m.groupValues[5]
                val tag = TagStats(
                    cleanTag(lines[i + 1]), dumpsysMillis(m.groupValues[1]) ?: 0,
                    m.groupValues[2].toLongOrNull() ?: 0, m.groupValues[3].toLongOrNull() ?: 0,
                )
                val prev = out[pkg]
                out[pkg] = PackageStats(
                    pkg, tag.runningMs + (prev?.runningMs ?: 0), tag.wakeups + (prev?.wakeups ?: 0),
                    tag.count + (prev?.count ?: 0), (prev?.tags ?: emptyList()) + tag,
                )
                i++
            }
            i++
        }
        return out
    }

    private fun indentOf(line: String) = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }
}

private val durationPart = Regex("""(\d+)(ms|d|h|m|s)""")

/**
 * Android's TimeUtils.formatDuration in dumps: "+1d0h0m0s0ms", "-1m0s519ms", "+44s999ms", "0".
 * Null for anything else ("--", "none").
 */
fun dumpsysMillis(text: String): Long? {
    val t = text.trim().trimEnd(',')
    if (t == "0") return 0
    val sign = if (t.startsWith("-")) -1 else 1
    val body = t.removePrefix("+").removePrefix("-")
    if (body.isEmpty()) return null
    var total = 0L
    var pos = 0
    for (m in durationPart.findAll(body)) {
        if (m.range.first != pos) return null
        pos = m.range.last + 1
        val n = m.groupValues[1].toLongOrNull() ?: return null
        total += n * when (m.groupValues[2]) {
            "d" -> 86_400_000L
            "h" -> 3_600_000L
            "m" -> 60_000L
            "s" -> 1000L
            else -> 1L
        }
    }
    return if (pos == body.length) sign * total else null
}
