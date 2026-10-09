package xyz.activityplus.android.ui.screens

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import xyz.activityplus.android.R
import xyz.activityplus.android.Tab
import xyz.activityplus.android.core.ChargeStatus
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.ThermalStatus
import xyz.activityplus.android.core.Transport
import xyz.activityplus.android.core.WeeklyReport
import xyz.activityplus.android.data.Monitor
import xyz.activityplus.android.data.WeeklyLoader
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.FindingText
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.BigValue
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.CoreBars
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLive
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.BrandEnd
import xyz.activityplus.android.ui.theme.BrandStart
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import kotlin.math.abs

@Composable
fun OverviewScreen(onOpen: (Tab) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val (s, series) = rememberLive()
    val settings = rememberSettings()
    val access = rememberUsageAccess()
    // Reload the per-app part once a minute; the live cards update every tick.
    val minute = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000); minute.intValue++
        }
    }
    val data = rememberLoaded(access, minute.intValue) { loadApps(1, withStorage = false) }
    val findings = if (s != null && data != null) diagnose(context, s, data) else emptyList()
    // Monday to Wednesday, once the last week has enough data.
    val weekly = rememberLoaded(access) {
        if (WeeklyReport.cardDay(System.currentTimeMillis())) WeeklyLoader.load(context).takeIf { it.report.enough } else null
    }

    Screen(
        title = stringResource(R.string.app_name),
        subtitle = s?.let { stringResource(R.string.overview_subtitle, Build.MODEL, Format.duration(it.uptimeSeconds)) },
        action = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
    ) {
        if (!access) item { UsageAccessCard() }
        item { Verdict(findings, data, settings.fahrenheit, onOpen) }
        if (weekly != null) item { WeeklyCard(weekly) { onOpen(Tab.WEEKLY) } }
        if (s == null) return@Screen
        item {
            CardRow(
                { BatteryCard(s, series, settings.fahrenheit) { onOpen(Tab.BATTERY) } },
                { MemoryCard(s, series) { onOpen(Tab.HISTORY) } },
            )
        }
        item {
            CardRow(
                { NetworkCard(s, series, settings.bits) { onOpen(Tab.HISTORY) } },
                { StorageCard(s) { onOpen(Tab.APPS) } },
            )
        }
        item {
            CardRow(
                { ClockCard(s) { onOpen(Tab.HISTORY) } },
                { ThermalCard(s, series, settings.fahrenheit) { onOpen(Tab.HISTORY) } },
            )
        }
        if (data != null && data.energy.isNotEmpty()) item { BusiestCard(data) { onOpen(Tab.APPS) } }
    }
}

/** Builds the diagnosis input from the live snapshot and today's per-app data. */
fun diagnose(context: Context, s: Snapshot, data: AppsData): List<Diagnosis.Finding> {
    val animator = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return Diagnosis.run(
        Diagnosis.Input(
            storageFreeBytes = s.storage.freeBytes,
            storageTotalBytes = s.storage.totalBytes,
            memAvailableBytes = s.memory.availableBytes,
            memThresholdBytes = s.memory.thresholdBytes,
            lowMemory = s.memory.lowMemory,
            thermal = s.thermal.status,
            batteryTempC = s.battery.temperatureC,
            powerSave = s.powerSave,
            uptimeSeconds = s.uptimeSeconds,
            healthFraction = s.battery.healthFraction,
            batteryHealth = s.battery.health,
            animatorScale = animator,
            foregroundLabel = s.foregroundPackage?.let { appName(context, it, data.labels) },
            apps = data.apps,
            energy = data.energy,
            capacityMah = s.battery.estimatedFullMah ?: s.battery.designMah,
            voltage = s.battery.voltageV,
            nowMillis = s.timeMillis,
            ownPackage = context.packageName,
        )
    )
}

