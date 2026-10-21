package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.SelfCost
import xyz.activityplus.android.core.SelfCost.Energy
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.data.PowerMonitors
import xyz.activityplus.android.data.SelfCostStore
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.service.SelfCostSampler
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import xyz.activityplus.android.widget.WidgetUpdater
import java.time.LocalDate
import java.time.ZoneId

/*
 * "Activity+ itself": what the app costs, from Android's counters for its own uid (SelfCostSampler),
 * and what it does in the background. Every state says something: still loading, the phone does not
 * hand out the counters, nothing read yet, nothing for today yet, and energy that needs the computer grant.
 */

data class SelfCostData(
    /** False when the phone refuses the app its own counters. */
    val readable: Boolean,
    val today: SelfCost.Sum,
    val week: SelfCost.Sum,
    val todayEnergy: Energy,
    val weekEnergy: Energy,
    val env: SelfCost.Env,
)

/** Reads the counters first when the last reading is 10 minutes old, so the card is current without the service. */
suspend fun loadSelfCost(context: Context, settings: Settings): SelfCostData {
    val env = SelfCost.Env(
        live = settings.live,
        batteryStats = ProShell.hasBatteryStats(context),
        computerGrant = ProShell.hasComputerGrant(context),
        backgroundMeasure = settings.backgroundMeasure,
        powerMonitors = PowerMonitors.supported(context),
        weekly = settings.alerts && settings.weekly,
        widgets = runCatching { WidgetUpdater.hasWidgets(context) }.getOrDefault(false),
    )
    // A failing store must show "cannot be shown", not a card that stays on "reading" for good.
    return runCatching { readSelfCost(context, settings, env) }.getOrElse {
        SelfCostData(false, SelfCost.Sum(SelfCost.Counters.ZERO, 0), SelfCost.Sum(SelfCost.Counters.ZERO, 0), Energy.NotYet, Energy.NotYet, env)
    }
}

private fun readSelfCost(context: Context, settings: Settings, env: SelfCost.Env): SelfCostData {
    SelfCostSampler.record(context, minGapMs = 10 * 60_000L)
    val store = if (SelfCostStore.exists(context)) SelfCostStore.get(context) else null
    val readable = store?.lastReading() != null || SelfCostSampler.readable(context)
    val days = BackgroundSchedule.lastDays(LocalDate.now(ZoneId.systemDefault()), 7)
    val series = store?.series(days.first(), days.last()).orEmpty()

    // Energy: Android's own estimate for Activity+ from the automatic background measurement.
    val background = if (env.computerGrant && BackgroundStore.exists(context)) BackgroundStore.get(context) else null
    val mah = background?.series(context.packageName, days.first(), days.last()).orEmpty()
    val measured = background?.measuredDays(days.first(), days.last()).orEmpty()
    val capacity = app.monitor.latest.value?.battery?.capacityMah
    fun energy(period: List<String>) = SelfCost.energy(
        env.computerGrant, settings.backgroundMeasure, period.count { it in measured }, period.sumOf { mah[it] ?: 0.0 }, capacity,
    )
    return SelfCostData(
        readable, SelfCost.sum(series, days.takeLast(1)), SelfCost.sum(series, days),
        energy(days.takeLast(1)), energy(days), env,
    )
}

/** Settings, next to the live status switch. */
@Composable
fun SelfCostCard() {
    val context = LocalContext.current
    val settings = rememberSettings()
    // Again on every return: the grant and the settings are changed on other pages.
    val resumes = rememberResumes()
    val data = rememberLoaded(resumes, settings.live, settings.backgroundMeasure, settings.alerts, settings.weekly) { loadSelfCost(context, settings) }
    Card {
        CardHeader(stringResource(R.string.own_title), Metric.Energy, Icons.Outlined.Speed, stringResource(R.string.own_caption))
        Spacer(Modifier.height(8.dp))
        if (data == null) {
            Note(stringResource(R.string.own_loading))
            return@Card
        }
        if (!data.readable) {
            Note(stringResource(R.string.own_unreadable))
        } else if (data.week.measuredDays == 0) {
            Note(stringResource(R.string.own_empty))
        } else {
            Counts(data)
            Spacer(Modifier.height(8.dp))
            Note(stringResource(R.string.own_note))
        }
        Spacer(Modifier.height(12.dp))
        Running(data, settings.intervalSeconds)
    }
}

@Composable
private fun Counts(d: SelfCostData) {
    val muted = LocalSurfaces.current.muted
    val today = d.today.takeIf { it.measuredDays > 0 }?.counters
    val week = d.week.counters
    Row3("", stringResource(R.string.own_col_today), stringResource(R.string.own_col_week), header = true)
    Row3(stringResource(R.string.own_cpu), today?.let { SelfCost.durationText(it.cpuMs) }, SelfCost.durationText(week.cpuMs))
    Row3(stringResource(R.string.own_wakeups), today?.wakeups?.toString(), week.wakeups.toString())
    Row3(
        stringResource(R.string.own_wakelock),
        today?.let { stringResource(R.string.own_wakelock_value, SelfCost.durationText(it.wakelockMs), it.wakelocks) },
        stringResource(R.string.own_wakelock_value, SelfCost.durationText(week.wakelockMs), week.wakelocks),
    )
    Row3(stringResource(R.string.own_jobs), today?.jobs?.toString(), week.jobs.toString())
    Row3(stringResource(R.string.own_data), today?.let { Format.bytes(it.dataBytes).toString() }, Format.bytes(week.dataBytes).toString())
    Row3(stringResource(R.string.own_service), today?.let { SelfCost.durationText(it.serviceMs) }, SelfCost.durationText(week.serviceMs))
    Row3(stringResource(R.string.own_energy), energyText(d.todayEnergy), energyText(d.weekEnergy))
    if (today == null) {
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.own_empty_today))
    }
    // One sentence for what energy is, or why it is missing; the week's state first, as it has the most days.
    val energy = d.weekEnergy.takeIf { it !is Energy.Known } ?: d.todayEnergy
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            when (energy) {
                is Energy.Known -> R.string.own_energy_note
                Energy.NeedsGrant -> R.string.own_energy_needs_grant
                Energy.SettingOff -> R.string.own_energy_setting_off
                Energy.NotYet -> R.string.own_energy_not_yet
            }
        ),
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
}

