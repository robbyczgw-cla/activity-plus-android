package xyz.activityplus.android.ui.screens

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Schedule
import xyz.activityplus.android.pro.AlarmParser
import xyz.activityplus.android.pro.JobParser
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.pro.ShellService
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.components.tapRow
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.time.ZoneId

/*
 * "Planned for tonight" (core/Schedule): the alarms and jobs apps have set, read from `dumpsys alarm` and
 * `dumpsys jobscheduler` only while the user looks (Battery screen, app details, Diagnosis), never in
 * the background. With the computer grant it reads by itself; with Shizuku only on a tap; without
 * either the Battery screen shows one hint that opens the pro page.
 */

/** The last reading: one serves the card, the app details and the Diagnosis for a while. */
private object PlanCache {
    @Volatile var last: Pair<Long, Schedule.Plan>? = null

    fun get(maxAgeMs: Long): Schedule.Plan? =
        last?.takeIf { System.currentTimeMillis() - it.first in 0..maxAgeMs }?.second
}

/** Reused when a screen opens again shortly after; apps rarely change their plans within minutes. */
private const val FRESH_MS = 5 * 60_000L
/** How long a reading still feeds the Diagnosis and the app details when no new one can be made. */
private const val STALE_MS = 60 * 60_000L

/** Reads both reports now: through the computer grant, or Shizuku when [viaShizuku]. Throws when neither works. */
suspend fun readSchedule(context: Context, viaShizuku: Boolean): Schedule.Plan {
    val read: suspend (String) -> String =
        if (viaShizuku) { c -> ProShell.run(context, c) } else { c -> ProShell.readWithGrant(context, c) }
    val zone = ZoneId.systemDefault()
    val alarms = AlarmParser.parse(read(ShellService.ALARMS))
    // The alarms alone still make the card; the job list is a bonus.
    val jobs = runCatching { JobParser.parse(read(ShellService.JOBS), zone) }.getOrNull()
    val now = System.currentTimeMillis()
    val plan = Schedule.plan(alarms, jobs, now, zone, ignore = setOf("android", context.packageName))
    PlanCache.last = now to plan
    return plan
}

/** A recent reading, else a new one with the computer grant; null without the grant and without a reading. */
suspend fun loadSchedule(context: Context): Schedule.Plan? {
    PlanCache.get(FRESH_MS)?.let { return it }
    if (!ProShell.hasComputerGrant(context)) return PlanCache.get(STALE_MS)
    return runCatching { readSchedule(context, viaShizuku = false) }.getOrNull() ?: PlanCache.get(STALE_MS)
}

/** For the Diagnosis: the costly apps of the last reading. Never reads itself, so it is fine on the main thread. */
fun cachedDozeAlarms(context: Context, labels: Map<String, String>): List<Pair<Schedule.Costly, String>> =
    PlanCache.get(STALE_MS)?.let { Schedule.costly(it) }.orEmpty().map { it to appName(context, it.pkg, labels) }

private class Loaded(val plan: Schedule.Plan?)

