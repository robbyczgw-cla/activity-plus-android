package xyz.activityplus.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.WeeklyReport
import xyz.activityplus.android.core.WeeklyReport.Change
import xyz.activityplus.android.core.WeeklyReport.Trend
import xyz.activityplus.android.data.WeeklyLoader
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.WeeklyText
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.BrandEnd
import xyz.activityplus.android.ui.theme.BrandStart
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

/** The last full week against the week before: a headline, the top five by battery, screen time and data, and totals. */
@Composable
fun WeeklyReportScreen() {
    val context = LocalContext.current
    val access = rememberUsageAccess()
    val result = rememberLoaded(access) { runCatching { WeeklyLoader.load(context) } }
    val loaded = result?.getOrNull()

    Screen(
        title = stringResource(R.string.weekly_title),
        subtitle = loaded?.let { range(context, it.weeks) },
    ) {
        when {
            result == null -> item { Note(stringResource(R.string.loading)) }
            loaded == null || !loaded.report.enough -> item {
                Card {
                    Text(stringResource(R.string.weekly_not_enough_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Note(stringResource(R.string.weekly_not_enough_body, WeeklyReport.MIN_DAYS))
                }
            }
            else -> {
                val r = loaded.report
                if (!access) item { UsageAccessCard() }
                WeeklyText.headline(context, loaded)?.let { text -> item { WeeklyHeadline(text) } }
                item {
                    TopCard(
                        stringResource(R.string.weekly_top_battery), Metric.Energy, r.energy, loaded,
                        value = { Format.energy(it).toString() },
                    )
                }
                item {
                    TopCard(
                        stringResource(R.string.weekly_top_screen), Metric.Cpu, r.screenTime, loaded,
                        value = { Format.duration(it.toLong()) },
                    )
                }
                item {
                    TopCard(
                        stringResource(R.string.weekly_top_data), Metric.Network, r.data, loaded,
                        value = { Format.bytes(it.toLong()).toString() },
                    )
                }
                item {
                    Card {
                        CardHeader(stringResource(R.string.weekly_totals), MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        StatLine(stringResource(R.string.weekly_total_screen_on), withChange(context, Format.energy(r.screenOnMwh).toString(), r.screenOnChange))
                        r.screenOffPercentPerHour?.let {
                            StatLine(
                                stringResource(R.string.weekly_total_screen_off),
                                withChange(context, stringResource(R.string.weekly_per_hour, Format.number(it, 2) + " %"), r.screenOffChange),
                            )
                        }
                        StatLine(stringResource(R.string.weekly_total_charges), withChange(context, r.chargeSessions.toString(), r.chargeChange))
                        Spacer(Modifier.height(6.dp))
                        Note(stringResource(R.string.weekly_note))
                    }
                }
            }
        }
    }
}

/** The headline on the brand gradient, like the verdict on the overview. */
@Composable
fun WeeklyHeadline(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(BrandStart.copy(alpha = 0.9f), BrandEnd.copy(alpha = 0.9f))))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(18.dp)
    ) {
        Column {
            Text(stringResource(R.string.weekly_title), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.titleLarge)
            if (onClick != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.weekly_open), color = Color.White,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** The overview card, Monday to Wednesday. */
@Composable
fun WeeklyCard(loaded: WeeklyLoader.Loaded, onClick: () -> Unit) {
    val text = WeeklyText.headline(LocalContext.current, loaded) ?: return
    WeeklyHeadline(text, onClick)
}

@Composable
private fun TopCard(
    title: String, color: Color, entries: List<WeeklyReport.Entry>, loaded: WeeklyLoader.Loaded, value: (Double) -> String,
) {
    val context = LocalContext.current
    Card {
        CardHeader(title, color, caption = stringResource(R.string.weekly_vs_before))
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) Note(stringResource(R.string.weekly_nothing))
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.pkg, 30.dp)
                Spacer(Modifier.width(12.dp))
                Text(loaded.label(e.pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(value(e.value), style = MaterialTheme.typography.bodyLarge)
                    WeeklyText.change(context, e.change)?.let {
                        Text(it, color = changeColor(e.change), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// More use is red, less is green; "new" is neutral blue.
@Composable
private fun changeColor(c: Change): Color = when (c.trend) {
    Trend.UP -> Metric.Heat
    Trend.DOWN -> Metric.Energy
    Trend.NEW -> Metric.Cpu
    else -> LocalSurfaces.current.muted
}

private fun withChange(context: android.content.Context, value: String, c: Change): String =
    WeeklyText.change(context, c)?.let { "$value · $it" } ?: value

private fun range(context: android.content.Context, w: WeeklyReport.Weeks): String =
    DateUtils.formatDateRange(
        context, w.lastStart, w.lastEnd - 1, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
