package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.NightCheck.Reason
import xyz.activityplus.android.core.NightCheck.Verdict
import xyz.activityplus.android.core.WakeupSchedule
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.data.NightStore
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.pro.ProReport
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.service.BackgroundSampler
import xyz.activityplus.android.service.WakeupSampler
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.components.tapRow
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import xyz.activityplus.android.ui.theme.forText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The night check: one "Last night" card (Overview in the morning, Battery screen always) and a
 * sheet with the last 14 nights. It folds in the background report and the wakeup counters of last
 * night when a grant gives them; without one it still judges sleep, Doze and drain.
 */

data class NightReport(
    /** The morning the night ended on. */
    val date: LocalDate,
    val result: NightCheck.Result,
    /** Newest first. */
    val nights: List<NightCheck.Entry>,
    /** A grant that can name apps is there; without one the card shows the hint. */
    val appsGrant: Boolean,
)

/** Twice the history: the oldest of the 14 shown nights needs 14 nights before it for its usual night. */
private const val SHOWN = NightCheck.USUAL_NIGHTS
private const val DAYS = 2 * SHOWN

/** Blocking (store reads); call off the main thread. */
fun loadNightCheck(context: Context, nowMillis: Long = System.currentTimeMillis(), storedOnly: Boolean = false): NightReport {
    val zone = ZoneId.systemDefault()
    val date = NightCheck.nightDate(nowMillis, zone)
    val dates = (DAYS - 1 downTo 0).map { date.minusDays(it.toLong()) }
    val spans = if (NightStore.exists(context)) {
        NightStore.get(context).spans(NightCheck.window(dates.first(), zone).first - NightCheck.MAX_GAP_MS, nowMillis)
    } else emptyList()
    val capacity = if (storedOnly) app.monitor.latest.value?.battery?.capacityMah else runCatching { app.monitor.batteryNow().capacityMah }.getOrNull()
    val wakeups = ProShell.hasBatteryStats(context)
    // The last half hour of the night is counted at the next reading; take it now if it is due.
    if (wakeups && !storedOnly) WakeupSampler.recordIfDue(context, WakeupSchedule.Trigger.SCREEN)
    val background = BackgroundSampler.enabled(context)
    val entries = NightCheck.history(spans, dates, zone, capacity, SHOWN) { m -> nightSuspects(context, m, wakeups, background) }
    return NightReport(date, entries.last().result, entries.reversed(), wakeups || background)
}

/** For the Overview: only in the morning, and only a night that was measured. */
fun loadMorningNight(context: Context, nowMillis: Long = System.currentTimeMillis()): NightReport? {
    val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    if (now.hour >= NightCheck.NOON_HOUR) return null
    val r = loadNightCheck(context, nowMillis)
    return r.takeIf { it.date == now.toLocalDate() && it.result.verdict != Verdict.NOT_ENOUGH_DATA }
}

/**
 * The report for a screen, reloaded on every return and as soon as a span is saved: the screen-on
 * that ends the night may come while the app is already open. [morningOnly] for the Overview.
 */
@Composable
fun rememberNightCheck(morningOnly: Boolean): NightReport? {
    val context = LocalContext.current
    val resumes = rememberResumes()
    val saved by NightStore.saved.collectAsState()
    return rememberLoaded(resumes, saved, morningOnly) {
        if (morningOnly) loadMorningNight(context) else loadNightCheck(context)
    }
}

/**
 * The apps of one night: Android's counters (BATTERY_STATS) cut to the night, else the background
 * report's intervals apportioned to it (computer grant). With
 * both, the counters lead and the report adds mAh. Null when neither was read that night.
 */
