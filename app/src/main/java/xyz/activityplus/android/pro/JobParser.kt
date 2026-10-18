package xyz.activityplus.android.pro

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads `dumpsys jobscheduler`: the background jobs each app has registered with their conditions,
 * and how often jobs ran lately. Pure text parsing, tested against output of the Android 15 emulator.
 * The job blocks look the same since Android 9; unknown lines are skipped, a missing section gives
 * an empty result.
 */
object JobParser {
    data class Job(
        /** "u0a134" or "1000", as Android prints it. */
        val uid: String,
        /** The job id, with the app's namespace in front where it uses one ("ns:42"). */
        val id: String,
        /** The app the job works for; for jobs run through another app (sync adapters) that is the source. */
        val pkg: String,
        /** The service class that does the work; WorkManager apps all share one. */
        val service: String,
        /** Repeats every so often; null for a one-time job. */
        val periodicMs: Long?,
        val minLatencyMs: Long?,
        val requiresCharging: Boolean,
        val requiresIdle: Boolean,
        val requiresBatteryNotLow: Boolean,
        val requiresNetwork: Boolean,
        /** Wi-Fi or another free network only. */
        val requiresUnmetered: Boolean,
        /** Earliest next run, from the time of the report; null when it may run any time. */
        val earliestInMs: Long?,
        val lastRunMillis: Long?,
        /** App standby bucket ("ACTIVE", "RARE" ...); decides how often Android lets it run. */
        val bucket: String?,
    )

    data class Report(
        val jobs: List<Job>,
        /** How many times each package's jobs started over [runsSpanMs]. */
        val runs: Map<String, Int>,
        val runsSpanMs: Long?,
    )

