package xyz.activityplus.android.ui

import android.content.Context
import android.text.format.DateUtils
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.SinceUpdate
import xyz.activityplus.android.core.SinceUpdate.Change
import xyz.activityplus.android.core.SinceUpdate.Direction
import xyz.activityplus.android.core.SinceUpdate.Metric
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The words for "since the update": the date, the version and one sentence end per change
 * ("wakes the phone 4× as often"). The sentence ends take the app name in front, in the card
 * lines as well as in the diagnosis, which keeps the German word order right.
 */
object UpdateText {
    fun date(c: Context, millis: Long): String =
        DateUtils.formatDateTime(c, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    /** "v2.24.20"; a name that already says "v" or has no digits is shown as it is, an empty one as nothing. */
    fun version(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.first().isDigit()) "v$it" else it }

    /** "Updated 12 Oct to v2.24.20". */
    fun updated(c: Context, millis: Long, versionName: String?): String =
        version(versionName)?.let { c.getString(R.string.su_updated, date(c, millis), it) }
            ?: c.getString(R.string.su_updated_nov, date(c, millis))

    /** "Since the update on 12 Oct (v2.24.20):". */
    fun intro(c: Context, millis: Long, versionName: String?): String =
        version(versionName)?.let { c.getString(R.string.su_intro, date(c, millis), it) }
            ?: c.getString(R.string.su_intro_nov, date(c, millis))

    /** "Since the update on 12 Oct (v2.24.20): WhatsApp wakes the phone 4× as often." */
    fun sentence(c: Context, app: String, change: Change, millis: Long, versionName: String?): String =
        "${intro(c, millis, versionName)} $app ${effect(c, change)}."

    /** The change as the end of a sentence about the app, e.g. "wakes the phone 4× as often". */
    fun effect(c: Context, change: Change): String {
        val ratio = change.ratio
        val up = change.direction == Direction.UP
        // Daily medians over a week, as people think of data in weeks.
        val perWeek = Format.bytes((abs(change.after - change.before) * 7).roundToLong()).toString()
        return when (change.metric) {
            Metric.BACKGROUND -> when {
                up && ratio != null -> c.getString(R.string.su_bg_up, SinceUpdate.times(ratio))
                up -> c.getString(R.string.su_bg_new, mah(change.after))
                else -> c.getString(R.string.su_bg_down, less(ratio))
            }
            Metric.WAKEUPS -> when {
                up && ratio != null -> c.getString(R.string.su_wake_up, SinceUpdate.times(ratio))
                up -> c.getString(R.string.su_wake_new, Format.number(change.after, 0))
                else -> c.getString(R.string.su_wake_down, less(ratio))
            }
            Metric.WAKELOCK -> when {
                up && ratio != null -> c.getString(R.string.su_lock_up, SinceUpdate.times(ratio))
                up -> c.getString(R.string.su_lock_new, Format.duration((change.after / 1000).roundToLong()))
                else -> c.getString(R.string.su_lock_down, less(ratio))
            }
            Metric.SCREEN_ENERGY -> when {
                up && ratio != null -> c.getString(R.string.su_energy_up, SinceUpdate.times(ratio))
                up -> c.getString(R.string.su_energy_new, Format.energy(change.after).toString() + "/h")
                else -> c.getString(R.string.su_energy_down, less(ratio))
            }
            Metric.MOBILE -> c.getString(if (up) R.string.su_mobile_up else R.string.su_mobile_down, perWeek)
            Metric.WIFI -> c.getString(if (up) R.string.su_wifi_up else R.string.su_wifi_down, perWeek)
            Metric.STORAGE -> c.getString(R.string.su_storage_up, Format.bytes((change.after - change.before).roundToLong()).toString())
        }
    }

    /** "60 %" for a ratio of 0.4. */
    private fun less(ratio: Double?): String = Format.percent(1 - (ratio ?: 0.0))

    private fun mah(v: Double) = Format.number(v, if (v < 10) 1 else 0) + " mAh"
}
