package xyz.activityplus.android.pro

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads `dumpsys appops`, the report in which Android notes when an app last used a protected sensor.
 * Only the sensor ops below are kept; the report is 700 kB and more on a full phone, so it is read
 * line by line. Tolerant on purpose: the layout differs between Android versions, so every line
 * that does not fit is ignored and a report in an unknown layout simply gives an empty result.
 *
 * What the report looks like (Android 11 to 16):
 * ```
 *   Uid u0a144:                                      one block per uid
 *       CAMERA: mode=foreground                      the runtime permission, set per uid
 *     Package com.example.app:                       one block per package of the uid
 *       FINE_LOCATION (allow):                       per op: the mode of the package
 *         tag=[                                      per attribution tag (apps may split their code in tags)
 *           Access: [fg-s] 2026-10-18 15:01:55.455 (-6h47m52s762ms) duration=+3s734ms
 *           Reject: [bg-s]2026-10-18 15:01:55.455 (-6h47m52s762ms)
 *           Running start at: +1m42s662ms            the access of the line above is still going on
 *         ]
 * ```
 * Android keeps only the LAST access per op, state and flag, so one report never tells how often an
 * app used a sensor; [SensorLogStore] collects the changes between reports for that.
 */
object AppOpsParser {
    enum class Sensor { LOCATION, CAMERA, MICROPHONE, BODY, NEARBY }

    /** The ops worth a log. [MONITOR_LOCATION] is a running location request, [FINE_LOCATION] a delivered fix. */
    enum class Op(val sensor: Sensor) {
        COARSE_LOCATION(Sensor.LOCATION),
        FINE_LOCATION(Sensor.LOCATION),
        MONITOR_LOCATION(Sensor.LOCATION),
        MONITOR_HIGH_POWER_LOCATION(Sensor.LOCATION),
        CAMERA(Sensor.CAMERA),
        RECORD_AUDIO(Sensor.MICROPHONE),
        BODY_SENSORS(Sensor.BODY),
        ACTIVITY_RECOGNITION(Sensor.BODY),
        BLUETOOTH_SCAN(Sensor.NEARBY),
        NEARBY_WIFI_DEVICES(Sensor.NEARBY),
    }

    /**
     * Where the app was during the access. The word before the "-" in "[bg-s]" is the uid state:
     * top and fg (on screen), fgsvc (a foreground service, so a notification), bg and cch (cached) are
     * background, pers is a system process.
     */
    enum class Where { SCREEN, SERVICE, BACKGROUND, SYSTEM, UNKNOWN }

    fun whereOf(state: String): Where = when (state) {
        "top", "fg" -> Where.SCREEN
        "fgsvc", "fgsvcl" -> Where.SERVICE // Android 10 and 11 had a separate state for location services
        "bg", "cch" -> Where.BACKGROUND
        "pers" -> Where.SYSTEM
        else -> Where.UNKNOWN
    }

    /** The permission mode. FOREGROUND is "only while the app is in use", the choice that stops background use. */
    enum class Mode { ALLOW, IGNORE, FOREGROUND, DEFAULT, ERRORED }

    fun modeOf(word: String): Mode? = Mode.entries.find { it.name.equals(word, ignoreCase = true) }

    /**
     * One access or rejection. [flags] is the part after the "-": "s" (the app itself), "tp"/"up"
     * (the app acted as trusted/untrusted proxy for another app), "tpd"/"upd" (another app, usually Play
     * services, accessed on this app's behalf: this app is the real user).
     */
    data class Event(
        val op: Op,
        val timeMillis: Long,
        val where: Where,
        val flags: String,
        val durationMs: Long?,
        /** Access still going on when the report was made: camera open, location updates running. */
        val running: Boolean,
        val rejected: Boolean,
    ) {
        /** The proxy side of an access made for another app; that app has its own, "d" line. */
        val forOthers get() = flags == "tp" || flags == "up"
    }

    data class OpState(val op: Op, val mode: Mode?, val events: List<Event>)

    data class PackageOps(val pkg: String, val ops: Map<Op, OpState>) {
        val events get() = ops.values.flatMap { it.events }
    }

    data class Report(val packages: Map<String, PackageOps>) {
        fun events(): List<Pair<String, Event>> = packages.flatMap { (pkg, p) -> p.events.map { pkg to it } }
    }

    /** More than this apart and the clock text is not trusted (device zone changed, odd locale): the relative time wins. */
    private const val MAX_CLOCK_DIFF_MS = 5 * 60_000L