private fun nightSuspects(context: Context, m: NightCheck.Measured, wakeups: Boolean, background: Boolean): List<NightCheck.Suspect>? {
    val window = m.fromMillis..m.toMillis
    val counted = if (wakeups && WakeupStore.exists(context)) {
        val intervals = WakeupStore.get(context).intervals(window.first, window.last)
        if (intervals.isEmpty()) null
        else Wakeups.inWindow(intervals, window).map { NightCheck.Suspect(it.pkg, it.counters.wakelockMs, it.counters.wakeups) }
    } else null
    // The morning reading runs at the screen-on that ends the night, a few seconds after it.
    val reported = if (background && BackgroundStore.exists(context)) {
        BackgroundStore.get(context).span(window.first, window.last)?.apps
            ?.filter { it.pkg != ProReport.SYSTEM }
            ?.map { NightCheck.Suspect(it.pkg, it.wakelockMs, 0, it.mah) }
    } else null
    if (counted == null || reported == null) return counted ?: reported
    val mah = reported.associate { it.pkg to it.mah }
    return counted.map { it.copy(mah = mah[it.pkg]) }
}

private fun perHour(v: Double) = Format.number(v, 1) + " %/h"

private fun clock(context: Context, t: Long) = DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_TIME)

/** Worth a row: woke the phone, kept it awake a minute, or used a little battery. */
private fun NightCheck.Suspect.visible() = wakeups > 0 || wakelockMs >= 60_000 || (mah ?: 0.0) >= 0.05

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightCheckCard(report: NightReport, onPro: () -> Unit) {
    val context = LocalContext.current
    val r = report.result
    val m = r.measured?.takeIf { r.verdict != Verdict.NOT_ENOUGH_DATA }
    var history by rememberSaveable { mutableStateOf(false) }
    Card(onClick = { history = true }) {
        CardHeader(
            stringResource(R.string.bg_night_title), Metric.Energy, Icons.Outlined.Bedtime,
            m?.let { clock(context, it.fromMillis) + " – " + clock(context, it.toMillis) },
        )
        Spacer(Modifier.height(8.dp))
        Text(title(context, r), style = MaterialTheme.typography.titleLarge, color = verdictColor(r.verdict).forText())
        Spacer(Modifier.height(4.dp))
        reasonLines(context, r).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        if (m != null) {
            Spacer(Modifier.height(4.dp))
            NightStats(m, r.usual)
            SuspectRows(r)
            if (!report.appsGrant) ProHint(onPro)
        }
        NextStep(r)
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.nc_tap_history))
        ShareReportTextButton()
    }
    if (history) {
        ModalBottomSheet(onDismissRequest = { history = false }, containerColor = LocalSurfaces.current.card) {
            NightsSheet(report)
        }
    }
}

private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.SLEPT_WELL -> Metric.Energy
    Verdict.NOT_ENOUGH_DATA -> Color.Unspecified
    else -> Metric.Warn
}

private fun appLabel(context: Context, pkg: String) = appName(context, pkg, emptyMap())

private fun title(context: Context, r: NightCheck.Result): String = when (r.verdict) {
    Verdict.SLEPT_WELL -> context.getString(R.string.nc_title_well)
    Verdict.APP_KEPT_AWAKE -> context.getString(R.string.nc_title_app, appLabel(context, r.suspect!!.pkg))
    Verdict.SYSTEM_DID_NOT_SLEEP -> context.getString(R.string.nc_title_system)
    Verdict.HIGH_DRAIN_WHILE_ASLEEP -> context.getString(R.string.nc_title_drain)
    Verdict.NOT_ENOUGH_DATA -> context.getString(R.string.nc_title_none)
}

