package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.AppUsage
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

enum class AppSort { BATTERY, SCREEN, DATA, BACKGROUND, STORAGE }

/** One row: everything known about an app for the range, including measured battery. */
data class AppRow(val usage: AppUsage, val mwh: Double, val usualMwh: Double?) {
    /** "3.2×" when today is far above the app's usual day. */
    val unusual: Double? get() = usualMwh?.takeIf { it >= 50 }?.let { mwh / it }?.takeIf { it >= 2.5 && mwh >= 200 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onPro: () -> Unit = {}) {
    val access = rememberUsageAccess()
    var days by rememberSaveable { mutableStateOf(1) }
    var sort by rememberSaveable { mutableStateOf(AppSort.BATTERY) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val data = rememberLoaded(access, days) { loadApps(days, withStorage = true) }

    val rows = data?.let { d ->
        d.apps.map { AppRow(it, d.energy[it.pkg]?.first ?: 0.0, d.usualEnergy[it.pkg]) }
            .filter { it.mwh > 0 || it.usage.screenSeconds > 0 || it.usage.totalBytes > 0 || it.usage.serviceSeconds > 0 || sort == AppSort.STORAGE }
            .sortedWith(
                compareByDescending<AppRow> {
                    when (sort) {
                        AppSort.BATTERY -> it.mwh
                        AppSort.SCREEN -> it.usage.screenSeconds.toDouble()
                        AppSort.DATA -> it.usage.totalBytes.toDouble()
                        AppSort.BACKGROUND -> it.usage.backgroundBytes.toDouble() + it.usage.serviceSeconds * 1e5
                        AppSort.STORAGE -> it.usage.storageBytes.toDouble()
                    }
                }.thenByDescending { it.usage.screenSeconds }.thenByDescending { it.usage.totalBytes }
            )
    }

    Screen(title = stringResource(R.string.tab_apps), subtitle = stringResource(R.string.apps_subtitle)) {
        if (!access) {
            item { UsageAccessCard() }
            return@Screen
        }
        item {
            Column {
                Chips(listOf(1, 7), days, { if (it == 1) stringResource(R.string.range_today) else stringResource(R.string.range_7d) }) { days = it }
                Chips(AppSort.entries, sort, { sortLabel(it) }) { sort = it }
            }
        }
        if (rows == null) {
            item { Note(stringResource(R.string.loading)) }
            return@Screen
        }
        item { ProEntryCard(onPro) }
        items(rows.take(80), key = { it.usage.pkg }) { row -> AppRowView(row, sort) { selected = row.usage.pkg } }
        item { Note(stringResource(R.string.method_apps), Modifier.padding(horizontal = 4.dp)) }
    }

    val sel = rows?.find { it.usage.pkg == selected }
    if (sel != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = LocalSurfaces.current.card) {
            AppDetail(sel, days)
        }
    }
}

@Composable
private fun sortLabel(s: AppSort) = stringResource(
    when (s) {
        AppSort.BATTERY -> R.string.sort_battery
        AppSort.SCREEN -> R.string.sort_screen
        AppSort.DATA -> R.string.sort_data
        AppSort.BACKGROUND -> R.string.sort_background
        AppSort.STORAGE -> R.string.sort_storage
    }
)

@Composable
private fun AppRowView(row: AppRow, sort: AppSort, onClick: () -> Unit) {
    val u = row.usage
    val context = LocalContext.current
    val primary = when (sort) {
        AppSort.BATTERY -> if (row.mwh > 0) Format.energy(row.mwh).toString() else "–"
        AppSort.SCREEN -> Format.duration(u.screenSeconds)
        AppSort.DATA -> Format.bytes(u.totalBytes).toString()
        AppSort.BACKGROUND -> if (u.serviceSeconds > 0) Format.duration(u.serviceSeconds) else Format.bytes(u.backgroundBytes).toString()
        AppSort.STORAGE -> Format.bytes(u.storageBytes).toString()
    }
    val secondary = listOfNotNull(
        u.screenSeconds.takeIf { it > 0 && sort != AppSort.SCREEN }?.let { context.getString(R.string.row_on_screen, Format.duration(it)) },
        u.totalBytes.takeIf { it > 0 && sort != AppSort.DATA }?.let { Format.bytes(it).toString() },
        u.serviceSeconds.takeIf { it > 60 && sort != AppSort.BACKGROUND }?.let { context.getString(R.string.row_background, Format.duration(it)) },
        u.storageBytes.takeIf { it > 0 && sort == AppSort.BATTERY }?.let { context.getString(R.string.row_storage, Format.bytes(it).toString()) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(u.pkg, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                row.unusual?.let {
                    Spacer(Modifier.width(6.dp))
                    Badge(context.getString(R.string.unusual_badge, Format.number(it, 1)), Metric.Warn)
                }
            }
            if (secondary.isNotEmpty()) Note(secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppDetail(row: AppRow, days: Int) {
    val u = row.usage
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(u.pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(u.label, style = MaterialTheme.typography.titleLarge)
                Note(u.pkg)
            }
        }
        Spacer(Modifier.height(14.dp))
        row.unusual?.let {
            Card {
                Text(
                    stringResource(R.string.unusual_body, Format.number(it, 1), Format.energy(row.usualMwh ?: 0.0).toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            if (days == 1) stringResource(R.string.range_today) else stringResource(R.string.range_7d),
            style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted,
        )
        StatLine(stringResource(R.string.detail_battery), if (row.mwh > 0) Format.energy(row.mwh).toString() else "–", Metric.Energy)
        StatLine(stringResource(R.string.detail_screen), Format.duration(u.screenSeconds))
        StatLine(stringResource(R.string.detail_launches), u.launches.toString())
        StatLine(stringResource(R.string.detail_service), Format.duration(u.serviceSeconds))
        StatLine(stringResource(R.string.detail_wifi), Format.bytes(u.wifiBytes).toString(), Metric.Network)
        StatLine(stringResource(R.string.detail_mobile), Format.bytes(u.mobileBytes).toString(), Metric.Network)
        StatLine(stringResource(R.string.detail_background_data), Format.bytes(u.backgroundBytes).toString())
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.detail_storage_title), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
        StatLine(stringResource(R.string.detail_app), u.appBytes?.let { Format.bytes(it).toString() } ?: "–", Metric.Disk)
        StatLine(stringResource(R.string.detail_data), u.dataBytes?.let { Format.bytes(it).toString() } ?: "–", Metric.Disk)
        StatLine(stringResource(R.string.detail_cache), u.cacheBytes?.let { Format.bytes(it).toString() } ?: "–")
        if (u.lastUsedMillis > 0) {
            StatLine(
                stringResource(R.string.detail_last_used),
                android.text.format.DateUtils.getRelativeTimeSpanString(u.lastUsedMillis).toString(),
            )
        }
        val pro = rememberLoaded(u.pkg) { app.history.proReports(1).firstOrNull() }
        pro?.forPackage(u.pkg)?.let { p ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.detail_pro_title, android.text.format.DateUtils.getRelativeTimeSpanString(pro.timeMillis).toString()),
                style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted,
            )
            StatLine(stringResource(R.string.detail_pro_battery), Format.number(p.mah, 1) + " mAh", Metric.Energy)
            if (p.wakelockMs > 0) StatLine(stringResource(R.string.detail_pro_awake), Format.duration(p.wakelockMs / 1000))
            if (p.cpuMs > 0) StatLine(stringResource(R.string.detail_pro_cpu), Format.duration(p.cpuMs / 1000))
            p.pssBytes?.let { StatLine(stringResource(R.string.detail_pro_ram), Format.bytes(it).toString(), Metric.Memory) }
        }
        AppBackgroundSection(u.pkg)
        Spacer(Modifier.height(10.dp))
        Note(stringResource(R.string.method_foreground))
        if (!u.pkg.startsWith("#")) {
            Button(onClick = { Actions.appInfo(context, u.pkg) }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Text(stringResource(R.string.detail_open_app_info))
            }
            Note(stringResource(R.string.detail_app_info_hint), Modifier.padding(top = 6.dp))
        }
    }
}
