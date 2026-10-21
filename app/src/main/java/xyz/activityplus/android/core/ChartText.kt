package xyz.activityplus.android.core

/**
 * What a chart shows, in words for a screen reader: "Power, 2.1 to 4.5 W, now 3.2 W". The wording
 * comes from [Words] (string resources in the app, fakes in tests); this decides which sentence
 * fits the data, so a chart without data still says so instead of staying silent. Pure.
 */
object ChartText {
    /** Min, max and last of the points that exist; null points (gaps) are skipped. */
    data class Summary(val count: Int, val min: Double, val max: Double, val last: Double, val average: Double)

    fun summary(values: List<Double?>): Summary? {
        val v = values.filterNotNull().filter { it.isFinite() }
        if (v.isEmpty()) return null
        return Summary(v.size, v.min(), v.max(), v.last(), v.average())
    }

    interface Words {
        /** Fewer than two points: the chart draws nothing. */
        fun empty(subject: String): String

        /** All points read the same. */
        fun flat(subject: String, value: String): String

        /** [now] is null for charts whose last point is not "now" (a typical day, one bar per day). */
        fun range(subject: String, low: String, high: String, now: String?): String

        /** One bar per item (CPU cores). */
        fun bars(subject: String, low: String, high: String): String
    }

    fun describe(subject: String, values: List<Double?>, format: (Double) -> String, words: Words, latest: Boolean = true): String {
        // The charts draw a line from two points on; one point is a reading, not a course.
        val s = summary(values)?.takeIf { it.count >= 2 } ?: return words.empty(subject)
        val low = format(s.min)
        val high = format(s.max)
        // Judged on the spoken text: two values that read the same are "steady", not "3.2 to 3.2".
        return if (low == high) words.flat(subject, format(s.last)) else words.range(subject, low, high, if (latest) format(s.last) else null)
    }

    /** Bars side by side instead of a course over time, so there is no "now". */
    fun describeBars(subject: String, fractions: List<Double?>, format: (Double) -> String, words: Words): String {
        val s = summary(fractions) ?: return words.empty(subject)
        return words.bars(subject, format(s.min), format(s.max))
    }
}
