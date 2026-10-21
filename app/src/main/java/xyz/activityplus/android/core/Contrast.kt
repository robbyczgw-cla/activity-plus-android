package xyz.activityplus.android.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG 2.x contrast on plain ARGB ints, so the metric palette can be checked in a JVM test and
 * adjusted at run time without Compose types. Pure.
 */
object Contrast {
    /** AA for normal text. */
    const val TEXT = 4.5

    /** AA for large text and for graphics (lines, bars, icons). */
    const val GRAPHIC = 3.0

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance, 0 (black) to 1 (white); alpha is ignored, blend first with [over]. */
    fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** Contrast ratio 1..21 of two opaque colours. */
    fun ratio(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** [fg] drawn with [alpha] over [bg], as the opaque colour the eye sees (a tinted badge background). */
    fun over(fg: Int, bg: Int, alpha: Float): Int {
        fun mix(shift: Int) = ((fg shr shift and 0xFF) * alpha + (bg shr shift and 0xFF) * (1 - alpha)).roundToInt()
        return 0xFF shl 24 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * [fg] unchanged if it reaches [min] on every background, else the nearest shade of the same
     * hue that does: mixed towards black on a light background, towards white on a dark one, in
     * small steps so the colour keeps its character.
     */
    fun ensure(fg: Int, min: Double, vararg backgrounds: Int): Int {
        if (backgrounds.all { ratio(fg, it) >= min }) return fg
        val towards = if (luminance(backgrounds.first()) > 0.5) 0x000000 else 0xFFFFFF
        for (step in 1..STEPS) {
            val t = step.toFloat() / STEPS
            val c = over(towards, fg, t)
            if (backgrounds.all { ratio(c, it) >= min }) return c
        }
        return 0xFF000000.toInt() or towards
    }

    private const val STEPS = 40
}
