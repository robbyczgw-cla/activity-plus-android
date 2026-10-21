package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Forecast
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.Metric
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/*
 * "Remaining time by your pattern" on the Battery page, under the live card whose time left is the
 * current-rate estimate. The learning is core/Forecast; this reads 14 days of the history for it.
 */

/** The usual drain per hour of the day, from the last 14 days on battery. Blocking: off the main thread. */
fun loadForecastProfile(nowMillis: Long = System.currentTimeMillis()): Forecast.Profile {
    val from = nowMillis - Forecast.WINDOW_DAYS * 86_400_000L
    val samples = app.history.samples(from, nowMillis).map { Forecast.Sample(it.ts, it.level, it.charging) }
    // Sitting full on a charger is not charging in the status, but it is not use either: the plug sessions say.
    val plugged = app.history.sessions(500, charging = true).filter { it.endMillis >= from }.map { it.startMillis..it.endMillis }
    return Forecast.profile(samples, plugged)
}

/** Time of day, with the weekday when it is not today ("Tue 02:10"), in the phone's own format. */
private fun clock(context: Context, millis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val sameDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val flags = DateUtils.FORMAT_SHOW_TIME or if (sameDay) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(context, millis, flags)
}

/** Not shown while charging (the caller decides); the charging card has the time to full. */
@Composable
fun ForecastCard(b: BatteryState) {
    // Learned once per visit; the level and the clock move on every second, the pattern does not.
    val profile = rememberLoaded(Unit) { loadForecastProfile() }
    ForecastContent(b, profile, System.currentTimeMillis())
}

/** The card for a learned [profile] (null while it loads), without loading anything itself. */
@Composable
fun ForecastContent(b: BatteryState, profile: Forecast.Profile?, now: Long) {
    val context = LocalContext.current
    val empty = Forecast.outcome(profile, now, b.levelFraction, 0.0, b.percentPerHour)
    val low = Forecast.outcome(profile, now, b.levelFraction, Forecast.LOW, b.percentPerHour)

    Card {
        CardHeader(stringResource(R.string.fc_title), Metric.Energy, Icons.Outlined.Schedule)
        Spacer(Modifier.height(8.dp))
        // Neither the pattern nor the current rate gives an answer yet: say so instead of leaving a gap.
        if (!empty.patternReady && empty.current == null) {
            Note(stringResource(if (profile == null) R.string.loading else R.string.es_forecast_none))
            return@Card
        }
        // The pattern when it is learned, else the current rate; the other one only if it says something else.
        val main = if (empty.patternReady) empty.pattern else empty.current
        Text(
            when {
                main == null -> stringResource(R.string.fc_beyond)
                empty.patternReady -> stringResource(R.string.fc_until_pattern, clock(context, main, now))
                else -> stringResource(R.string.fc_until_current, clock(context, main, now))
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        if (empty.differs(now)) {
            StatLine(stringResource(R.string.fc_current_rate), clock(context, empty.current!!, now))
        }
        val lowAt = if (low.patternReady) low.pattern else low.current
        if (b.levelFraction > Forecast.LOW && lowAt != null) {
            StatLine(stringResource(R.string.fc_reaches, Format.percent(Forecast.LOW)), clock(context, lowAt, now))
        }
        if (profile != null && profile.ready) {
            val weekend = isWeekend(now)
            Spacer(Modifier.height(8.dp))
            Sparkline(
                profile.day(weekend), Metric.Energy, height = 36.dp,
                label = stringResource(if (weekend) R.string.fc_chart_weekend else R.string.fc_chart_weekday),
                format = { Format.number(it, 1) + " %/h" }, latest = false,
            )
            Note(stringResource(if (weekend) R.string.fc_chart_weekend else R.string.fc_chart_weekday))
        } else if (profile != null) {
            Note(stringResource(R.string.fc_not_enough))
        }
    }
}

private fun isWeekend(millis: Long): Boolean {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).dayOfWeek
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
}