/** Battery screen, next to "Who woke the phone". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleCard(onPro: () -> Unit) {
    val context = LocalContext.current
    // Checked again on every return: the grant is given on the pro page or from a computer.
    val resumes = rememberResumes()
    val grant = remember(resumes) { ProShell.hasComputerGrant(context) }
    val shizuku = remember(resumes) { !grant && ProShell.shizukuState(context) == ProShell.Access.SHIZUKU }
    if (!grant && !shizuku) {
        ScheduleHintCard(onPro)
        return
    }
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf<Loaded?>(null) }
    val loaded = rememberLoaded(resumes, grant) { Loaded(if (grant) loadSchedule(context) else PlanCache.get(STALE_MS)) }
    val plan = asked?.plan ?: loaded?.plan
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Card {
        CardHeader(stringResource(R.string.sc_title), Metric.Energy, Icons.Outlined.Schedule, stringResource(R.string.sc_caption))
        Spacer(Modifier.height(8.dp))
        when {
            reading || (grant && loaded == null) -> Note(stringResource(R.string.sc_reading))
            plan == null && grant -> Note(stringResource(R.string.sc_failed))
            plan == null -> {
                Note(stringResource(if (asked != null) R.string.sc_failed else R.string.sc_shizuku))
            }
            else -> PlanBody(plan) { selected = it }
        }
        if (shizuku && !reading) {
            TextButton(
                onClick = {
                    reading = true
                    scope.launch {
                        asked = Loaded(runCatching { readSchedule(context, viaShizuku = true) }.getOrNull())
                        reading = false
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(if (plan == null) R.string.sc_read_now else R.string.sc_read_again) + " ›")
            }
        }
    }
    // A new reading may have dropped the app; then there is no sheet to show.
    val app = selected?.let { pkg -> plan?.apps?.find { it.pkg == pkg } }
    if (plan != null && app != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = LocalSurfaces.current.card) {
            ScheduleAppSheet(app, plan)
        }
    }
}

@Composable
private fun PlanBody(plan: Schedule.Plan, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val night = time(context, plan.night.first) + " – " + time(context, plan.night.last + 1)
    PlanSubTitle(stringResource(R.string.sc_night, night))
    val top = Schedule.noisiest(plan, 5)
    if (top.isEmpty()) Note(stringResource(R.string.sc_quiet))
    top.forEach { PlanRow(it) { onSelect(it.pkg) } }
    Spacer(Modifier.height(6.dp))
    Note(stringResource(R.string.sc_explain))
    Spacer(Modifier.height(4.dp))
    Note(stringResource(R.string.sc_note))
}

@Composable
private fun ScheduleHintCard(onPro: () -> Unit) {
    Card(onClick = onPro) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = Metric.Energy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sc_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.sc_hint))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}

@Composable
private fun PlanSubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun PlanRow(a: Schedule.AppPlan, onClick: () -> Unit) {
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
                ReasonBadge(a)
            }
            Note(summary(context, a))
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
    }
}

@Composable
private fun ReasonBadge(a: Schedule.AppPlan) {
    val text = when {
        Schedule.Reason.EXACT_IN_DOZE in a.reasons -> R.string.sc_badge_doze
        Schedule.Reason.FREQUENT_JOBS in a.reasons -> R.string.sc_badge_jobs
        else -> return
    }
    Spacer(Modifier.width(6.dp))
    Badge(stringResource(text), Metric.Warn)
}

/** "Next wakeup 23:15, then every 15 min · 32 wakeups tonight", or the jobs when the app set no alarm. */
private fun summary(context: Context, a: Schedule.AppPlan): String {
    val parts = ArrayList<String>()
    val onBattery = a.jobs.filter { it.onBattery }
    // An alarm due only after the night is no reason to be on this card; the jobs then are.
    val headline = a.headline?.takeIf { it.tonight > 0 || it.alarmClock || onBattery.isEmpty() }
    headline?.let { parts += alarmText(context, it) }
    if (a.wakeupsTonight > 0) parts += tonight(context, a.wakeupsTonight)
    if (headline == null && onBattery.isNotEmpty()) {
        val shortest = onBattery.mapNotNull { it.job.periodicMs }.minOrNull()
        parts += context.resources.getQuantityString(R.plurals.sc_jobs_count, onBattery.size, onBattery.size) +
            (shortest?.let { ", " + context.getString(R.string.sc_job_every, every(it)) } ?: "")
    }
    return parts.joinToString(" · ")
}

/** "Next wakeup 23:15, then every 15 min, allowed in Doze". */
private fun alarmText(context: Context, p: Schedule.AlarmPlan): String {
    if (p.alarmClock) return context.getString(R.string.sc_alarm_clock, time(context, p.nextMillis))
    return listOfNotNull(
        context.getString(R.string.sc_next, time(context, p.nextMillis)),
        p.intervalMs?.let { context.getString(if (p.intervalMeasured) R.string.sc_then_about else R.string.sc_then_every, every(it)) },
        when {
            p.allowWhileIdle -> context.getString(R.string.sc_in_doze)
            p.exact -> context.getString(R.string.sc_exact)
            else -> null
        },
    ).joinToString(", ")
}

private fun tonight(context: Context, n: Int) = context.resources.getQuantityString(R.plurals.sc_tonight_count, n, n)

/** The time, with the weekday when it is not today. */
private fun time(context: Context, millis: Long): String {
    val day = if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME or day)
}

/** "15 min", "6 h", "1 d": whole hours and days without the "00 min". */
private fun every(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 86_400 && s % 86_400 == 0L -> "${s / 86_400} d"
        s >= 3600 && s % 3600 == 0L -> "${s / 3600} h"
        else -> Format.duration(s)
    }
}

/** The sheet behind a row: the app's plan and the two places to stop it, which stay the user's call. */
@Composable
private fun ScheduleAppSheet(a: Schedule.AppPlan, plan: Schedule.Plan) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a.pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(appName(context, a.pkg, emptyMap()), style = MaterialTheme.typography.titleLarge)
                Note(a.pkg)
            }
        }
        Spacer(Modifier.height(10.dp))
        PlanDetails(a, plan)
        Button(onClick = { Actions.appInfo(context, a.pkg) }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Text(stringResource(R.string.detail_open_app_info))
        }
        ExactAlarmsButton(a)
        Note(stringResource(R.string.sc_fix), Modifier.padding(top = 6.dp))
    }
}

