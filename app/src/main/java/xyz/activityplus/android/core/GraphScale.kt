package xyz.activityplus.android.core

/**
 * Turns a few hours of history samples into pixel positions for the history widget's graph.
 * Pure, so the gaps, the colour changes and the scale are tested without a Bitmap.
 */
object GraphScale {
    /** [tag] changes the line's colour (charging or not); a change starts a new segment. */
    data class Sample(val millis: Long, val value: Double, val tag: Int = 0)

    data class Pt(val x: Float, val y: Float)

    data class Segment(val tag: Int, val points: List<Pt>)

    data class Scaled(
        val segments: List<Segment>,
        /** The value range the y axis covers. */
        val min: Double,
        val max: Double,
        /** Where the zero line is, when the range includes zero and [scale] was asked to show it. */
        val zeroY: Float?,
    )

    /** One sample per minute is stored; a hole of this long is the monitor not running, not a straight line. */
    const val MAX_GAP_MS = 10 * 60_000L

    /**
     * The y range of [values]: their min and max, at least [minSpan] tall (a flat line must not look
     * like a cliff), with zero inside when [includeZero], and moved inside [bounds] if given.
     */
    fun range(
        values: List<Double>,
        minSpan: Double,
        includeZero: Boolean = false,
        bounds: ClosedFloatingPointRange<Double>? = null,
    ): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to minSpan
        var lo = values.min()
        var hi = values.max()
        if (includeZero) {
            lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0)
        }
        if (hi - lo < minSpan) {
            if (includeZero) {
                // Keep zero where it is and grow away from it.
                if (hi > 0 || lo == 0.0) hi = lo + minSpan else lo = hi - minSpan
            } else {
                val mid = (lo + hi) / 2
                lo = mid - minSpan / 2; hi = mid + minSpan / 2
            }
        }
        if (bounds != null) {
            if (lo < bounds.start) {
                hi += bounds.start - lo; lo = bounds.start
            }
            if (hi > bounds.endInclusive) {
                lo -= hi - bounds.endInclusive; hi = bounds.endInclusive
            }
            lo = maxOf(lo, bounds.start)
        }
        return lo to hi
    }

    /**
     * Positions in a [width] x [height] box with [pad] pixels around the plot: time runs left to
     * right over [fromMillis]..[toMillis], the value bottom to top. Samples outside the window are
     * dropped; a gap of more than [maxGapMs] or a changed tag splits the line. After a tag change the
     * new segment starts at the last point of the old one, so the colours meet without a hole.
     */
    fun scale(
        samples: List<Sample>,
        fromMillis: Long,
        toMillis: Long,
        width: Float,
        height: Float,
        minSpan: Double,
        pad: Float = 0f,
        includeZero: Boolean = false,
        bounds: ClosedFloatingPointRange<Double>? = null,
        maxGapMs: Long = MAX_GAP_MS,
    ): Scaled {
        val inside = samples.filter { it.millis in fromMillis..toMillis && it.value.isFinite() }.sortedBy { it.millis }
        val (lo, hi) = range(inside.map { it.value }, minSpan, includeZero, bounds)
        val span = (toMillis - fromMillis).coerceAtLeast(1)
        val plotW = width - 2 * pad
        val plotH = height - 2 * pad
        fun x(t: Long) = pad + plotW * (t - fromMillis).toFloat() / span
        fun y(v: Double) = pad + plotH * (1f - ((v - lo) / (hi - lo)).toFloat())

        val segments = ArrayList<Segment>()
        var tag = 0
        var points = ArrayList<Pt>()
        var last: Sample? = null
        for (s in inside) {
            val prev = last
            val gap = prev != null && s.millis - prev.millis > maxGapMs
            if (prev != null && (gap || s.tag != tag)) {
                if (points.isNotEmpty()) segments += Segment(tag, points)
                points = ArrayList()
                if (!gap) points += Pt(x(prev.millis), y(prev.value))
            }
            tag = s.tag
            points += Pt(x(s.millis), y(s.value))
            last = s
        }
        if (points.isNotEmpty()) segments += Segment(tag, points)
        val zeroY = if (includeZero && lo <= 0.0 && hi >= 0.0) y(0.0) else null
        return Scaled(segments, lo, hi, zeroY)
    }
}
