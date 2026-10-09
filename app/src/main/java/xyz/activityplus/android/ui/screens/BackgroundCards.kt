package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.pro.BackgroundDelta
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.ProReport
import xyz.activityplus.android.service.BackgroundSampler
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.BigValue
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The cards of the automatic background measurement (BackgroundSampler). Each loader returns null
 * without the computer grant or with the setting off, so the screens look exactly as before.
 */

private fun storeIfOn(context: Context): BackgroundStore? =
    if (BackgroundSampler.enabled(context) && BackgroundStore.exists(context)) BackgroundStore.get(context) else null

private fun mah(v: Double) = Format.number(v, if (v < 1) 2 else if (v < 10) 1 else 0) + " mAh"

/** Worth a row: rounds to something, or kept the phone awake a minute. */
private fun BackgroundStore.AppDay.visible() = mah >= 0.05 || wakelockMs >= 60_000

data class LastNight(val span: BackgroundStore.Span, /** From the level history, without charging. */ val lostFraction: Double?)

/** From midnight to this morning's reading; only in the morning, and only after a real night. */
fun loadLastNight(context: Context, nowMillis: Long): LastNight? {
    val store = storeIfOn(context) ?: return null
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    if (now.hour >= 12) return null
    if (store.meta(BackgroundStore.META_MORNING_DAY) != now.toLocalDate().toString()) return null
    val morning = store.meta(BackgroundStore.META_MORNING_TS)?.toLongOrNull() ?: return null
    val midnight = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val span = store.span(midnight, morning) ?: return null
    if (span.toMillis - span.fromMillis < 3_600_000) return null
    val rows = app.history.samples(span.fromMillis, span.toMillis)
    val lost = if (rows.size >= 2) BackgroundDelta.levelLost(rows.map { it.level to it.charging }) else null
    return LastNight(span, lost)
}

@Composable
fun LastNightCard(night: LastNight, capacityMah: Double?, onClick: () -> Unit) {
    val context = LocalContext.current
    val span = night.span
    val lost = night.lostFraction ?: capacityMah?.takeIf { it > 0 }?.let { span.totalMah / it }
    val time = { t: Long -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_TIME) }
    Card(onClick = onClick) {
        CardHeader(
            stringResource(R.string.bg_night_title), Metric.Energy, Icons.Outlined.Bedtime,
            time(span.fromMillis) + " – " + time(span.toMillis),
        )
        Spacer(Modifier.height(8.dp))
        BigValue(
            if (lost != null) Format.Scaled(Format.number(lost.coerceAtLeast(0.0) * 100, 0), "%")
            else Format.Scaled(Format.number(span.totalMah, 0), "mAh")
        )
        Note(stringResource(R.string.bg_night_lost))
        Spacer(Modifier.height(6.dp))
        // Apps only: the system row is not something to act on.
        val top = span.apps.filter { it.pkg != ProReport.SYSTEM && it.visible() }.take(3)
        if (top.isEmpty()) Note(stringResource(R.string.bg_night_quiet))
        top.forEach { AppDayRow(it, null) }
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.bg_note_report))
    }
}

/** Today per app, most first; null when the feature is off. */
fun loadBackgroundToday(context: Context): List<BackgroundStore.AppDay>? {
    val store = storeIfOn(context) ?: return null
    return store.day(BackgroundSchedule.dayKey(System.currentTimeMillis(), ZoneId.systemDefault())).filter { it.visible() }
}

@Composable
fun BackgroundTodayCard(apps: List<BackgroundStore.AppDay>, onPro: () -> Unit) {
    Card {
        CardHeader(stringResource(R.string.bg_today_title), Metric.Energy, Icons.Outlined.History, stringResource(R.string.bg_today_caption))
        Spacer(Modifier.height(8.dp))
        if (apps.isEmpty()) Note(stringResource(R.string.bg_today_empty))
        val max = apps.firstOrNull()?.mah?.coerceAtLeast(1e-9) ?: 1.0
        apps.take(8).forEach { AppDayRow(it, it.mah / max) }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.bg_today_note))
        TextButton(onClick = onPro, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.bg_open_pro) + " ›")
        }
    }
}

@Composable
private fun AppDayRow(a: BackgroundStore.AppDay, fraction: Double?) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(appName(context, a.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (a.wakelockMs >= 60_000) Note(context.getString(R.string.pro_awake, Format.duration(a.wakelockMs / 1000)))
            if (fraction != null) {
                Spacer(Modifier.height(3.dp))
                Meter(fraction, Metric.Energy, height = 4.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(mah(a.mah), style = MaterialTheme.typography.bodyLarge)
    }
}

data class AppBackground(
    /** mAh per day, oldest first, today last; null for days without any reading. */
    val week: List<Double?>,
    val todayMah: Double,
    val usualMah: Double?,
    val unusual: Boolean,
)

/** One app's last 7 days, compared with the 7 days before today. */
fun loadAppBackground(context: Context, pkg: String): AppBackground? {
    val store = storeIfOn(context) ?: return null
    val days = BackgroundSchedule.lastDays(LocalDate.now(ZoneId.systemDefault()), 8)
    val series = store.series(pkg, days.first(), days.last())
    val measured = store.measuredDays(days.first(), days.last())
    val values = days.map { d -> series[d] ?: if (d in measured) 0.0 else null }
    val week = values.takeLast(7)
    if (week.all { (it ?: 0.0) < 0.05 }) return null
    val today = values.last() ?: 0.0
    val previous = values.dropLast(1).filterNotNull()
    return AppBackground(week, today, previous.takeIf { it.isNotEmpty() }?.average(), BackgroundDelta.unusual(today, previous))
}

/** Section of the app detail sheet. */
@Composable
fun AppBackgroundSection(pkg: String) {
    val context = LocalContext.current
    val b = rememberLoaded(pkg) { loadAppBackground(context, pkg) } ?: return
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.bg_detail_title), style = MaterialTheme.typography.titleSmall,
            color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f),
        )
        if (b.unusual) Badge(stringResource(R.string.bg_unusual), Metric.Warn)
    }
    StatLine(stringResource(R.string.bg_detail_today), mah(b.todayMah), Metric.Energy)
    b.usualMah?.let { StatLine(stringResource(R.string.bg_detail_usual), mah(it)) }
    Spacer(Modifier.height(4.dp))
    Sparkline(b.week, Metric.Energy, height = 36.dp)
    Note(stringResource(R.string.bg_detail_note))
}
