package xyz.activityplus.android.core

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving the history to another phone: "Export data" writes a ZIP, "Import data" merges one into the
 * history that is already there. Everything here is plain Java (zip, text, JSON) and has no database in
 * it, so the whole round trip runs in unit tests; the phone supplies a [Source] to read from and a [Sink]
 * to write to.
 *
 * Rules of the merge: rows are only ever added. A row whose key (see [Table.key]) is already there is
 * skipped, whether it came from this phone or twice from the file, and nothing is deleted or changed.
 */
object DataTransfer {
    /**
     * 1: Activity+ 0.5, samples.csv and app_energy.csv (and a derived nights.csv) with no "format" in summary.json.
     * 2: one file per history table, "format" and "version" in summary.json, exact times next to the readable ones.
     */
    const val FORMAT = 2

    /** Rows of one table, in [Table.stored] order. */
    fun interface Source {
        fun rows(table: Table, emit: (List<Any?>) -> Unit)
    }

    /** Where imported rows go. Values are in [Table.stored] order, as [Column.Type] says (Long, Double, String, null). */
    interface Sink {
        /** [Table.keyOf] of every row the table already has. */
        fun existingKeys(table: Table): MutableSet<String>

        /** [block] is all or nothing, so a file that breaks halfway leaves the table as it was. */
        fun transaction(table: Table, block: () -> Unit)

        /** Adds rows that are not there yet; must never replace one. */
        fun insert(table: Table, rows: List<List<Any?>>)
    }

    class Info(
        val version: String,
        val versionCode: Int,
        val nowMillis: Long,
        val zone: ZoneId,
        /** Device facts and the like, added to summary.json by the phone. */
        val extra: (JSONObject) -> Unit = {},
    )

