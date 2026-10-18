package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Thermal
import xyz.activityplus.android.data.ThermalStore
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

/*
 * Heat and throttling: the throttling log (ThermalRecorder) against the minute history. A card on
 * the Hardware page, a section in the app detail and the finding in the diagnosis all read the same
 * report, so the numbers agree everywhere.
 */

private const val HEAT_DAYS = 7

/** How many stretches and apps the card lists; the rest is in the app detail. */
private const val HEAT_EPISODES = 3
private const val HEAT_APPS = 5

class HeatReport(
    /** Newest first. */
    val episodes: List<Thermal.Episode>,
    val summary: Thermal.Summary,
    /** Ranked, without Activity+ itself. */
    val apps: List<Thermal.AppHeat>,
)

// The card, the detail sheet and the diagnosis ask within seconds of each other; 7 days of minutes are 10,000 rows.
@Volatile private var heatCache: Pair<Long, HeatReport>? = null
private const val HEAT_CACHE_MS = 60_000L

/** Reads the log and the history: off the main thread. */
fun loadHeat(context: Context, nowMillis: Long = System.currentTimeMillis()): HeatReport {
    heatCache?.takeIf { nowMillis - it.first < HEAT_CACHE_MS && it.first <= nowMillis }?.let { return it.second }
    val from = nowMillis - HEAT_DAYS * 86_400_000L
    // All the events kept, not only the week: an episode that began before it still has to be cut at its start.
    val events = if (ThermalStore.exists(context)) ThermalStore.get(context).events(0) else emptyList()
    val episodes = Thermal.since(Thermal.episodes(events, nowMillis), from)
    val minutes = app.history.samples(from, nowMillis).map {
        Thermal.Minute(it.ts, it.fgPkg, it.screenOn, it.tempC, it.powerMw, it.charging)
    }
    val apps = Thermal.perApp(minutes, episodes).filter { it.pkg != context.packageName }
    return HeatReport(episodes.reversed(), Thermal.summary(episodes), apps).also { heatCache = nowMillis to it }
}

/** For the diagnosis: the apps that throttle the phone regularly, with their names. Blocking. */
fun loadThrottlers(context: Context, labels: Map<String, String>): List<Pair<Thermal.AppHeat, String>> =
    Thermal.regular(loadHeat(context).apps).map { it to appName(context, it.pkg, labels) }

/** "6.8 W on average, up to 44 °C, throttled after 20 min" */
private fun heatLine(context: Context, a: Thermal.AppHeat, fahrenheit: Boolean): String {
    val after = a.throttledAfterMinutes
    return listOfNotNull(
        a.avgPowerMw?.let { context.getString(R.string.th_app_power, Format.watts(it).toString()) },
        context.getString(R.string.th_app_max, Format.temperature(a.maxTempC, fahrenheit).toString()),
        when {
            after == 0 -> context.getString(R.string.th_at_once)
            after != null -> context.getString(R.string.th_after, Format.duration(after * 60L))
            a.throttledMinutes > 0 -> context.getString(R.string.th_for, Format.duration(a.throttledMinutes * 60L))
            else -> null
        },
    ).joinToString(", ")
}

/** Hardware page: how often Android slowed the phone down, and which apps were on screen when it got hot. */
@Composable
fun HeatCard(onPro: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberSettings()
    val access = rememberUsageAccess()
    val heat = rememberLoaded(rememberResumes(), access) { loadHeat(context) } ?: return
    Card {
        CardHeader(stringResource(R.string.th_title), Metric.Heat, Icons.Outlined.DeviceThermostat, stringResource(R.string.th_caption))
        Spacer(Modifier.height(8.dp))
        val s = heat.summary
        if (s.count == 0) {
            Note(stringResource(R.string.th_none))
        } else {
            StatLine(
                stringResource(R.string.th_total),
                stringResource(R.string.th_total_value, pluralStringResource(R.plurals.th_times, s.count, s.count), Format.duration(s.totalMillis / 1000)),
                Metric.Heat,
            )
            StatLine(stringResource(R.string.th_longest), Format.duration(s.longestMillis / 1000))
            s.peak?.let { StatLine(stringResource(R.string.th_worst), thermalLabel(it)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.th_recent), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
            heat.episodes.take(HEAT_EPISODES).forEach { EpisodeRow(it, settings.fahrenheit) }
            if (!ProShell.hasComputerGrant(context)) {
                Note(stringResource(R.string.th_grant_hint), Modifier.padding(top = 6.dp))
                TextButton(onClick = onPro, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.th_open_pro) + " ›") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.th_apps_title), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
        when {
            !access -> Note(stringResource(R.string.th_usage_hint))
            heat.apps.isEmpty() -> Note(stringResource(R.string.th_apps_empty))
            else -> heat.apps.take(HEAT_APPS).forEach { AppHeatRow(it, settings.fahrenheit) }
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.th_note))
    }
}

@Composable
private fun EpisodeRow(e: Thermal.Episode, fahrenheit: Boolean) {
    val context = LocalContext.current
    val whenText = DateUtils.formatDateTime(
        context, e.startMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
    // What was known at the start: the app on screen and, with the grant, the sensor and the cooling devices.
    val detail = listOfNotNull(
        e.pkg?.let { appName(context, it, emptyMap()) },
        e.sensor?.let { name ->
            e.sensorC?.let { context.getString(R.string.th_ep_sensor, name, Format.temperature(it, fahrenheit).toString()) } ?: name
        },
        e.coolingActive?.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.th_ep_cooling, it, it) },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                whenText + " · " + Format.duration(e.durationMillis / 1000), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false),
            )
            if (e.ongoing) {
                Spacer(Modifier.width(6.dp))
                Badge(stringResource(R.string.th_ongoing), Metric.Heat)
            }
        }
        if (detail.isNotEmpty()) Note(detail)
    }
}

@Composable
private fun AppHeatRow(a: Thermal.AppHeat, fahrenheit: Boolean) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appName(context, a.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                )
                if (a.regularly) {
                    Spacer(Modifier.width(6.dp))
                    Badge(stringResource(R.string.th_regular_badge), Metric.Warn)
                }
            }
            Note(heatLine(context, a, fahrenheit))
        }
    }
}

/** Section of the app detail sheet; nothing for an app with too little time on screen. */
@Composable
fun AppHeatSection(pkg: String) {
    val context = LocalContext.current
    val settings = rememberSettings()
    val a = rememberLoaded(pkg) { loadHeat(context).apps.firstOrNull { it.pkg == pkg } } ?: return
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.th_detail_title), style = MaterialTheme.typography.titleSmall,
            color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f),
        )
        if (a.regularly) Badge(stringResource(R.string.th_regular_badge), Metric.Warn)
    }
    StatLine(stringResource(R.string.th_detail_time), Format.duration(a.minutes * 60L))
    StatLine(
        stringResource(R.string.th_detail_temp),
        stringResource(
            R.string.th_detail_temp_value,
            Format.temperature(a.avgTempC, settings.fahrenheit).toString(), Format.temperature(a.maxTempC, settings.fahrenheit).toString(),
        ),
        Metric.Heat,
    )
    a.avgPowerMw?.let { StatLine(stringResource(R.string.th_detail_power), Format.watts(it).toString(), Metric.Energy) }
    if (a.throttledMinutes > 0) {
        StatLine(
            stringResource(R.string.th_detail_throttled),
            stringResource(R.string.th_total_value, pluralStringResource(R.plurals.th_times, a.episodes, a.episodes), Format.duration(a.throttledMinutes * 60L)),
        )
        Note(heatLine(context, a, settings.fahrenheit))
    }
}