/** Android 12+ lets the user take away an app's exact alarms; only worth offering when it has some. */
@Composable
private fun ExactAlarmsButton(a: Schedule.AppPlan) {
    if (Build.VERSION.SDK_INT < 31 || a.alarms.none { it.exact && !it.alarmClock }) return
    val context = LocalContext.current
    OutlinedButton(onClick = { Actions.exactAlarms(context, a.pkg) }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Text(stringResource(R.string.sc_exact_alarms))
    }
}

/** Section of the app detail sheet; nothing without a reading or when the app planned nothing. */
@Composable
fun AppScheduleSection(pkg: String) {
    val context = LocalContext.current
    val plan = rememberLoaded(pkg) { loadSchedule(context) } ?: return
    val a = plan.apps.find { it.pkg == pkg } ?: return
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.sc_detail_title), style = MaterialTheme.typography.titleSmall,
            color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f),
        )
        ReasonBadge(a)
    }
    PlanDetails(a, plan)
    ExactAlarmsButton(a)
}

private const val MAX_ALARMS = 5
private const val MAX_JOBS = 6

@Composable
private fun PlanDetails(a: Schedule.AppPlan, plan: Schedule.Plan) {
    val context = LocalContext.current
    a.alarms.take(MAX_ALARMS).forEach { p ->
        Column(Modifier.padding(vertical = 3.dp)) {
            Text(alarmText(context, p), style = MaterialTheme.typography.bodyMedium)
            val note = listOfNotNull(
                Schedule.shortName(p.tag).takeIf { it.isNotEmpty() },
                p.tonight.takeIf { it > 0 && !p.alarmClock }?.let { tonight(context, it) },
            ).joinToString(" · ")
            if (note.isNotEmpty()) Note(note)
        }
    }
    if (a.alarms.size > MAX_ALARMS) Note(stringResource(R.string.sc_more, a.alarms.size - MAX_ALARMS))
    val since = plan.sinceBootMs
    if (a.pastWakeups != null && a.pastWakeups > 0 && since != null) {
        StatLine(
            stringResource(R.string.sc_since_start),
            stringResource(R.string.sc_in, a.pastWakeups.toString(), Format.duration(since / 1000)), Metric.Energy,
        )
    }
    if (a.jobs.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        PlanSubTitle(stringResource(R.string.sc_jobs_title))
        // WorkManager apps register many jobs under one service: same name and conditions make one line.
        val groups = a.jobs
            .sortedWith(compareByDescending<Schedule.JobPlan> { it.frequent }.thenByDescending { it.onBattery }.thenBy { it.job.periodicMs ?: Long.MAX_VALUE })
            .groupBy { Schedule.shortName(it.job.service) to conditions(context, it.job) }
        groups.entries.take(MAX_JOBS).forEach { (key, list) ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text((if (list.size > 1) "${list.size}× " else "") + key.first, style = MaterialTheme.typography.bodyMedium)
                val last = list.mapNotNull { it.job.lastRunMillis }.maxOrNull()
                    ?.let { context.getString(R.string.sc_job_last, DateUtils.getRelativeTimeSpanString(it).toString()) }
                Note(listOfNotNull(key.second, last).joinToString(" · "))
            }
        }
        if (groups.size > MAX_JOBS) Note(stringResource(R.string.sc_more, groups.size - MAX_JOBS))
        val runs = a.jobRuns
        val span = plan.runsSpanMs
        if (runs != null && span != null) {
            StatLine(stringResource(R.string.sc_job_runs), stringResource(R.string.sc_in, runs.toString(), Format.duration(span / 1000)))
        }
    }
}

/** "every 1 h · needs internet · also on battery". */
private fun conditions(context: Context, j: JobParser.Job): String = listOfNotNull(
    j.periodicMs?.let { context.getString(R.string.sc_job_every, every(it)) } ?: context.getString(R.string.sc_job_once),
    when {
        j.requiresUnmetered -> context.getString(R.string.sc_job_wifi)
        j.requiresNetwork -> context.getString(R.string.sc_job_network)
        else -> null
    },
    context.getString(if (j.requiresCharging) R.string.sc_job_charging else R.string.sc_job_battery),
    if (j.requiresIdle) context.getString(R.string.sc_job_idle) else null,
).joinToString(" · ")