    /** File name to rows, in the order written; summary.json comes last and is not counted in itself. */
    fun write(out: OutputStream, source: Source, info: Info): Map<String, Int> {
        val rows = LinkedHashMap<String, Int>()
        var firstSample: Long? = null
        var lastSample: Long? = null
        val epoch = Tables.SAMPLES.stored.indexOfFirst { it.name == "epoch_ms" }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val w = OutputStreamWriter(zip, Charsets.UTF_8)
            for (table in Tables.ALL) {
                zip.putNextEntry(ZipEntry(table.file))
                w.write(Csv.line(table.header))
                var n = 0
                source.rows(table) { values ->
                    if (table === Tables.SAMPLES) {
                        val ts = (values[epoch] as Number).toLong()
                        if (firstSample == null) firstSample = ts
                        lastSample = ts
                    }
                    w.write(Csv.line(table.csvRow(values, info.zone)))
                    n++
                }
                w.flush()
                zip.closeEntry()
                rows[table.file] = n
            }
            zip.putNextEntry(ZipEntry(SUMMARY))
            w.write(summary(info, rows, firstSample, lastSample).toString(2))
            w.flush()
            zip.closeEntry()
            rows[SUMMARY] = 1
        }
        return rows
    }

    const val SUMMARY = "summary.json"

    private fun summary(info: Info, rows: Map<String, Int>, first: Long?, last: Long?): JSONObject = JSONObject().apply {
        put("app", APP)
        put("format", FORMAT)
        put("version", info.version)
        put("version_code", info.versionCode)
        put("exported", Csv.time(info.nowMillis, info.zone))
        info.extra(this)
        if (first != null && last != null) {
            put("samples", JSONObject().apply { put("first", Csv.time(first, info.zone)); put("last", Csv.time(last, info.zone)) })
        }
        put("files", JSONObject().apply { rows.forEach { (name, n) -> put(name, n) } })
        put(
            "notes",
            "Percent values are 0-100. Power is milliwatts, positive into the battery. Times carry their UTC offset. " +
                "Counter snapshots, monitor baselines and other bookkeeping of the phone are not part of the file. " +
                "Activity+ can import this ZIP on another phone (Settings, Data, Import data); the files with the epoch_ms " +
                "columns are what it reads, the readable time columns are ignored.",
        )
    }

    const val APP = "Activity+"

    // ---- Reading a file ----

    enum class Problem {
        /** Not a ZIP, or one without the summary of an Activity+ export. */
        NOT_AN_EXPORT,
        /** Written by a newer Activity+ than this one understands. */
        NEWER_FORMAT,
        /** A "format" that is not a number from 1 on. */
        UNKNOWN_FORMAT,
        /** An export, but with nothing this version can import. */
        NOTHING_TO_IMPORT,
    }

    class Rejected(val problem: Problem, val format: Int? = null)

    class TableCount(val table: Table, val rows: Int)

    /** What the file holds, to show before anything is imported. */
    class Preview(
        val format: Int,
        val version: String?,
        val device: String?,
        val exportedMillis: Long?,
        val firstMillis: Long?,
        val lastMillis: Long?,
        /** Tables that will be imported, with the rows in the file; empty tables are left out. */
        val tables: List<TableCount>,
    ) {
        val totalRows get() = tables.sumOf { it.rows }

        /** Written before 0.6: only part of the history is in it. */
        val partial get() = format < FORMAT
    }

    /** Either a [Preview] or the reason the file cannot be imported. */
    class Inspection private constructor(val preview: Preview?, val rejected: Rejected?) {
        companion object {
            fun ready(p: Preview) = Inspection(p, null)
            fun rejected(problem: Problem, format: Int? = null) = Inspection(null, Rejected(problem, format))
        }
    }

    /**
     * Reads the whole ZIP once for the summary and the rows per file (the summary is the last entry, and a ZIP
     * can only be read forward). Counts the rows in the files themselves instead of trusting the summary.
     */
    fun inspect(zip: InputStream): Inspection {
        var summary: JSONObject? = null
        val counts = HashMap<Table, Int>()
        forEachEntry(zip) { name, stream ->
            if (name == SUMMARY) {
                summary = runCatching { JSONObject(String(readUpTo(stream, MAX_SUMMARY_BYTES), Charsets.UTF_8)) }.getOrNull()
            } else {
                val table = Tables.byFile(name)
                if (table != null && table.importable) {
                    var records = 0
                    Csv.forEachRecord(InputStreamReader(stream, Charsets.UTF_8)) { records++ }
                    counts[table] = (records - 1).coerceAtLeast(0) // the header is no row
                }
            }
        }
        val s = summary
        if (s == null || s.optString("app") != APP) return Inspection.rejected(Problem.NOT_AN_EXPORT)
        // No "format" is what Activity+ 0.5 wrote.
        val format = if (s.has("format")) (s.opt("format") as? Int ?: return Inspection.rejected(Problem.UNKNOWN_FORMAT)) else 1
        if (format < 1) return Inspection.rejected(Problem.UNKNOWN_FORMAT, format)
        if (format > FORMAT) return Inspection.rejected(Problem.NEWER_FORMAT, format)
        val tables = Tables.ALL.filter { it.importable && it.firstFormat <= format }
            .mapNotNull { t -> counts[t]?.takeIf { it > 0 }?.let { TableCount(t, it) } }
        // Days of network data alone (not listed) are no reason to ask the user anything.
        if (tables.none { it.table.listed }) return Inspection.rejected(Problem.NOTHING_TO_IMPORT, format)
        val samples = s.optJSONObject("samples")
        return Inspection.ready(
            Preview(
                format = format,
                version = s.optString("version").ifEmpty { null },
                device = s.optJSONObject("device")?.optString("model")?.ifEmpty { null },
                exportedMillis = millis(s.optString("exported")),
                firstMillis = millis(samples?.optString("first")),
                lastMillis = millis(samples?.optString("last")),
                tables = tables,
            )
        )
    }

    private const val MAX_SUMMARY_BYTES = 1 shl 20

    /** At most [limit] bytes: InputStream.readNBytes needs Android 13. */
    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun millis(iso: String?): Long? =
        iso?.takeIf { it.isNotEmpty() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** Entries one after the other; a name inside a folder counts by its last part. The stream is not closed. */
    private fun forEachEntry(zip: InputStream, block: (name: String, stream: InputStream) -> Unit) {
        val z = ZipInputStream(zip)
        while (true) {
            val entry = z.nextEntry ?: break
            if (!entry.isDirectory) block(entry.name.substringAfterLast('/'), z)
            z.closeEntry()
        }
    }

    // ---- Merging ----

    enum class Verdict { ADDED, DUPLICATE, INVALID }

    /**
     * Decides, row by row, what happens to the records of one file: unreadable ones are refused, rows whose
     * key is in [existing] (the table's own, or an earlier row of the same file) are skipped, and the rest
     * come out as stored values. Created with the file's header record.
     */
    class Merger(val table: Table, header: List<String>, private val existing: MutableSet<String>, private val nowMillis: Long) {
        private val index = header.withIndex().associate { (i, name) -> name.trim() to i }

        /** Columns the file lacks; then none of its rows can be read. Derived columns are not needed. */
        val missing = table.stored.filter { it.name !in index }.map { it.name }

        private val at = table.stored.map { index[it.name] ?: -1 }

        var added = 0; private set
        var duplicates = 0; private set
        var invalid = 0; private set

        fun accept(record: List<String>, onNew: (List<Any?>) -> Unit): Verdict {
            val values = decode(record)
            val verdict = when {
                values == null -> Verdict.INVALID
                !existing.add(table.keyOf(values)) -> Verdict.DUPLICATE
                else -> { onNew(values); Verdict.ADDED }
            }
            when (verdict) {
                Verdict.ADDED -> added++
                Verdict.DUPLICATE -> duplicates++
                Verdict.INVALID -> invalid++
            }
            return verdict
        }

        private fun decode(record: List<String>): List<Any?>? {
            if (missing.isNotEmpty()) return null
            val out = ArrayList<Any?>(at.size)
            for ((c, i) in table.stored.zip(at)) {
                val raw = record.getOrNull(i) ?: return null
                when {
                    raw.isNotEmpty() -> out.add(value(c, raw) ?: return null)
                    c.nullable -> out.add(null)
                    c.type == Column.Type.TEXT -> out.add("")
                    else -> return null
                }
            }
            // A key with a hole in it, say a package name that is missing, is no row.
            for (k in table.key) {
                val v = out[table.stored.indexOfFirst { it.name == k }]
                if (v == null || v == "") return null
            }
            return out
        }

        /** The stored value for a field that is not empty, or null when it cannot be read. */
        private fun value(c: Column, raw: String): Any? =
            when (c.type) {
                Column.Type.TEXT -> raw
                Column.Type.INT -> raw.toLongOrNull()
                Column.Type.REAL -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it / c.scale }
                Column.Type.FLAG -> when (raw) { "1" -> 1L; "0" -> 0L; else -> null }
                Column.Type.DAY -> raw.takeIf { validDay(it) }
                Column.Type.TIME -> raw.toLongOrNull()?.takeIf { it in MIN_TIME_MS..nowMillis + DAY_MS }
            }
    }

    /** 2000-01-01: no phone with this app has older history, and a zero here means an unset clock. */
    private const val MIN_TIME_MS = 946_684_800_000L
    private const val DAY_MS = 86_400_000L

    class TableReport(val table: Table, val added: Int, val duplicates: Int, val invalid: Int, val missing: List<String>)

    class Report(val tables: List<TableReport>) {
        val added get() = tables.sumOf { it.added }
        val duplicates get() = tables.sumOf { it.duplicates }
        val invalid get() = tables.sumOf { it.invalid }
        /** Files that could not be read at all because columns are missing. */
        val unreadable get() = tables.count { it.missing.isNotEmpty() }
    }

    /**
     * Merges the files of [zip] into [sink]. [format] is the one [inspect] found; files an older format
     * did not have are ignored even if they are in the ZIP. One transaction per file.
     */
    fun import(zip: InputStream, format: Int, sink: Sink, nowMillis: Long): Report {
        val reports = ArrayList<TableReport>()
        forEachEntry(zip) { name, stream ->
            val table = Tables.byFile(name)
            if (table != null && table.importable && table.firstFormat <= format && reports.none { it.table === table }) {
                reports += importTable(table, InputStreamReader(stream, Charsets.UTF_8), sink, nowMillis)
            }
        }
        return Report(reports)
    }

    private fun importTable(table: Table, reader: InputStreamReader, sink: Sink, nowMillis: Long): TableReport {
        var merger: Merger? = null
        val batch = ArrayList<List<Any?>>(BATCH)
        sink.transaction(table) {
            val existing = sink.existingKeys(table)
            Csv.forEachRecord(reader) { record ->
                val m = merger
                if (m == null) merger = Merger(table, record, existing, nowMillis)
                else if (m.missing.isEmpty()) {
                    m.accept(record) { batch.add(it) }
                    if (batch.size >= BATCH) { sink.insert(table, batch.toList()); batch.clear() }
                }
            }
            if (batch.isNotEmpty()) sink.insert(table, batch.toList())
        }
        val m = merger ?: return TableReport(table, 0, 0, 0, emptyList())
        return TableReport(table, m.added, m.duplicates, m.invalid, m.missing)
    }

    private const val BATCH = 500
}