@Composable
private fun energyText(e: Energy): String = when (e) {
    is Energy.Known -> e.percentOfCharge?.let { stringResource(R.string.own_energy_value, SelfCost.mahText(e.mah), SelfCost.percentText(it)) }
        ?: SelfCost.mahText(e.mah)
    else -> stringResource(R.string.own_none)
}

/** Label, today, 7 days. A null value is a day without a reading: a dash, never a gap. */
@Composable
private fun Row3(label: String, today: String?, week: String, header: Boolean = false) {
    val muted = LocalSurfaces.current.muted
    val style = MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = muted, style = style, modifier = Modifier.weight(1.3f))
        Text(
            today ?: stringResource(R.string.own_none), style = style, textAlign = TextAlign.End,
            color = if (header) muted else androidx.compose.ui.graphics.Color.Unspecified,
            fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        Text(
            week, style = style, textAlign = TextAlign.End,
            color = if (header) muted else androidx.compose.ui.graphics.Color.Unspecified,
            fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Running(d: SelfCostData, intervalSeconds: Int) {
    Text(stringResource(R.string.own_running_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (!d.env.live) {
        Note(stringResource(R.string.own_live_off))
        Spacer(Modifier.height(4.dp))
    }
    SelfCost.Job.entries.forEach { job ->
        val need = SelfCost.need(job, d.env)
        // With the live status off only the jobs that do not depend on it are left to describe.
        if (need == SelfCost.Need.LIVE) return@forEach
        val running = need == SelfCost.Need.NONE
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(jobTitle(job)), style = MaterialTheme.typography.bodyMedium,
            color = if (running) androidx.compose.ui.graphics.Color.Unspecified else LocalSurfaces.current.muted,
        )
        Note(
            if (running) {
                if (job == SelfCost.Job.MONITOR) stringResource(jobWhen(job), intervalSeconds) else stringResource(jobWhen(job))
            } else stringResource(needText(need))
        )
    }
    Spacer(Modifier.height(10.dp))
    Note(stringResource(R.string.own_no_wake))
}

private fun jobTitle(job: SelfCost.Job) = when (job) {
    SelfCost.Job.MONITOR -> R.string.own_job_monitor
    SelfCost.Job.WAKEUPS -> R.string.own_job_wakeups
    SelfCost.Job.SENSORS -> R.string.own_job_sensors
    SelfCost.Job.POWER_RAILS -> R.string.own_job_rails
    SelfCost.Job.BACKGROUND -> R.string.own_job_background
    SelfCost.Job.UPDATES -> R.string.own_job_updates
    SelfCost.Job.STORAGE -> R.string.own_job_storage
    SelfCost.Job.THERMAL -> R.string.own_job_thermal
    SelfCost.Job.NIGHT -> R.string.own_job_night
    SelfCost.Job.SELF_COST -> R.string.own_job_self
    SelfCost.Job.WEEKLY -> R.string.own_job_weekly
    SelfCost.Job.WIDGETS -> R.string.own_job_widgets
}

private fun jobWhen(job: SelfCost.Job) = when (job) {
    SelfCost.Job.MONITOR -> R.string.own_job_monitor_when
    SelfCost.Job.WAKEUPS -> R.string.own_job_wakeups_when
    SelfCost.Job.SENSORS -> R.string.own_job_sensors_when
    SelfCost.Job.POWER_RAILS -> R.string.own_job_rails_when
    SelfCost.Job.BACKGROUND -> R.string.own_job_background_when
    SelfCost.Job.UPDATES -> R.string.own_job_updates_when
    SelfCost.Job.STORAGE -> R.string.own_job_storage_when
    SelfCost.Job.THERMAL -> R.string.own_job_thermal_when
    SelfCost.Job.NIGHT -> R.string.own_job_night_when
    SelfCost.Job.SELF_COST -> R.string.own_job_self_when
    SelfCost.Job.WEEKLY -> R.string.own_job_weekly_when
    SelfCost.Job.WIDGETS -> R.string.own_job_widgets_when
}

private fun needText(need: SelfCost.Need) = when (need) {
    SelfCost.Need.BATTERY_STATS -> R.string.own_need_battery_stats
    SelfCost.Need.COMPUTER_GRANT -> R.string.own_need_computer_grant
    SelfCost.Need.BACKGROUND_SETTING -> R.string.own_need_background_setting
    SelfCost.Need.UNSUPPORTED -> R.string.own_need_unsupported
    SelfCost.Need.WEEKLY_SETTING -> R.string.own_need_weekly_setting
    SelfCost.Need.NO_WIDGETS -> R.string.own_need_no_widgets
    // Never shown as a reason: running jobs show their interval, and LIVE is skipped.
    SelfCost.Need.NONE, SelfCost.Need.LIVE -> R.string.own_none
}

/** One line under the "Battery by app" note on the Battery screen; opens the settings, where the card sits. */
@Composable
fun SelfCostLink(onClick: () -> Unit) {
    Text(
        stringResource(R.string.own_link) + " ›",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
