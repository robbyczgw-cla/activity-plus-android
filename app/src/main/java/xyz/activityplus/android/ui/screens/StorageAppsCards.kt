package xyz.activityplus.android.ui.screens

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.ForegroundTracker
import xyz.activityplus.android.core.PackageStorage
import xyz.activityplus.android.core.StorageApps
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.SegmentBar
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

// Sections 1, 3 and 4 of the Storage page.

/** Every installed app's size and last use. Null apps means usage access is missing. */
class StorageAppsData(val apps: List<PackageStorage>?, val userCacheBytes: Long?)

/**
 * Reading StorageStats for every package takes a moment, and the caches and unused-apps cards both
 * need it, so the second card to ask gets the result of the first. A return to the screen (new [key])
 * always reads again, since the user may just have cleared a cache or uninstalled an app.
 */
private object StorageAppsLoader {
    private const val KEEP_MILLIS = 10_000L
    private var key = -1
    private var at = 0L
    private var last: StorageAppsData? = null

    @Synchronized
    fun load(context: Context, key: Int): StorageAppsData {
        val now = System.currentTimeMillis()
        last?.let { if (key == this.key && now - at < KEEP_MILLIS) return it }
        val data = read(context, now)
        if (data.apps != null) {
            last = data
            this.key = key
            at = System.currentTimeMillis()
        }
        return data
    }

    private fun read(context: Context, now: Long): StorageAppsData {
        if (!ForegroundTracker.hasUsageAccess(context)) return StorageAppsData(null, null)
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val user = Process.myUserHandle()
        val lastUsed = runCatching {
            usage.queryAndAggregateUsageStats(now - StorageApps.HISTORY_DAYS * StorageApps.DAY, now)
        }.getOrNull().orEmpty()
        val apps = pm.getInstalledApplications(0).mapNotNull { a ->
            val s = runCatching { stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, user) }.getOrNull()
                ?: return@mapNotNull null
            PackageStorage(
                pkg = a.packageName,
                label = pm.getApplicationLabel(a).toString(),
                // Updated system apps keep FLAG_SYSTEM, so they stay out of the unused list.
                system = a.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                appBytes = s.appBytes,
                dataBytes = s.dataBytes,
                cacheBytes = s.cacheBytes,
                lastUsedMillis = lastUsed[a.packageName]?.lastTimeUsed ?: 0L,
                installedMillis = runCatching { pm.getPackageInfo(a.packageName, 0).firstInstallTime }.getOrDefault(0L),
            )
        }
        val userCache = runCatching { stats.queryStatsForUser(StorageManager.UUID_DEFAULT, user).cacheBytes }.getOrNull()
        return StorageAppsData(apps, userCache)
    }
}

/** Keeps showing the last result while a refresh runs, so coming back does not flash the loading note. */
@Composable
private fun <T> keepLast(value: T?): T? {
    var kept by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != null) kept = value }
    return value ?: kept
}

// 1. Breakdown

private class BreakdownResult(val parts: StorageApps.Breakdown, val detailed: Boolean)