    fun parse(text: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Report =
        parse(text.lineSequence(), nowMillis, zone)

    /** [nowMillis] is when the report was made; the relative times in it count back from there. */
    fun parse(lines: Sequence<String>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Report {
        val out = LinkedHashMap<String, PackageBuilder>()
        var uidModes = HashMap<Op, Mode>()
        var pkg: PackageBuilder? = null
        var op: Op? = null
        var mode: Mode? = null
        var last: Event? = null

        fun closeOp() {
            val p = pkg
            val o = op
            if (p != null && o != null) p.add(o, mode, uidModes)
            op = null; mode = null; last = null
        }

        fun closePackage() {
            closeOp()
            pkg?.let { b -> out.getOrPut(b.name) { PackageBuilder(b.name) }.merge(b) }
            pkg = null
        }

        for (raw in lines) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            // Everything before the first uid block, and every other section, sits at indent 2 or less.
            if (indent <= 2) {
                closePackage()
                uidModes = HashMap() // a new uid, or no uid at all
                continue
            }
            if (indent == 4 && line.startsWith("Package ") && line.endsWith(":")) {
                closePackage()
                pkg = PackageBuilder(line.removePrefix("Package ").removeSuffix(":").trim())
                continue
            }
            if (pkg == null) {
                // Between "Uid" and the first package: the uid-wide mode of an op ("CAMERA: mode=foreground").
                val m = UID_MODE.matchEntire(line) ?: continue
                val o = opOf(m.groupValues[1]) ?: continue
                modeOf(m.groupValues[2])?.let { uidModes[o] = it }
                continue
            }
            val header = OP_HEADER.matchEntire(line)
            if (header != null) {
                closeOp()
                op = opOf(header.groupValues[1])
                mode = modeOf(header.groupValues[2].substringBefore(' '))
                continue
            }
            // Older layout: "CAMERA: mode=allow; time=+1h ago" under the package.
            val old = UID_MODE.matchEntire(line)
            if (old != null) {
                closeOp()
                op = opOf(old.groupValues[1])
                mode = modeOf(old.groupValues[2])
                continue
            }
            val o = op ?: continue
            val e = EVENT.matchEntire(line)
            if (e != null) {
                val event = event(o, e, nowMillis, zone)
                last = event
                if (event != null) pkg!!.event(o, event)
            } else if (line.startsWith("Running start")) {
                last?.let { prev -> pkg!!.markRunning(o, prev) }
            }
        }
        closePackage()
        return Report(out.mapValues { it.value.build() }.filterValues { it.ops.isNotEmpty() })
    }

    private fun opOf(name: String): Op? = Op.entries.find { it.name == name }

    private fun event(op: Op, m: MatchResult, nowMillis: Long, zone: ZoneId): Event? {
        val (kind, state, flags, rest) = m.destructured
        val absolute = CLOCK.find(rest)?.let { c ->
            runCatching {
                LocalDateTime.parse(c.groupValues[1] + "T" + c.groupValues[2] + "." + c.groupValues[3].padEnd(3, '0'))
                    .atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val relative = RELATIVE.find(rest)?.let { r -> durationMs(r.groupValues[1])?.let { nowMillis - it } }
        // The clock text gives the same time on every report, which the log needs to tell accesses apart.
        val time = when {
            absolute != null && (relative == null || kotlin.math.abs(absolute - relative) <= MAX_CLOCK_DIFF_MS) -> absolute
            else -> relative
        } ?: return null
        val duration = DURATION.find(rest)?.let { durationMs(it.groupValues[1]) }
        return Event(op, time, whereOf(state), flags, duration, running = false, rejected = kind == "Reject")
    }

    /** "6h47m52s762ms", "+1m42s7ms", "-9d6h5m39s845ms" to milliseconds, sign dropped; null when there is no unit. */
    fun durationMs(text: String): Long? {
        var total = 0L
        var found = false
        for (m in UNIT.findAll(text)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            total += n * when (m.groupValues[2]) {
                "d" -> 86_400_000L
                "h" -> 3_600_000L
                "m" -> 60_000L
                "s" -> 1_000L
                else -> 1L
            }
            found = true
        }
        return if (found) total else null
    }

    private class PackageBuilder(val name: String) {
        private val modes = LinkedHashMap<Op, Mode?>()
        private val events = LinkedHashMap<Op, MutableList<Event>>()
        private val uidMode = HashMap<Op, Mode>()

        fun add(op: Op, mode: Mode?, uid: Map<Op, Mode>) {
            modes.putIfAbsent(op, mode)
            uid[op]?.let { uidMode[op] = it }
            events.getOrPut(op) { ArrayList() }
        }

        fun event(op: Op, e: Event) {
            events.getOrPut(op) { ArrayList() } += e
        }

        /** Replaces [e] by the same event marked as running. */
        fun markRunning(op: Op, e: Event) {
            val list = events[op] ?: return
            val i = list.lastIndexOf(e)
            if (i >= 0) list[i] = e.copy(running = true)
        }

        fun merge(other: PackageBuilder) {
            if (other === this) return
            for ((op, m) in other.modes) modes.putIfAbsent(op, m)
            other.uidMode.forEach { (op, m) -> uidMode.putIfAbsent(op, m) }
            for ((op, list) in other.events) events.getOrPut(op) { ArrayList() } += list
        }

        /** The uid-wide mode wins over the package's: it is where Android keeps the runtime permission. */
        fun build() = PackageOps(
            name,
            events.mapValues { (op, list) -> OpState(op, uidMode[op] ?: modes[op], list.toList()) },
        )
    }

    private val UID_MODE = Regex("""([A-Z][A-Z0-9_]*): mode=(\w+).*""")
    private val OP_HEADER = Regex("""([A-Z][A-Z0-9_]*) \(([^)]*)\):""")
    private val EVENT = Regex("""(Access|Reject): \[([a-z]+)-([a-z]*)]\s*(.*)""")
    private val CLOCK = Regex("""(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?""")
    private val RELATIVE = Regex("""\(([+-]?[0-9dhmsµ]+)\)""")
    private val DURATION = Regex("""duration=([+-]?[0-9dhmsµ]+)""")
    private val UNIT = Regex("""(\d+)(ms|d|h|m|s)""")
}
