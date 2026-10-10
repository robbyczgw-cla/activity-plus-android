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
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.activityplus.android.R
import xyz.activityplus.android.core.AppGrowth
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.StorageForecast
import xyz.activityplus.android.core.StorageGrowth
import xyz.activityplus.android.data.StorageGrowthStore
import xyz.activityplus.android.service.StorageGrowthRecorder
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.Metric
import java.time.LocalDate

data class StorageGrowthData(
    /** Days with a device snapshot in the last 30. */
    val snapshots: Int,
    val forecast: StorageForecast,
    /** Used bytes per day, last 30 days, null where none was taken. */
    val used: List<Double?>,
    val totalBytes: Long?,
    /** Whether any app sizes were recorded (they need usage access). */
    val hasApps: Boolean,
    val month: List<AppGrowth>,
    /** Time of the snapshot the month's growth counts from. */
    val monthFromMillis: Long?,
    val week: Map<String, AppGrowth>,
    val unusual: Set<String>,
)

private const val DAYS = 30

fun loadStorageGrowth(context: Context): StorageGrowthData {
    val store = StorageGrowthStore.get(context)
    val today = LocalDate.now()
    val device = store.device(today.minusDays(DAYS.toLong()))
    // One day more than shown, so "30 days ago" is still there at any time of day.
    val apps = store.apps(today.minusDays(DAYS.toLong() + 1))
    val month = StorageGrowth.growth(apps, DAYS)
    val latest = apps.maxOfOrNull { it.timeMillis }
    return StorageGrowthData(
        snapshots = device.count { !it.day.isBefore(today.minusDays(DAYS - 1L)) },
        forecast = StorageGrowth.forecast(device),
        used = StorageGrowth.usedSeries(device, today, DAYS),
        totalBytes = device.lastOrNull()?.totalBytes,
        hasApps = apps.isNotEmpty(),
        month = month,
        monthFromMillis = month.firstOrNull()?.let { g -> latest?.let { it - (g.spanDays * StorageGrowth.DAY_MS).toLong() } },
        week = StorageGrowth.growth(apps, 7).associateBy { it.pkg },
        unusual = StorageGrowth.unusual(apps),
    )
}

/** Forecast, used storage over 30 days, and the apps that grew the most this month. */
@Composable
fun StorageGrowthSection() {
    val context = LocalContext.current
    val resumes = rememberResumes()
    var version by remember { mutableIntStateOf(0) }
    // Today's snapshot, when the service has not taken it (live monitor off, or the app was just set up).
    LaunchedEffect(resumes) {
        if (withContext(Dispatchers.IO) { StorageGrowthRecorder.recordIfDue(context) }) version++
    }
    val data = rememberLoaded(resumes, version) { loadStorageGrowth(context) } ?: return
    Card {
        CardHeader(stringResource(R.string.sgrowth_title), Metric.Disk, Icons.AutoMirrored.Outlined.TrendingUp)
        Spacer(Modifier.height(8.dp))
        Text(forecastText(data.forecast), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (data.forecast.kind == StorageForecast.Kind.FILLING) {
            Note(stringResource(R.string.sgrowth_per_day, Format.bytes(-data.forecast.bytesPerDay.toLong()).toString()))
        }
        if (data.snapshots < 2) {
            Spacer(Modifier.height(4.dp))
            Note(stringResource(R.string.sgrowth_starts_today))
        } else {
            Spacer(Modifier.height(10.dp))
            UsedSparkline(data.used, data.totalBytes)
            Note(stringResource(R.string.sgrowth_used_30))
        }

        val top = data.month.filter { it.growthBytes >= StorageGrowth.MB }.take(5)
        if (!data.hasApps) {
            Spacer(Modifier.height(8.dp))
            Note(stringResource(R.string.sgrowth_no_access))
        } else if (data.month.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.sgrowth_top_title), style = MaterialTheme.typography.titleSmall)
            // A younger history says from when it counts.
            val from = data.monthFromMillis
            if (from != null && data.month.first().spanDays < DAYS - 1.5) {
                Note(stringResource(R.string.sgrowth_since, DateUtils.formatDateTime(context, from, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)))
            }
            if (top.isEmpty()) Note(stringResource(R.string.sgrowth_none))
            top.forEach { GrowthRow(it, data.week[it.pkg], it.pkg in data.unusual) }
            Spacer(Modifier.height(4.dp))
            Note(stringResource(R.string.sgrowth_note))
        }
    }
}

@Composable
private fun forecastText(f: StorageForecast): String = when (f.kind) {
    StorageForecast.Kind.TOO_FEW -> stringResource(R.string.sgrowth_too_few)
    StorageForecast.Kind.STABLE -> stringResource(R.string.sgrowth_stable)
    StorageForecast.Kind.FREEING -> stringResource(R.string.sgrowth_freeing)
    StorageForecast.Kind.FILLING -> {
        val days = (f.daysLeft ?: 0).coerceAtLeast(1)
        pluralStringResource(R.plurals.sgrowth_full_in, days, days)
    }
}

/** Zoomed in on the used range, but never so far that a few MB look like a cliff: at least 2 % of the disk tall. */
@Composable
private fun UsedSparkline(used: List<Double?>, totalBytes: Long?) {
    val values = used.filterNotNull()
    if (values.isEmpty()) return
    val lo = values.min()
    val hi = values.max()
    val minRange = (totalBytes ?: 0L) * 0.02
    val pad = ((minRange - (hi - lo)) / 2).coerceAtLeast((hi - lo) * 0.1)
    Sparkline(used, Metric.Disk, min = (lo - pad).coerceAtLeast(0.0), max = hi + pad)
}

private fun signed(bytes: Long) = (if (bytes > 0) "+" else "") + Format.bytes(bytes)

@Composable
private fun GrowthRow(g: AppGrowth, week: AppGrowth?, unusual: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { Actions.appInfo(context, g.pkg) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(g.pkg, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(appName(context, g.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val details = listOfNotNull(
                if (g.isNew) stringResource(R.string.sgrowth_new) else g.percent?.let { "+" + Format.percent(it) },
                week?.takeIf { it.growthBytes != 0L && it.spanDays < g.spanDays }?.let { stringResource(R.string.sgrowth_week, signed(it.growthBytes)) },
                g.cacheGrowthBytes.takeIf { kotlin.math.abs(it) >= StorageGrowth.MB }?.let { stringResource(R.string.sgrowth_cache, signed(it)) },
            )
            if (details.isNotEmpty()) Note(details.joinToString(" · "))
        }
        Spacer(Modifier.width(10.dp))
        if (unusual) {
            Badge(stringResource(R.string.sgrowth_unusual), Metric.Warn)
            Spacer(Modifier.width(8.dp))
        }
        Text(signed(g.growthBytes), style = MaterialTheme.typography.bodyLarge)
    }
}
