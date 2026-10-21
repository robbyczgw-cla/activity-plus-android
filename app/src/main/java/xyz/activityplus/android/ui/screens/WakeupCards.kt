package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.WakeupSchedule
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.core.Wakeups.AppCounters
import xyz.activityplus.android.core.Wakeups.Counters
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.service.WakeupSampler
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.components.tapRow
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.time.LocalDate
import java.time.ZoneId

/*
 * "Who woke the phone": wakeups, wakelocks and CPU per app from Android's counters (WakeupSampler).
 * Needs BATTERY_STATS; without it the Battery screen shows one hint card that opens the pro page,
 * and the other places stay as they were.
 */

/** The store only when it already exists, so looking never creates the file. */
private fun storeIfThere(context: Context): WakeupStore? =
    if (WakeupStore.exists(context)) WakeupStore.get(context) else null

data class WakeupOverview(
    /** Ranked, worst first. */
    val today: List<AppCounters>,
    /** Null without a measured night. */
    val night: List<AppCounters>?,
    val nightWindow: LongRange?,
    /** Apps far above their usual day today. */
    val unusual: Set<String>,
    /** False until the first two readings made an interval. */
    val measured: Boolean,
)

/** Reads the counters first when the last reading is half an hour old, so the card works without the service. */
fun loadWakeups(context: Context): WakeupOverview? {
    if (!ProShell.hasBatteryStats(context)) return null
    WakeupSampler.recordIfDue(context, WakeupSchedule.Trigger.SCREEN)
    val store = storeIfThere(context) ?: return WakeupOverview(emptyList(), null, null, emptySet(), measured = false)
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val days = BackgroundSchedule.lastDays(LocalDate.now(zone), 8)
    val todayApps = store.day(days.last())
    val window = Wakeups.nightWindow(now, store.meta(WakeupStore.META_MORNING_TS)?.toLongOrNull(), zone)
    val night = window?.let { w ->
        val intervals = store.intervals(w.first, w.last)
        if (intervals.isEmpty()) null else Wakeups.rank(Wakeups.inWindow(intervals, w), night = true)
    }
    val measuredDays = store.measuredDays(zone)
    val previous = days.dropLast(1)
    val all = store.allSeries(previous.first(), previous.last())
    val unusual = todayApps.filter { Wakeups.unusual(it.counters, Wakeups.measuredValues(all[it.pkg], previous, measuredDays)) }
        .map { it.pkg }.toSet()
    return WakeupOverview(Wakeups.rank(todayApps, night = false), night, window, unusual, measured = measuredDays.isNotEmpty())
}

/** Battery screen: the card with the grant, a one-line hint without. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeupCard(onPro: () -> Unit) {
    val context = LocalContext.current
    // Checked again on every return: the grant is given on the pro page or from a computer.
    val resumes = rememberResumes()
    if (!remember(resumes) { ProShell.hasBatteryStats(context) }) {
        WakeupHintCard(onPro)
        return
    }
    val data = rememberLoaded(resumes) { loadWakeups(context) } ?: return
    val overLimit = rememberLoaded(resumes) { loadWakeLimit(context) }.orEmpty()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Card {
        CardHeader(stringResource(R.string.wk_title), Metric.Energy, Icons.Outlined.Alarm, stringResource(R.string.wk_caption))
        Spacer(Modifier.height(8.dp))
        if (!data.measured) {
            Note(stringResource(R.string.wk_empty))
        } else {
            val window = data.nightWindow
            if (data.night != null && window != null) {
                val time = { t: Long -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_TIME) }
                SubTitle(stringResource(R.string.wk_night, time(window.first) + " – " + time(window.last)))
                if (data.night.isEmpty()) Note(stringResource(R.string.wk_quiet))
                data.night.take(3).forEach { WakeupRow(it, it.pkg in data.unusual, it.pkg in overLimit) { selected = it.pkg } }
                Spacer(Modifier.height(8.dp))
            }
            SubTitle(stringResource(R.string.wk_today))
            if (data.today.isEmpty()) Note(stringResource(R.string.wk_quiet))
            data.today.take(5).forEach { WakeupRow(it, it.pkg in data.unusual, it.pkg in overLimit) { selected = it.pkg } }
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.wk_explain))
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.wk_note))
    }
    selected?.let { pkg ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = LocalSurfaces.current.card) {
            WakeupAppSheet(pkg)
        }
    }
}

@Composable
private fun WakeupHintCard(onPro: () -> Unit) {
    Card(onClick = onPro) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Alarm, null, tint = Metric.Energy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wk_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.wk_hint))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun WakeupRow(a: AppCounters, unusual: Boolean, overLimit: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().tapRow(stringResource(R.string.a11y_open_details), onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appName(context, a.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unusual) {
                    Spacer(Modifier.width(6.dp))
                    Badge(stringResource(R.string.bg_unusual), Metric.Warn)
                }
            }
            Note(summary(context, a.counters))
            if (overLimit) {
                Spacer(Modifier.height(3.dp))
                WakeLimitBadge()
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
    }
}

/** "212 wakeups · kept awake 14 min · CPU 3 min", leaving out what is too small to matter. */
private fun summary(context: Context, c: Counters): String = listOfNotNull(
    c.wakeups.takeIf { it > 0 }?.let { wakeups(context, it) },
    c.wakelockMs.takeIf { it >= 30_000 }?.let { context.getString(R.string.pro_awake, Format.duration(it / 1000)) },
    c.cpuMs.takeIf { it >= 60_000 }?.let { context.getString(R.string.wk_cpu, Format.duration(it / 1000)) },
).joinToString(" · ")