/** The measured reason first, then what it means; one or two short lines. */
private fun reasonLines(context: Context, r: NightCheck.Result): List<String> {
    val m = r.measured
    val deep = m?.let { Format.percent(it.deepShare) }
    return when (r.reason) {
        Reason.GOOD -> listOf(context.getString(R.string.nc_reason_well, perHour(m!!.drainPerHour), deep))
        Reason.APP_AWAKE -> listOfNotNull(
            context.getString(R.string.nc_reason_app_awake, Format.duration(r.suspect!!.wakelockMs / 1000)),
            context.getString(R.string.nc_excessive).takeIf { r.suspect.wakelockMs >= NightCheck.EXCESSIVE_WAKELOCK_MS },
        )
        Reason.APP_WAKEUPS -> {
            val n = r.suspect!!.wakeups.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            listOf(context.resources.getQuantityString(R.plurals.nc_reason_app_wakeups, n, n))
        }
        Reason.NO_DEEP_DOZE, Reason.LOW_DEEP_SLEEP -> listOf(
            when {
                r.reason == Reason.NO_DEEP_DOZE -> context.getString(R.string.nc_reason_no_deep, deep)
                r.usual != null -> context.getString(R.string.nc_reason_low_deep_usual, deep, Format.percent(r.usual.deepShare))
                else -> context.getString(R.string.nc_reason_low_deep, deep)
            },
            context.getString(
                when {
                    !r.appsChecked -> R.string.nc_system_unknown
                    m!!.weakSignal -> R.string.nc_system_signal
                    else -> R.string.nc_system_explain
                }
            ),
        )
        Reason.HIGH_DRAIN, Reason.DRAIN_ABOVE_USUAL -> listOf(
            if (r.reason == Reason.DRAIN_ABOVE_USUAL && r.usual != null) {
                context.getString(R.string.nc_reason_drain_usual, perHour(m!!.drainPerHour), perHour(r.usual.drainPerHour), deep)
            } else context.getString(R.string.nc_reason_drain, perHour(m!!.drainPerHour), deep),
            context.getString(if (m.weakSignal) R.string.nc_drain_signal else R.string.nc_drain_explain),
        )
        Reason.NO_RECORD -> listOf(context.getString(R.string.nc_reason_no_record))
        Reason.CHARGED -> listOf(context.getString(R.string.nc_reason_charged))
        Reason.SHORT -> listOf(context.getString(R.string.nc_reason_short))
    }
}

@Composable
private fun NightStats(m: NightCheck.Measured, usual: NightCheck.Usual?) {
    val context = LocalContext.current
    val usually = { v: String -> " · " + context.getString(R.string.nc_usually, v) }
    StatLine(stringResource(R.string.nc_stat_asleep), Format.duration(m.asleepMs / 1000))
    StatLine(
        stringResource(R.string.nc_stat_deep),
        Format.percent(m.deepShare) + (usual?.let { usually(Format.percent(it.deepShare)) } ?: ""),
        Metric.Energy,
    )
    StatLine(
        stringResource(R.string.nc_stat_first_deep),
        m.firstDeepAfterMs?.let { Format.duration(it / 1000) }
            ?: stringResource(if (m.lightReached) R.string.nc_light_only else R.string.nc_never),
    )
    StatLine(stringResource(R.string.nc_stat_interruptions), m.interruptions.toString())
    StatLine(
        stringResource(R.string.nc_stat_drain),
        perHour(m.drainPerHour) + (m.mahPerHour?.let { " · " + Format.number(it, 0) + " mAh/h" } ?: "") +
            (usual?.let { usually(perHour(it.drainPerHour)) } ?: ""),
    )
    if (m.weakSignal) StatLine(stringResource(R.string.nc_stat_signal), stringResource(R.string.nc_signal_weak), Metric.Warn)
}

/** Last night's apps from the grant data: what the old "Last night" card and the wakeup line showed. */
@Composable
private fun SuspectRows(r: NightCheck.Result) {
    val list = r.suspects ?: return
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.nc_apps_title), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
    val top = list.filter { it.visible() }.take(3)
    if (top.isEmpty()) Note(stringResource(R.string.bg_night_quiet))
    top.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(s.pkg, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appLabel(context, s.pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Note(suspectSummary(context, s))
            }
        }
    }
}

/** "212 wakeups · kept awake 14 min · 35 mAh", leaving out what is too small to matter. */
private fun suspectSummary(context: Context, s: NightCheck.Suspect): String = listOfNotNull(
    s.wakeups.takeIf { it > 0 }?.let {
        val n = it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        context.resources.getQuantityString(R.plurals.wk_wakeups, n, n)
    },
    s.wakelockMs.takeIf { it >= 30_000 }?.let { context.getString(R.string.pro_awake, Format.duration(it / 1000)) },
    s.mah?.takeIf { it >= 0.05 }?.let { Format.number(it, if (it < 10) 1 else 0) + " mAh" },
).joinToString(" · ")

