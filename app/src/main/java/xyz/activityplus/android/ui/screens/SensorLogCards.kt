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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.activityplus.android.R
import xyz.activityplus.android.Tab
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.SensorLog
import xyz.activityplus.android.data.SensorLogStore
import xyz.activityplus.android.pro.AppOpsParser.Sensor
import xyz.activityplus.android.pro.AppOpsParser.Where
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.service.SensorLogSampler
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.tapRow
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import xyz.activityplus.android.ui.theme.forText
import java.time.ZoneId

/*
 * The sensor log: which app used location, camera or microphone, and whether in the foreground or
 * in the background (SensorLogSampler reads Android's app-ops report; core/SensorLog counts).
 * Needs the computer grant. Without it the entry cards are a hint that opens the pro page; nothing
 * here ever changes a permission, a tap only opens the system's own page for the app.
 */

private const val DAY_MS = 86_400_000L

private fun storeIfThere(context: Context): SensorLogStore? =
    if (SensorLogStore.exists(context)) SensorLogStore.get(context) else null

/** An app the user knows: one with a launcher icon (the only ones the app can see). System services are noise here. */
private fun isUserApp(context: Context, pkg: String) =
    runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

/** The last 24 hours per app, from the store only; empty without the grant or before the first reading. */
private fun lastDay(context: Context, now: Long): List<SensorLog.AppUse> {
    if (!ProShell.hasComputerGrant(context)) return emptyList()
    val store = storeIfThere(context) ?: return emptyList()
    return SensorLog.summarize(store.accesses(now - DAY_MS, now), now - DAY_MS, now)
}

/** For the Diagnosis: apps you know that kept using location in the background, with the name to show. Blocking: off the main thread. */
fun loadBackgroundLocation(context: Context): List<Pair<SensorLog.BackgroundUse, String>> =
    SensorLog.manyInBackground(lastDay(context, System.currentTimeMillis()).filter { isUserApp(context, it.pkg) })
        .map { it to appName(context, it.pkg, emptyMap()) }

data class SensorLogData(
    val live: List<SensorLog.Live>,
    val day: List<SensorLog.AppUse>,
    val night: List<SensorLog.BackgroundUse>,
    val nightWindow: LongRange,
    /** When the first reading was stored; null before. */
    val since: Long?,
    /** Packages without a launcher icon (Play services, system components); hidden unless asked for. */
    val system: Set<String>,
)

/** Reads the report first, so "right now" is now, then the history. Blocking: off the main thread. */
suspend fun loadSensorLog(context: Context): SensorLogData {
    val report = runCatching { SensorLogSampler.read(context) }.getOrNull()
    val now = System.currentTimeMillis()
    val store = storeIfThere(context)
    val day = lastDay(context, now)
    val window = SensorLog.nightWindow(now, ZoneId.systemDefault())
    val night = store?.let {
        SensorLog.backgroundUses(SensorLog.summarize(it.accesses(window.first, window.last), window.first, window.last), Sensor.LOCATION)
    }.orEmpty()
    val live = report?.let { SensorLog.rightNow(SensorLog.accesses(it), now) }.orEmpty()
    val pkgs = day.map { it.pkg } + live.map { it.pkg } + night.map { it.pkg }
    return SensorLogData(
        live, day, night, window,
        since = store?.meta(SensorLogStore.META_SINCE)?.toLongOrNull(),
        system = pkgs.filterNot { isUserApp(context, it) }.toSet(),
    )
}

private fun sensorIcon(s: Sensor): ImageVector = when (s) {
    Sensor.LOCATION -> Icons.Outlined.LocationOn
    Sensor.CAMERA -> Icons.Outlined.CameraAlt
    Sensor.MICROPHONE -> Icons.Outlined.Mic
    Sensor.BODY -> Icons.Outlined.MonitorHeart
    Sensor.NEARBY -> Icons.Outlined.Bluetooth
}

private fun sensorName(context: Context, s: Sensor): String = context.getString(
    when (s) {
        Sensor.LOCATION -> R.string.sl_location
        Sensor.CAMERA -> R.string.sl_camera
        Sensor.MICROPHONE -> R.string.sl_microphone
        Sensor.BODY -> R.string.sl_body
        Sensor.NEARBY -> R.string.sl_nearby
    }
)

private fun clock(context: Context, t: Long) = DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_TIME)