private fun wakeups(context: Context, n: Long): String {
    val count = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return context.resources.getQuantityString(R.plurals.wk_wakeups, count, count)
}

/** The sheet behind a row: the app's week and the way to restrict it, which stays the user's call. */
@Composable
private fun WakeupAppSheet(pkg: String) {
    val context = LocalContext.current
    Column(Modifier.sheetContent()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(appName(context, pkg, emptyMap()), style = MaterialTheme.typography.titleLarge)
                Note(pkg)
            }
        }
        Spacer(Modifier.height(4.dp))
        AppWakeupSection(pkg)
        if (!pkg.startsWith("#")) {
            Button(onClick = { Actions.appInfo(context, pkg) }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Text(stringResource(R.string.detail_open_app_info))
            }
            Note(stringResource(R.string.wk_fix), Modifier.padding(top = 6.dp))
        }
    }
}

data class AppWakeups(
    /** Per day, oldest first, today last; null for days without any reading. */
    val week: List<Counters?>,
    val today: Counters,
    val usual: Counters?,
    val unusual: Boolean,
)

/** One app's last 7 days, compared with the 7 days before today. */
fun loadAppWakeups(context: Context, pkg: String): AppWakeups? {
    val store = storeIfThere(context) ?: return null
    val zone = ZoneId.systemDefault()
    val days = BackgroundSchedule.lastDays(LocalDate.now(zone), 8)
    val series = store.series(pkg, days.first(), days.last())
    val measured = store.measuredDays(zone)
    val week = days.takeLast(7).map { d -> series[d] ?: if (d in measured) Counters.ZERO else null }
    if (week.none { it != null && Wakeups.worthShowing(it) }) return null
    val today = series[days.last()] ?: Counters.ZERO
    val previous = Wakeups.measuredValues(series, days.dropLast(1), measured)
    return AppWakeups(week, today, Wakeups.average(previous), Wakeups.unusual(today, previous))
}

/** Section of the app detail sheet; nothing when the app never woke the phone. */
@Composable
fun AppWakeupSection(pkg: String) {
    val context = LocalContext.current
    val w = rememberLoaded(pkg) { loadAppWakeups(context, pkg) } ?: return
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.wk_detail_title), style = MaterialTheme.typography.titleSmall,
            color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f),
        )
        if (w.unusual) Badge(stringResource(R.string.bg_unusual), Metric.Warn)
    }
    WakeLimitNote(pkg)
    val t = w.today
    StatLine(stringResource(R.string.wk_detail_wakeups), t.wakeups.toString(), Metric.Energy)
    StatLine(stringResource(R.string.wk_detail_awake), Format.duration(t.wakelockMs / 1000))
    StatLine(stringResource(R.string.wk_detail_cpu), Format.duration(t.cpuMs / 1000))
    if (t.jobs > 0) StatLine(stringResource(R.string.wk_detail_jobs), t.jobs.toString() + " · " + Format.duration(t.jobMs / 1000))
    if (t.syncs > 0) StatLine(stringResource(R.string.wk_detail_syncs), t.syncs.toString())
    if (t.gpsMs >= 1000) StatLine(stringResource(R.string.wk_detail_gps), Format.duration(t.gpsMs / 1000))
    w.usual?.let { u ->
        StatLine(stringResource(R.string.wk_detail_usual), summary(context, u).ifEmpty { "–" })
    }
    Spacer(Modifier.height(4.dp))
    Sparkline(
        w.week.map { it?.wakeups?.toDouble() }, Metric.Energy, height = 36.dp,
        label = stringResource(R.string.wk_detail_chart), format = { Format.number(it, 0) }, latest = false,
    )
    Note(stringResource(R.string.wk_detail_chart))
    Spacer(Modifier.height(4.dp))
    Note(stringResource(R.string.wk_explain))
}