private fun loadBreakdown(context: Context, access: Boolean): BreakdownResult {
    val stats = context.getSystemService(StorageStatsManager::class.java)
    var total = runCatching { stats.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
    var free = runCatching { stats.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
    if (total == null || free == null) {
        val fs = StatFs(Environment.getDataDirectory().path)
        total = fs.totalBytes
        free = fs.availableBytes
    }
    if (access) {
        val user = Process.myUserHandle()
        val internal = runCatching { stats.queryStatsForUser(StorageManager.UUID_DEFAULT, user) }.getOrNull()
        val external = runCatching { stats.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, user) }.getOrNull()
        if (internal != null && external != null) {
            // getDataBytes() already includes the app's files on shared storage (Android/data, obb);
            // adding ExternalStorageStats.getAppBytes() would count them twice.
            val apps = internal.appBytes + internal.dataBytes
            return BreakdownResult(
                StorageApps.breakdown(total, free, apps, external.imageBytes, external.videoBytes, external.audioBytes), true,
            )
        }
    }
    return BreakdownResult(StorageApps.breakdown(total, free, 0, 0, 0, 0), false)
}

@Composable
fun StorageBreakdownSection() {
    val context = LocalContext.current
    val access = rememberUsageAccess()
    val resumes = rememberResumes()
    val result = keepLast(rememberLoaded(access, resumes) { loadBreakdown(context, access) })
    Card {
        CardHeader(
            stringResource(R.string.storageapps_break_title), Metric.Disk,
            caption = result?.let { stringResource(R.string.storageapps_break_caption, Format.bytes(it.parts.freeBytes).toString()) },
        )
        if (result == null) {
            Note(stringResource(R.string.storageapps_loading), Modifier.padding(top = 8.dp))
            return@Card
        }
        val b = result.parts
        Text(
            stringResource(R.string.storageapps_break_used, Format.bytes(b.usedBytes).toString(), Format.bytes(b.totalBytes).toString()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )
        val free = LocalSurfaces.current.muted.copy(alpha = 0.35f)
        val parts: List<Triple<String, Long, Color>> = if (result.detailed) {
            listOf(
                Triple(stringResource(R.string.storageapps_part_apps), b.appBytes, Metric.Cpu),
                Triple(stringResource(R.string.storageapps_part_images), b.imageBytes, Metric.Gpu),
                Triple(stringResource(R.string.storageapps_part_videos), b.videoBytes, Metric.Memory),
                Triple(stringResource(R.string.storageapps_part_audio), b.audioBytes, Metric.Network),
                Triple(stringResource(R.string.storageapps_part_other), b.otherBytes, Metric.Disk),
                Triple(stringResource(R.string.storageapps_part_free), b.freeBytes, free),
            )
        } else {
            listOf(
                Triple(stringResource(R.string.storageapps_part_used), b.usedBytes, Metric.Disk),
                Triple(stringResource(R.string.storageapps_part_free), b.freeBytes, free),
            )
        }
        SegmentBar(parts.filter { it.second > 0 }, b.totalBytes)
        Note(
            stringResource(if (result.detailed) R.string.storageapps_break_note else R.string.storageapps_break_needs_access),
            Modifier.padding(top = 8.dp),
        )
    }
}

// 3. Caches

@Composable
fun StorageCachesSection() {
    val context = LocalContext.current
    val access = rememberUsageAccess()
    val resumes = rememberResumes()
    val data = keepLast(rememberLoaded(access, resumes) { StorageAppsLoader.load(context, resumes) })
    Card {
        CardHeader(stringResource(R.string.storageapps_cache_title), Metric.Network)
        val apps = data?.apps
        if (!access || apps == null) {
            Note(
                stringResource(if (!access) R.string.storageapps_needs_access else R.string.storageapps_loading),
                Modifier.padding(top = 8.dp),
            )
            return@Card
        }
        val caches = remember(data) { StorageApps.caches(apps, data?.userCacheBytes) }
        if (caches.top.isEmpty()) {
            Note(stringResource(R.string.storageapps_cache_empty), Modifier.padding(top = 8.dp))
        } else {
            Text(
                stringResource(R.string.storageapps_cache_total, Format.bytes(caches.totalBytes).toString()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            caches.top.forEach { a ->
                AppLine(a, Format.bytes(a.cacheBytes).toString(), Modifier.clickable { Actions.appInfo(context, a.pkg) })
            }
        }
        Note(stringResource(R.string.storageapps_cache_note), Modifier.padding(top = 8.dp))
    }
}

/** Icon and name; a bold [value] on the right, or with [detail] a quiet second line and a [trailing] button. */
@Composable
private fun AppLine(
    app: PackageStorage,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.pkg, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalSurfaces.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (detail == null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        trailing()
    }
}

// 4. Unused apps

/** Android shows its own confirmation every time; nothing is removed without it. */
private fun uninstall(context: Context, pkg: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Actions.appInfo(context, pkg)
    } catch (_: SecurityException) {
        Actions.appInfo(context, pkg)
    }
}

private const val COLLAPSED_ROWS = 8

@Composable
fun StorageUnusedSection() {
    val context = LocalContext.current
    val access = rememberUsageAccess()
    val resumes = rememberResumes()
    var days by rememberSaveable { mutableStateOf(StorageApps.DEFAULT_UNUSED_DAYS) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val data = keepLast(rememberLoaded(access, resumes) { StorageAppsLoader.load(context, resumes) })
    Card {
        CardHeader(stringResource(R.string.storageapps_unused_title), Metric.Warn)
        val apps = data?.apps
        if (!access || apps == null) {
            Note(
                stringResource(if (!access) R.string.storageapps_needs_access else R.string.storageapps_loading),
                Modifier.padding(top = 8.dp),
            )
            return@Card
        }
        val now = remember(data) { System.currentTimeMillis() }
        val unused = remember(data, days) { StorageApps.unused(apps, now, days) }
        Note(stringResource(R.string.storageapps_unused_for), Modifier.padding(top = 8.dp, bottom = 4.dp))
        Chips(StorageApps.UNUSED_CHOICES, days, { stringResource(R.string.storageapps_days, it) }) { days = it }
        if (unused.apps.isEmpty()) {
            Text(
                stringResource(R.string.storageapps_unused_none, days),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(
                pluralStringResource(
                    R.plurals.storageapps_unused_summary, unused.apps.size, unused.apps.size, Format.bytes(unused.totalBytes).toString(),
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            val shown = if (showAll) unused.apps else unused.apps.take(COLLAPSED_ROWS)
            shown.forEach { a ->
                val age = StorageApps.age(a.lastUsedMillis, now)
                val last = if (age == null) {
                    stringResource(R.string.storageapps_last_none)
                } else if (age.unit == StorageApps.AgeUnit.DAYS) {
                    pluralStringResource(R.plurals.storageapps_last_days, age.count, age.count)
                } else {
                    pluralStringResource(R.plurals.storageapps_last_months, age.count, age.count)
                }
                AppLine(
                    a, "", detail = stringResource(R.string.storageapps_detail, Format.bytes(a.sizeBytes).toString(), last),
                    trailing = {
                        TextButton(onClick = { uninstall(context, a.pkg) }) { Text(stringResource(R.string.storageapps_uninstall)) }
                    },
                )
            }
            if (unused.apps.size > COLLAPSED_ROWS) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(
                        if (showAll) stringResource(R.string.storageapps_show_less)
                        else stringResource(R.string.storageapps_show_all, unused.apps.size),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.storageapps_unused_note))
    }
}