/** "Location: 14 times · 9 in the background · last 21:48", leaving out what is zero. */
private fun summary(context: Context, s: Sensor, u: SensorLog.SensorUse): String {
    val parts = ArrayList<String>()
    if (u.uses > 0) parts += context.resources.getQuantityString(R.plurals.sl_times, u.uses, u.uses)
    if (u.background > 0) parts += context.getString(R.string.sl_part_background, u.background)
    if (u.service > 0) parts += context.getString(R.string.sl_part_service, u.service)
    if (u.blocked > 0) parts += context.getString(R.string.sl_part_blocked, u.blocked)
    if (u.activeMs >= 60_000) parts += context.getString(R.string.sl_part_active, Format.duration(u.activeMs / 1000))
    parts += context.getString(R.string.sl_part_last, clock(context, u.lastMillis))
    return sensorName(context, s) + ": " + parts.joinToString(" · ")
}

@Composable
private fun WhereBadge(where: Where) {
    when (where) {
        Where.BACKGROUND -> Badge(stringResource(R.string.sl_badge_background), Metric.Warn)
        Where.SERVICE -> Badge(stringResource(R.string.sl_badge_service), Metric.Cpu)
        else -> Unit
    }
}

@Composable
fun SensorLogScreen(onPro: () -> Unit) {
    val context = LocalContext.current
    // Checked again on every return: the grant is given from a computer, outside the app.
    val resumes = rememberResumes()
    val grant = remember(resumes) { ProShell.hasComputerGrant(context) }
    var data by remember { mutableStateOf<SensorLogData?>(null) }
    var again by remember { mutableIntStateOf(0) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // While the page is open, "right now" stays right; the report is cheap and this runs only on screen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000); again++
        }
    }
    LaunchedEffect(resumes, again, grant) {
        // The old data stays on screen while the new is read.
        if (grant) withContext(Dispatchers.IO) { runCatching { loadSensorLog(context) }.getOrNull() }?.let { data = it }
    }

    Screen(
        title = stringResource(R.string.sl_title),
        subtitle = stringResource(R.string.sl_subtitle),
        action = {
            if (grant) IconButton(onClick = { again++ }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.sl_refresh)) }
        },
    ) {
        if (!grant) {
            item { SensorLogHint(onPro) }
            return@Screen
        }
        val d = data
        if (d == null) {
            item { Note(stringResource(R.string.loading)) }
            return@Screen
        }
        val shown = { pkg: String -> showSystem || pkg !in d.system }
        item { NowCard(d.live.filter { shown(it.pkg) }) }
        item { NightCard(d, d.night.filter { shown(it.pkg) }) }
        item { DayCard(d.day.filter { shown(it.pkg) }, d) }
        if (d.system.isNotEmpty()) item {
            TextButton(onClick = { showSystem = !showSystem }) {
                Text(stringResource(if (showSystem) R.string.sl_system_hide else R.string.sl_system_show, d.system.size))
            }
        }
        item { Note(stringResource(R.string.sl_note), Modifier.padding(horizontal = 4.dp)) }
        d.since?.let { since ->
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            item { Note(stringResource(R.string.sl_since, DateUtils.formatDateTime(context, since, flags)), Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

@Composable
private fun SensorLogHint(onPro: () -> Unit) {
    Card(onClick = onPro) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = Metric.Energy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sl_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.sl_hint))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}

@Composable
private fun NowCard(live: List<SensorLog.Live>) {
    val context = LocalContext.current
    Card {
        CardHeader(stringResource(R.string.sl_now_title), Metric.Energy, Icons.Outlined.CameraAlt, stringResource(R.string.sl_now_caption))
        Spacer(Modifier.height(8.dp))
        if (live.isEmpty()) Note(stringResource(R.string.sl_now_none))
        live.forEach { l ->
            Row(Modifier.fillMaxWidth().tapRow(stringResource(R.string.a11y_open_app_info)) { Actions.appInfo(context, l.pkg) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(l.pkg, 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appName(context, l.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(sensorIcon(l.sensor), null, Modifier.size(14.dp), tint = LocalSurfaces.current.muted)
                        Spacer(Modifier.width(4.dp))
                        Note(sensorName(context, l.sensor))
                    }
                }
                WhereBadge(l.where)
            }
        }
    }
}

@Composable
private fun NightCard(d: SensorLogData, night: List<SensorLog.BackgroundUse>) {
    val context = LocalContext.current
    val window = d.nightWindow
    Card {
        CardHeader(
            stringResource(R.string.sl_night_title), Metric.Energy, Icons.Outlined.LocationOn,
            clock(context, window.first) + " – " + clock(context, window.last),
        )
        Spacer(Modifier.height(8.dp))
        val since = d.since
        when {
            // A log that began after the night cannot say the night was quiet.
            since == null || since > window.last -> Note(stringResource(R.string.sl_night_nodata))
            night.isEmpty() -> Note(stringResource(R.string.sl_night_none))
            else -> night.take(5).forEach { u ->
                Row(Modifier.fillMaxWidth().tapRow(stringResource(R.string.a11y_open_app_info)) { Actions.appInfo(context, u.pkg) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(u.pkg, 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(appName(context, u.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(context.resources.getQuantityString(R.plurals.sl_times, u.uses, u.uses), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (since != null && since in (window.first + 1)..window.last) {
            Spacer(Modifier.height(4.dp))
            Note(stringResource(R.string.sl_night_partial, clock(context, since)))
        }
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.sl_night_note))
    }
}

@Composable
private fun DayCard(day: List<SensorLog.AppUse>, d: SensorLogData) {
    val context = LocalContext.current
    Card {
        CardHeader(stringResource(R.string.sl_day_title), Metric.Energy, Icons.Outlined.MonitorHeart, stringResource(R.string.sl_day_caption))
        Spacer(Modifier.height(8.dp))
        if (day.isEmpty()) Note(stringResource(if (d.since == null) R.string.sl_day_empty else R.string.sl_day_quiet))
        day.take(20).forEach { a ->
            Row(Modifier.fillMaxWidth().tapRow(stringResource(R.string.a11y_open_app_info)) { Actions.appInfo(context, a.pkg) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.pkg, 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appName(context, a.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (a.background > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(stringResource(R.string.sl_badge_background), Metric.Warn)
                        }
                    }
                    SensorLog.ORDER.forEach { s -> a.sensors[s]?.let { Note(summary(context, s, it)) } }
                }
                Spacer(Modifier.width(8.dp))
                Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
            }
        }
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.sl_tap))
    }
}

/** Overview and Hardware: one line from the stored log, and the way to the page. [hint] also shows without the grant. */
@Composable
fun SensorLogEntryCard(onOpen: (Tab) -> Unit, hint: Boolean) {
    val context = LocalContext.current
    val resumes = rememberResumes()
    val grant = remember(resumes) { ProShell.hasComputerGrant(context) }
    if (!grant) {
        if (hint) SensorLogHint { onOpen(Tab.PRO) }
        return
    }
    // The apps that used location in the background in the last 24 hours; null until read.
    val apps = rememberLoaded(resumes) {
        if (storeIfThere(context) == null) -1
        else SensorLog.backgroundUses(lastDay(context, System.currentTimeMillis()).filter { isUserApp(context, it.pkg) }, Sensor.LOCATION).size
    }
    Card(onClick = { onOpen(Tab.SENSORS) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = Metric.Energy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sl_title), style = MaterialTheme.typography.titleMedium)
                Note(
                    when {
                        apps == null || apps < 0 -> stringResource(R.string.sl_entry_empty)
                        apps == 0 -> stringResource(R.string.sl_entry_quiet)
                        else -> context.resources.getQuantityString(R.plurals.sl_entry_apps, apps, apps)
                    }
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}

/** App detail: "Location: 14 times in the background in the last 24 hours"; nothing when the app used no sensor. */
@Composable
fun AppSensorSection(pkg: String) {
    val context = LocalContext.current
    val use = rememberLoaded(pkg) { lastDay(context, System.currentTimeMillis()).find { it.pkg == pkg } } ?: return
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.sl_detail_title), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
    SensorLog.ORDER.forEach { s ->
        val u = use.sensors[s] ?: return@forEach
        val name = sensorName(context, s)
        val res = context.resources
        if (u.background > 0) {
            Text(res.getQuantityString(R.plurals.sl_detail_background, u.background, name, u.background), style = MaterialTheme.typography.bodyMedium, color = Metric.Warn.forText())
        } else if (u.uses > 0) {
            Text(res.getQuantityString(R.plurals.sl_detail_uses, u.uses, name, u.uses), style = MaterialTheme.typography.bodyMedium)
        }
        if (u.blocked > 0) {
            Note(res.getQuantityString(R.plurals.sl_detail_blocked, u.blocked, name, u.blocked))
        }
    }
}