    // "  JOB #u0a134/30000: 91bf556 com.app/.Service" and, with a namespace,
    // "  JOB usagestats:1000/0: ab88493 @usagestats@android/com.android.server.Service"
    private val header = Regex("""^(\s*)JOB (?:#|([^\s:]+):)([0-9a-z]+)/(-?\d+): \S+ (?:@[^@\s]*@)?([^/\s]+)/(\S+)""")
    private val source = Regex("""^\s*Source: .*\bpkg=(\S+)""")
    private val periodic = Regex("""^\s*PERIODIC: interval=(\S+)""")
    private val latency = Regex("""^\s*Minimum latency: (\S+)""")
    private val requires = Regex("""^\s*Requires: (.*)$""")
    private val earliest = Regex("""^\s*Run time: earliest=([^,\s]+)""")
    private val lastRun = Regex("""^\s*Last successful run: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")
    private val bucketLine = Regex("""^\s*Standby bucket: (\S+)""")
    private val requiredConstraints = Regex("""^\s*Required constraints: (.*)$""")

    // "Current stats at 2026-10-18-21-47-37 (-2m10s756ms) over +2m10s756ms:" (also "Last stats", "Historical stats")
    private val statsHeader = Regex("""^\S.* stats at .* over (\S+):\s*$""")
    private val statsPackage = Regex("""^\s+[0-9a-z]+ / (\S+):\s*$""")
    // "11x pending 4% 7x active": started jobs; "active-top" ran while the app was on screen.
    private val statsActive = Regex("""(\d+)x active(?![-\w])""")
    // "  -1m45s630ms   START: #u0a143/771 @ns@com.app/.Service"  (START-P for periodic jobs)
    private val historyStart = Regex("""^\s*(-\S+)\s+START(?:-P)?: #[0-9a-z]+/-?\d+ (?:@[^@\s]*@)?([^/\s]+)/""")

    private val lastRunFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** [zone]: the phone's time zone, in which the report prints its dates. */
    fun parse(text: String, zone: ZoneId): Report {
        val lines = text.lines()
        val (runs, span) = statsRuns(lines) ?: historyRuns(lines)
        return Report(jobs(lines, zone), runs, span)
    }

    private fun jobs(lines: List<String>, zone: ZoneId): List<Job> {
        val out = LinkedHashMap<String, Job>()
        var i = 0
        while (i < lines.size) {
            val h = header.find(lines[i])
            if (h == null) {
                i++; continue
            }
            val indent = h.groupValues[1].length
            var pkg = h.groupValues[5]
            var periodicMs: Long? = null
            var latencyMs: Long? = null
            var charging = false
            var idle = false
            var batteryNotLow = false
            var network = false
            var unmetered = false
            var earliestMs: Long? = null
            var last: Long? = null
            var bucket: String? = null
            i++
            // The job's lines are indented deeper than its header; the next header or section ends it.
            while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) > indent) && header.find(lines[i]) == null) {
                val line = lines[i]
                source.find(line)?.let { pkg = it.groupValues[1] }
                periodic.find(line)?.let { periodicMs = dumpsysMillis(it.groupValues[1]) }
                latency.find(line)?.let { latencyMs = dumpsysMillis(it.groupValues[1]) }
                requires.find(line)?.let { r ->
                    val f = r.groupValues[1]
                    charging = "charging=true" in f
                    idle = "deviceIdle=true" in f
                    batteryNotLow = "batteryNotLow=true" in f
                }
                if (line.trimStart().startsWith("Network type:")) {
                    network = true
                    if ("NOT_METERED" in line) unmetered = true
                }
                requiredConstraints.find(line)?.let { r ->
                    val c = r.groupValues[1].split(' ')
                    if ("CONNECTIVITY" in c || "UNMETERED" in c) network = true
                    if ("UNMETERED" in c) unmetered = true
                    if ("CHARGING" in c) charging = true
                    if ("IDLE" in c) idle = true
                }
                earliest.find(line)?.let { earliestMs = dumpsysMillis(it.groupValues[1]) }
                lastRun.find(line)?.let { m ->
                    last = runCatching {
                        LocalDateTime.parse(m.groupValues[1], lastRunFormat).atZone(zone).toInstant().toEpochMilli()
                    }.getOrNull()
                }
                bucketLine.find(line)?.let { bucket = it.groupValues[1] }
                i++
            }
            val ns = h.groupValues[2]
            val id = if (ns.isEmpty()) h.groupValues[4] else "$ns:${h.groupValues[4]}"
            val job = Job(
                h.groupValues[3], id, pkg, h.groupValues[6], periodicMs, latencyMs, charging, idle, batteryNotLow,
                network, unmetered, earliestMs, last, bucket,
            )
            out.putIfAbsent("${job.uid}/${job.id}", job)
        }
        return out.values.toList()
    }

    /** Started jobs per package from Android's stats blocks, added up; null without any. */
    private fun statsRuns(lines: List<String>): Pair<Map<String, Int>, Long?>? {
        val runs = LinkedHashMap<String, Int>()
        var span = 0L
        var found = false
        var inBlock = false
        var pkg: String? = null
        for (line in lines) {
            val h = statsHeader.find(line)
            if (h != null) {
                found = true
                inBlock = true
                pkg = null
                span += dumpsysMillis(h.groupValues[1]) ?: 0
                continue
            }
            if (!inBlock) continue
            if (line.isNotBlank() && !line[0].isWhitespace() || line.trim() == "Job history:") {
                inBlock = false; continue
            }
            val p = statsPackage.find(line)
            if (p != null) {
                pkg = p.groupValues[1]; continue
            }
            val a = statsActive.find(line) ?: continue
            val name = pkg ?: continue
            runs[name] = (runs[name] ?: 0) + (a.groupValues[1].toIntOrNull() ?: 0)
        }
        return if (found) runs to span.takeIf { it > 0 } else null
    }

    /** Fallback: the START lines of the job history, which goes back as far as its oldest line. */
    private fun historyRuns(lines: List<String>): Pair<Map<String, Int>, Long?> {
        val runs = LinkedHashMap<String, Int>()
        var oldest = 0L
        for (line in lines) {
            val m = historyStart.find(line) ?: continue
            val ago = dumpsysMillis(m.groupValues[1]) ?: continue
            runs[m.groupValues[2]] = (runs[m.groupValues[2]] ?: 0) + 1
            oldest = maxOf(oldest, -ago)
        }
        return runs to oldest.takeIf { it > 0 }
    }

    private fun indentOf(line: String) = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }
}