@Composable
private fun Verdict(findings: List<Diagnosis.Finding>, data: AppsData?, fahrenheit: Boolean, onOpen: (Tab) -> Unit) {
    val context = LocalContext.current
    val top = findings.firstOrNull { it.severity >= Diagnosis.Severity.WARN }
        ?: findings.firstOrNull { it.kind == Diagnosis.Kind.TOP_DRAIN_APP }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(BrandStart.copy(alpha = 0.9f), BrandEnd.copy(alpha = 0.9f))))
            .clickable { onOpen(Tab.DIAGNOSIS) }
            .padding(18.dp)
    ) {
        Column {
            Text(
                stringResource(R.string.verdict_question),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            if (top != null) {
                val t = FindingText.of(context, top, fahrenheit)
                Text(t.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(t.body, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                if (findings.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        context.resources.getQuantityString(R.plurals.verdict_more, findings.size - 1, findings.size - 1),
                        color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Text(stringResource(R.string.verdict_fine), color = Color.White, style = MaterialTheme.typography.titleLarge)
                val busiest = data?.energy?.filterKeys { !it.startsWith("#") && it != context.packageName }?.maxByOrNull { it.value.first }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (busiest != null) stringResource(
                        R.string.verdict_fine_busiest, appName(context, busiest.key, data.labels),
                        Format.energy(busiest.value.first).toString(),
                    ) else stringResource(R.string.verdict_fine_body),
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CardRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Box(Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}

@Composable
private fun MetricCard(
    title: String, color: Color, icon: ImageVector, caption: String?, onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth().fillMaxHeight(), onClick = onClick) {
        CardHeader(title, color, icon, caption)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BatteryCard(s: Snapshot, series: Monitor.Series, fahrenheit: Boolean, onClick: () -> Unit) {
    val b = s.battery
    val power = b.powerMw
    MetricCard(
        stringResource(R.string.card_battery), Metric.Energy, Icons.Outlined.BatteryChargingFull,
        Format.percent(b.levelFraction), onClick,
    ) {
        BigValue(power?.let { Format.watts(abs(it)) })
        Note(
            when {
                power == null -> stringResource(R.string.battery_no_current)
                b.status == ChargeStatus.CHARGING -> stringResource(R.string.battery_charging)
                power < 0 -> stringResource(R.string.battery_drawing)
                else -> stringResource(R.string.battery_on_adapter)
            }
        )
        Spacer(Modifier.height(6.dp))
        Sparkline(series.power.map { it?.let { p -> abs(p) } }, Metric.Energy, height = 36.dp)
        Spacer(Modifier.height(6.dp))
        StatLine(stringResource(R.string.battery_current), b.currentMa?.let { Format.number(it, 0) + " mA" } ?: "–")
        StatLine(stringResource(R.string.battery_voltage), Format.number(b.voltageV, 2) + " V")
    }
}

@Composable
private fun MemoryCard(s: Snapshot, series: Monitor.Series, onClick: () -> Unit) {
    val m = s.memory
    MetricCard(
        stringResource(R.string.card_memory), Metric.Memory, Icons.Outlined.Memory,
        stringResource(R.string.memory_of, Format.bytes(m.totalBytes).toString()), onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(Format.bytes(m.usedBytes))
            if (m.lowMemory) {
                Spacer(Modifier.width(6.dp)); Badge(stringResource(R.string.memory_low), Metric.Warn)
            }
        }
        Note(stringResource(R.string.memory_in_use))
        Spacer(Modifier.height(6.dp))
        Sparkline(series.memory, Metric.Memory, height = 36.dp, max = 1.0)
        Spacer(Modifier.height(6.dp))
        StatLine(stringResource(R.string.memory_available), Format.bytes(m.availableBytes).toString())
        if (m.swapTotalBytes > 0) StatLine(stringResource(R.string.memory_swap), Format.bytes(m.swapUsedBytes).toString())
    }
}

@Composable
private fun NetworkCard(s: Snapshot, series: Monitor.Series, bits: Boolean, onClick: () -> Unit) {
    val n = s.network
    MetricCard(
        stringResource(R.string.card_network), Metric.Network, Icons.Outlined.Language,
        stringResource(
            when (n.transport) {
                Transport.WIFI -> R.string.net_wifi
                Transport.CELLULAR -> R.string.net_mobile
                Transport.ETHERNET -> R.string.net_ethernet
                Transport.VPN -> R.string.net_vpn
                Transport.NONE -> R.string.net_offline
            }
        ),
        onClick,
    ) {
        BigValue(Format.rate(n.rxBytesPerSecond, bits))
        Note(stringResource(R.string.net_down))
        Spacer(Modifier.height(6.dp))
        Sparkline(series.rx, Metric.Network, height = 36.dp)
        Spacer(Modifier.height(6.dp))
        StatLine(stringResource(R.string.net_up), Format.rate(n.txBytesPerSecond, bits).toString())
        n.wifiRssi?.let { StatLine(stringResource(R.string.net_signal), "$it dBm") }
    }
}

@Composable
private fun StorageCard(s: Snapshot, onClick: () -> Unit) {
    val st = s.storage
    MetricCard(
        stringResource(R.string.card_storage), Metric.Disk, Icons.Outlined.SdStorage,
        stringResource(R.string.storage_of, Format.bytes(st.totalBytes).toString()), onClick,
    ) {
        BigValue(Format.bytes(st.freeBytes))
        Note(stringResource(R.string.storage_free))
        Spacer(Modifier.height(14.dp))
        Meter(1 - st.freeFraction, if (st.freeFraction < 0.1) Metric.Heat else Metric.Disk)
        Spacer(Modifier.height(10.dp))
        StatLine(stringResource(R.string.storage_used), Format.bytes(st.usedBytes).toString())
    }
}

@Composable
private fun ClockCard(s: Snapshot, onClick: () -> Unit) {
    val cpu = s.cpu
    MetricCard(
        stringResource(R.string.card_cpu), Metric.Cpu, Icons.Outlined.Speed,
        stringResource(R.string.cpu_cores, cpu.cores.size), onClick,
    ) {
        BigValue(cpu.averageMhz?.let { Format.ghz(it) })
        Note(stringResource(R.string.cpu_clock_note))
        Spacer(Modifier.height(8.dp))
        CoreBars(cpu.cores.map { c -> if (c.curMhz != null && c.maxMhz != null && c.maxMhz > 0) c.curMhz / c.maxMhz else null }, Metric.Cpu, height = 34.dp)
        Spacer(Modifier.height(6.dp))
        StatLine(stringResource(R.string.cpu_own), Format.percent(cpu.ownCpuFraction))
    }
}

@Composable
private fun ThermalCard(s: Snapshot, series: Monitor.Series, fahrenheit: Boolean, onClick: () -> Unit) {
    val t = s.thermal
    val color = if (t.status >= ThermalStatus.MODERATE || s.battery.temperatureC >= 42) Metric.Heat else Metric.Energy
    MetricCard(
        stringResource(R.string.card_temperature), Metric.Heat, Icons.Outlined.DeviceThermostat,
        thermalLabel(t.status), onClick,
    ) {
        BigValue(Format.temperature(s.battery.temperatureC, fahrenheit))
        Note(stringResource(R.string.temp_battery))
        Spacer(Modifier.height(6.dp))
        Sparkline(series.temperature, color, height = 36.dp, min = null)
        Spacer(Modifier.height(6.dp))
        t.headroom?.let { StatLine(stringResource(R.string.temp_headroom), Format.percent((1 - it).coerceIn(0.0, 1.0))) }
        t.sensors.firstOrNull()?.let { StatLine(it.name, Format.temperature(it.celsius, fahrenheit).toString()) }
    }
}

@Composable
fun thermalLabel(status: ThermalStatus): String = stringResource(
    when (status) {
        ThermalStatus.NONE -> R.string.thermal_none
        ThermalStatus.LIGHT -> R.string.thermal_light
        ThermalStatus.MODERATE -> R.string.thermal_moderate
        ThermalStatus.SEVERE -> R.string.thermal_severe
        else -> R.string.thermal_critical
    }
)

@Composable
private fun BusiestCard(data: AppsData, onClick: () -> Unit) {
    val context = LocalContext.current
    val total = data.energy.values.sumOf { it.first }.coerceAtLeast(1.0)
    Card(onClick = onClick) {
        CardHeader(stringResource(R.string.busiest_title), MaterialTheme.colorScheme.onSurface, caption = stringResource(R.string.busiest_caption))
        Spacer(Modifier.height(8.dp))
        data.energy.entries.sortedByDescending { it.value.first }.take(5).forEach { (pkg, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appName(context, pkg, data.labels), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Note(Format.duration(v.second.toLong()))
                }
                Text(Format.percent(v.first / total), color = LocalSurfaces.current.muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.method_foreground))
    }
}
