package xyz.activityplus.android.ui

import android.content.Context
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.WeeklyReport.Change
import xyz.activityplus.android.core.WeeklyReport.Headline
import xyz.activityplus.android.core.WeeklyReport.Trend
import xyz.activityplus.android.data.WeeklyLoader

/** Sentences of the weekly report, shared by the screen, the overview card and the notification. */
object WeeklyText {
    /** "Your week: Chrome used the most battery, 1.4 Wh, up 40 %". Null when there is nothing to name. */
    fun headline(context: Context, loaded: WeeklyLoader.Loaded): String? {
        val h = loaded.report.headline ?: return null
        val name = loaded.label(h.entry.pkg)
        val c = h.entry.change
        if (h.kind == Headline.Kind.SCREEN) {
            return context.getString(R.string.weekly_headline_screen, name, Format.duration(h.entry.value.toLong()))
        }
        val energy = Format.energy(h.entry.value).toString()
        return when (c.trend) {
            Trend.UP -> context.getString(R.string.weekly_headline_up, name, energy, percent(c))
            Trend.DOWN -> context.getString(R.string.weekly_headline_down, name, energy, percent(c))
            Trend.NEW -> context.getString(R.string.weekly_headline_new, name, energy)
            else -> context.getString(R.string.weekly_headline, name, energy)
        }
    }

    private fun percent(c: Change) = Format.percent(c.percent / 100.0)

    /** "+40 %", "-12 %", "new", "unchanged"; null when there is no week before to compare with. */
    fun change(context: Context, c: Change): String? = when (c.trend) {
        Trend.UP -> context.getString(R.string.weekly_change_up, percent(c))
        Trend.DOWN -> context.getString(R.string.weekly_change_down, percent(c))
        Trend.NEW -> context.getString(R.string.weekly_change_new)
        Trend.FLAT -> context.getString(R.string.weekly_change_flat)
        Trend.NONE -> null
    }
}
