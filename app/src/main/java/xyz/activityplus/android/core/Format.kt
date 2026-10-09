package xyz.activityplus.android.core

import java.util.Locale
import kotlin.math.abs

/** Number formatting shared by the app, the notification and the tile. Pure, so it is unit-tested. */
object Format {
    /** Null means the default locale; tests pass Locale.US for stable output. */
    var locale: Locale? = null

    private fun loc(): Locale = locale ?: Locale.getDefault()

    fun number(value: Double, decimals: Int): String = String.format(loc(), "%.${decimals}f", value)

    /** Three significant digits, like the Mac app: 9.73, 27.7, 494. */
    fun sig3(value: Double): String {
        val a = abs(value)
        return when {
            a >= 100 -> number(value, 0)
            a >= 10 -> number(value, 1)
            else -> number(value, 2)
        }
    }

    data class Scaled(val value: String, val unit: String) {
        override fun toString() = "$value $unit"
    }

    private val byteUnits = listOf("B", "kB", "MB", "GB", "TB")

    fun bytes(bytes: Long): Scaled {
        var v = bytes.toDouble()
        var i = 0
        while (abs(v) >= 1000 && i < byteUnits.lastIndex) {
            v /= 1000; i++
        }
        return Scaled(if (i == 0) number(v, 0) else sig3(v), byteUnits[i])
    }

    /** Network rate. In bits mode the unit follows networking convention (kbit/s, Mbit/s). */
    fun rate(bytesPerSecond: Double, bits: Boolean): Scaled {
        if (bits) {
            var v = bytesPerSecond * 8
            val units = listOf("bit/s", "kbit/s", "Mbit/s", "Gbit/s")
            var i = 0
            while (v >= 1000 && i < units.lastIndex) {
                v /= 1000; i++
            }
            return Scaled(if (i == 0) number(v, 0) else sig3(v), units[i])
        }
        var v = bytesPerSecond
        val units = listOf("B/s", "kB/s", "MB/s", "GB/s")
        var i = 0
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000; i++
        }
        return Scaled(if (i == 0) number(v, 0) else sig3(v), units[i])
    }

    fun percent(fraction: Double): String = number(fraction * 100, 0) + " %"

    fun watts(milliwatts: Double): Scaled =
        if (abs(milliwatts) < 1000) Scaled(number(milliwatts, 0), "mW")
        else Scaled(sig3(milliwatts / 1000), "W")

    fun temperature(celsius: Double, fahrenheit: Boolean): Scaled =
        if (fahrenheit) Scaled(number(celsius * 9 / 5 + 32, 0), "°F") else Scaled(number(celsius, 0), "°C")

    fun ghz(mhz: Double): Scaled = Scaled(number(mhz / 1000, 2), "GHz")

    fun energy(milliwattHours: Double): Scaled =
        if (milliwattHours < 1000) Scaled(number(milliwattHours, 0), "mWh")
        else Scaled(sig3(milliwattHours / 1000), "Wh")

    /** "2 h 05 min", "12 min", "45 s". */
    fun duration(seconds: Long): String {
        val s = abs(seconds)
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> String.format(loc(), "%d h %02d min", h, m)
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }
}