@Composable
private fun ProHint(onPro: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().tapRow(stringResource(R.string.a11y_open_pro), onPro).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Note(stringResource(R.string.nc_pro_hint), Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
    }
}

/** One concrete next step per verdict, always into Android's own screens: Activity+ never acts itself. */
@Composable
private fun NextStep(r: NightCheck.Result) {
    val context = LocalContext.current
    val weak = r.measured?.weakSignal == true
    when (r.verdict) {
        Verdict.APP_KEPT_AWAKE -> {
            val pkg = r.suspect!!.pkg
            StepButton(stringResource(R.string.detail_open_app_info)) { Actions.appInfo(context, pkg) }
            Note(stringResource(R.string.wk_fix))
        }
        Verdict.SYSTEM_DID_NOT_SLEEP -> {
            StepButton(stringResource(R.string.nc_act_update)) { Actions.systemUpdates(context) }
            Note(stringResource(R.string.nc_act_update_note))
            StepButton(stringResource(R.string.nc_act_optimization)) { Actions.batteryOptimizations(context) }
        }
        Verdict.HIGH_DRAIN_WHILE_ASLEEP -> if (weak) {
            StepButton(stringResource(R.string.nc_act_network)) { Actions.networkSettings(context) }
            Note(stringResource(R.string.nc_act_network_note))
        } else {
            StepButton(stringResource(R.string.nc_act_usage)) { Actions.open(context, Diagnosis.Fix.BATTERY_USAGE) }
            Note(stringResource(R.string.nc_act_usage_note))
        }
        Verdict.SLEPT_WELL, Verdict.NOT_ENOUGH_DATA -> Unit
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text("$label ›") }
}

/** The sheet behind the card: the last 14 nights, newest first, and the usual night. */
@Composable
private fun NightsSheet(report: NightReport) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    Column(Modifier.sheetContent()) {
        Text(stringResource(R.string.nc_history_title), style = MaterialTheme.typography.titleLarge)
        Note(stringResource(R.string.nc_history_caption))
        Spacer(Modifier.height(8.dp))
        if (report.nights.none { it.result.measured != null && it.result.verdict != Verdict.NOT_ENOUGH_DATA }) {
            Note(stringResource(R.string.nc_history_empty))
        }
        report.nights.forEach { e ->
            val r = e.result
            val noon = e.date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        DateUtils.formatDateTime(context, noon, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(shortLabel(context, r), style = MaterialTheme.typography.bodyMedium, color = verdictColor(r.verdict).forText(), maxLines = 1)
                }
                val m = r.measured?.takeIf { r.verdict != Verdict.NOT_ENOUGH_DATA }
                if (m != null) {
                    Text(perHour(m.drainPerHour) + " · " + Format.percent(m.deepShare), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        report.result.usual?.let { u ->
            Spacer(Modifier.height(8.dp))
            Note(
                context.resources.getQuantityString(
                    R.plurals.nc_history_usual, u.nights, u.nights,
                    perHour(u.drainPerHour) + " · " + Format.percent(u.deepShare),
                )
            )
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.nc_explain))
    }
}

private fun shortLabel(context: Context, r: NightCheck.Result): String = when (r.verdict) {
    Verdict.SLEPT_WELL -> context.getString(R.string.nc_title_well)
    Verdict.APP_KEPT_AWAKE -> appLabel(context, r.suspect!!.pkg)
    Verdict.SYSTEM_DID_NOT_SLEEP -> context.getString(R.string.nc_short_system)
    Verdict.HIGH_DRAIN_WHILE_ASLEEP -> context.getString(R.string.nc_short_drain)
    Verdict.NOT_ENOUGH_DATA -> context.getString(
        when (r.reason) {
            Reason.CHARGED -> R.string.nc_short_charged
            Reason.NO_RECORD -> R.string.nc_short_no_record
            else -> R.string.nc_short_short
        }
    )
}
