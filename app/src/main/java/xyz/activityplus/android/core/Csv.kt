package xyz.activityplus.android.core

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
}
