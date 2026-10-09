package xyz.activityplus.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.pro.ProParser
import xyz.activityplus.android.pro.ProReport
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.pro.ProShell.ready
import xyz.activityplus.android.pro.ShellService
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

@Composable
fun ProScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    // Re-check on every return: the user may have just started Shizuku.
    var checks by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) checks++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val access = remember(checks) { ProShell.access(context) }
    var saved by remember { mutableIntStateOf(0) }
    val reports = rememberLoaded(saved) { app.history.proReports(10) }
    var selected by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun measure() {
        running = true
        error = null
        scope.launch {
            runCatching {
                // One report after the other through the same service; any one may fail on a given
                // phone, the others still count.
                val battery = runCatching { ProParser.batteryCheckin(ProShell.run(context, ShellService.BATTERY)) }.getOrNull()
                val full = access == ProShell.Access.SHIZUKU
                val memory = if (full) runCatching { ProParser.meminfoPss(ProShell.run(context, ShellService.MEMORY)) }.getOrNull() else null
                val cpu = if (full) runCatching { ProParser.cpuinfo(ProShell.run(context, ShellService.CPU)) }.getOrNull() else null
                val report = ProReport.build(System.currentTimeMillis(), battery, memory, cpu)
                check(report.apps.isNotEmpty()) { "empty" }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.history.savePro(report) }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            selected = 0
            saved++
            running = false
        }
    }

    Screen(title = stringResource(R.string.pro_title), subtitle = stringResource(R.string.pro_subtitle)) {
        item { AccessCard(access, running, ::measure) { checks++ } }
        error?.let { e -> item { Card { Text(stringResource(R.string.pro_error, e), color = Metric.Heat) } } }
        val report = reports?.getOrNull(selected)
        if (report != null) {
            if (reports.size > 1) {
                item {
                    Chips(reports.indices.toList().take(5), selected, {
                        DateUtils.formatDateTime(context, reports[it].timeMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE)
                    }) { selected = it }
                }
            }
            item { BatteryReportCard(report) }
            if (report.apps.any { (it.pssBytes ?: 0) > 0 }) item { MemoryReportCard(report) }
            if (report.apps.any { (it.cpuNow ?: 0.0) > 0 }) item { CpuReportCard(report) }
        }
        item { Note(stringResource(R.string.pro_privacy), Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun AccessCard(access: ProShell.Access, running: Boolean, onMeasure: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    Card {
        CardHeader(stringResource(R.string.pro_access_title), Metric.Cpu, Icons.Outlined.Science, accessLabel(access))
        Spacer(Modifier.height(8.dp))
        when (access) {
            ProShell.Access.DUMP, ProShell.Access.SHIZUKU -> {
                Note(stringResource(if (access == ProShell.Access.SHIZUKU) R.string.pro_ready_body else R.string.pro_ready_body_dump))
                Spacer(Modifier.height(10.dp))
                Button(onClick = onMeasure, enabled = !running, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    if (running) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.pro_measuring))
                    } else Text(stringResource(R.string.pro_measure))
                }
                Spacer(Modifier.height(6.dp))
                Note(stringResource(R.string.pro_dev_off_tip))
            }
            ProShell.Access.SHIZUKU_PERMISSION -> {
                Note(stringResource(R.string.pro_permission_body))
                Button(onClick = { ProShell.requestPermission { onChanged() } }, modifier = Modifier.padding(top = 10.dp)) {
                    Text(stringResource(R.string.pro_permission_button))
                }
            }
            ProShell.Access.SHIZUKU_NOT_RUNNING -> {
                Note(stringResource(R.string.pro_not_running_body))
                OutlinedButton(onClick = {
                    context.packageManager.getLaunchIntentForPackage(ProShell.SHIZUKU_PACKAGE)?.let { context.startActivity(it) }
                }, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.pro_open_shizuku)) }
            }
            ProShell.Access.SHIZUKU_NOT_INSTALLED, ProShell.Access.SHIZUKU_TOO_OLD -> {
                Step(1, stringResource(R.string.pro_step_install))
                Step(2, stringResource(R.string.pro_step_debugging))
                Step(3, stringResource(R.string.pro_step_start))
                Step(4, stringResource(R.string.pro_step_back))
                OutlinedButton(onClick = {
                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ProShell.SHIZUKU_PACKAGE}"))
                    runCatching { context.startActivity(market) }.onFailure {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.pro_get_shizuku)) }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.pro_pc_title), style = MaterialTheme.typography.titleSmall)
                Note(stringResource(R.string.pro_pc_body))
                val command = ProShell.computerCommand(context.packageName)
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp).clickable {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("adb", command))
                    },
                )
                Note(stringResource(R.string.pro_pc_note))
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$n.", color = Metric.Cpu, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun accessLabel(a: ProShell.Access) = stringResource(
    when (a) {
        ProShell.Access.DUMP -> R.string.pro_via_dump
        ProShell.Access.SHIZUKU -> R.string.pro_via_shizuku
        ProShell.Access.SHIZUKU_PERMISSION -> R.string.pro_state_permission
        ProShell.Access.SHIZUKU_NOT_RUNNING -> R.string.pro_state_not_running
        ProShell.Access.SHIZUKU_NOT_INSTALLED -> R.string.pro_state_off
        ProShell.Access.SHIZUKU_TOO_OLD -> R.string.pro_state_too_old
    }
)

