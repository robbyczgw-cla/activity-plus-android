package xyz.activityplus.android.core

import java.io.BufferedReader
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Small pieces of the data export: CSV text as RFC 4180 and Excel read it, with numbers that do
 * not depend on the phone's language (a decimal comma would break the columns). Pure.
 */
object Csv {
    /** Quoted when it holds a comma, a quote or a line break; quotes inside are doubled. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** One row, ending in a line break. Null is an empty field; numbers go through [number]. */
    fun line(fields: List<Any?>): String = fields.joinToString(",", postfix = "\n") { f ->
        when (f) {
            null -> ""
            is Double -> number(f)
            is Float -> number(f.toDouble())
            is Boolean -> if (f) "1" else "0"
            else -> field(f.toString())
        }
    }

    /** At most four decimals with a dot, no trailing zeros, no exponent; empty for null, NaN and infinity. */
    fun number(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return ""
        val s = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** "2026-10-18T09:41:00+02:00": local time with its offset, so the file reads the same anywhere. */
    fun time(millis: Long, zone: ZoneId): String =
        iso.format(Instant.ofEpochMilli(millis).atZone(zone).withNano(0))

    fun zipName(day: LocalDate) = "activityplus-export-$day.zip"

    /**
     * Reads what [line] writes (and what Excel saves): quoted fields with commas, doubled quotes and line
     * breaks inside, records ending in LF, CRLF or CR, an optional byte order mark. One record at a time, so
     * a big file never sits in memory. Lenient on purpose: a quote in the middle of a plain field is text,
     * a quote left open at the end of the file closes there, and empty lines are skipped. Does not close [reader].
     */
    fun forEachRecord(reader: Reader, block: (List<String>) -> Unit) {
        val input = reader as? BufferedReader ?: BufferedReader(reader, 16 * 1024)
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldQuoted = false   // this field began with a quote
        var recordQuoted = false  // some field of this record did, so a lone empty one is a value and not an empty line

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
            fieldQuoted = false
        }
        fun endRecord() {
            endField()
            if (!(fields.size == 1 && fields[0].isEmpty() && !recordQuoted)) block(fields)
            fields = ArrayList()
            recordQuoted = false
        }

        var first = true
        var skipLf = false
        var c = input.read()
        while (c != -1) {
            val ch = c.toChar()
            val afterCr = skipLf
            skipLf = false
            when {
                first && ch == '\uFEFF' -> Unit
                afterCr && ch == '\n' -> Unit // the second half of CRLF
                inQuotes -> if (ch != '"') field.append(ch) else {
                    // A doubled quote is a quote; a single one closes the field.
                    input.mark(1)
                    val next = input.read()
                    if (next == '"'.code) field.append('"') else { inQuotes = false; if (next != -1) input.reset() }
                }
                ch == '"' && field.isEmpty() && !fieldQuoted -> { inQuotes = true; fieldQuoted = true; recordQuoted = true }
                ch == ',' -> endField()
                ch == '\n' -> endRecord()
                ch == '\r' -> { endRecord(); skipLf = true }
                else -> field.append(ch)
            }
            first = false
            c = input.read()
        }
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldQuoted) endRecord()
    }
}