@Composable
private fun ReportRow(pkg: String, value: String, detail: String?, fraction: Double, color: androidx.compose.ui.graphics.Color) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (pkg == ProReport.SYSTEM) stringResource(R.string.row_system) else appName(context, pkg, emptyMap()),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            )
            if (!detail.isNullOrEmpty()) Note(detail)
            Spacer(Modifier.height(3.dp))
            Meter(fraction, color, height = 4.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BatteryReportCard(r: ProReport) {
    val context = LocalContext.current
    // Rows that round to 0.00 mAh say nothing.
    val apps = r.apps.filter { it.mah >= 0.005 || it.wakelockMs > 60_000 }.sortedByDescending { it.mah }
    Card {
        CardHeader(
            stringResource(R.string.pro_battery_title), Metric.Energy, Icons.Outlined.BatteryChargingFull,
            r.onBatteryMs?.let { stringResource(R.string.pro_on_battery, Format.duration(it / 1000)) },
        )
        Note(DateUtils.getRelativeTimeSpanString(r.timeMillis).toString())
        Spacer(Modifier.height(6.dp))
        if (apps.isEmpty()) {
            Note(stringResource(R.string.pro_battery_empty))
        }
        val total = r.totalMah.coerceAtLeast(1e-9)
        apps.take(15).forEach { a ->
            val detail = listOfNotNull(
                Format.percent(a.mah / total),
                a.cpuMs.takeIf { it >= 60_000 }?.let { context.getString(R.string.pro_cpu_time, Format.duration(it / 1000)) },
                a.wakelockMs.takeIf { it >= 60_000 }?.let { context.getString(R.string.pro_awake, Format.duration(it / 1000)) },
            ).joinToString(" · ")
            ReportRow(a.pkg, Format.number(a.mah, if (a.mah < 1) 2 else if (a.mah < 10) 1 else 0) + " mAh", detail, a.mah / total, Metric.Energy)
        }
        if (r.hardware.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pro_hardware_title), style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted)
            r.hardware.entries.filter { it.value >= 0.005 }.sortedByDescending { it.value }.forEach { (k, v) ->
                StatLine(hardwareLabel(k), Format.number(v, if (v < 1) 2 else if (v < 10) 1 else 0) + " mAh")
            }
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.pro_battery_note))
    }
}

@Composable
private fun hardwareLabel(key: String): String = when (key) {
    "scrn" -> stringResource(R.string.pro_hw_screen)
    "cell" -> stringResource(R.string.pro_hw_cell)
    "wifi" -> stringResource(R.string.net_wifi)
    "blue" -> "Bluetooth"
    "idle" -> stringResource(R.string.pro_hw_idle)
    "cpu" -> "CPU"
    "camera" -> stringResource(R.string.pro_hw_camera)
    "flashlight" -> stringResource(R.string.pro_hw_flashlight)
    "audio" -> "Audio"
    "video" -> "Video"
    "sensors" -> stringResource(R.string.hw_sensors)
    "memory" -> "RAM"
    else -> key
}

@Composable
private fun MemoryReportCard(r: ProReport) {
    val apps = r.apps.filter { (it.pssBytes ?: 0) > 0 }.sortedByDescending { it.pssBytes }
    val max = apps.firstOrNull()?.pssBytes?.toDouble() ?: 1.0
    Card {
        CardHeader(stringResource(R.string.pro_memory_title), Metric.Memory, Icons.Outlined.Memory, Format.bytes(apps.sumOf { it.pssBytes ?: 0 }).toString())
        Spacer(Modifier.height(6.dp))
        apps.take(15).forEach { a -> ReportRow(a.pkg, Format.bytes(a.pssBytes ?: 0).toString(), null, (a.pssBytes ?: 0) / max, Metric.Memory) }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.pro_memory_note))
    }
}

@Composable
private fun CpuReportCard(r: ProReport) {
    val apps = r.apps.filter { (it.cpuNow ?: 0.0) > 0.05 }.sortedByDescending { it.cpuNow }
    val max = (apps.firstOrNull()?.cpuNow ?: 1.0).coerceAtLeast(1.0)
    Card {
        CardHeader(stringResource(R.string.pro_cpu_title), Metric.Cpu, Icons.Outlined.Speed)
        Spacer(Modifier.height(6.dp))
        apps.take(12).forEach { a -> ReportRow(a.pkg, Format.number(a.cpuNow ?: 0.0, 1) + " %", null, (a.cpuNow ?: 0.0) / max, Metric.Cpu) }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.pro_cpu_note))
    }
}

/** Entry card on the apps and battery screens. */
@Composable
fun ProEntryCard(onOpen: () -> Unit) {
    val latest = rememberLoaded(Unit) { app.history.proReports(1).firstOrNull() }
    Card(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.Science, null, tint = Metric.Cpu)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pro_entry_title), style = MaterialTheme.typography.titleMedium)
                Note(
                    latest?.let { stringResource(R.string.pro_entry_last, DateUtils.getRelativeTimeSpanString(it.timeMillis).toString()) }
                        ?: stringResource(R.string.pro_entry_body)
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}
