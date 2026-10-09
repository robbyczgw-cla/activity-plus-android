package xyz.activityplus.android.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryHealth
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.HardwareInfo
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.Transport
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.CoreBars
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.SegmentBar
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.rememberLive
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

private var cached: HardwareInfo? = null

@Composable
fun HardwareScreen() {
    val context = LocalContext.current
    val (s, _) = rememberLive()
    val settings = rememberSettings()
    val hw = rememberLoaded(Unit) { cached ?: HardwareInfo.read(context.applicationContext).also { cached = it } } ?: cached

    Screen(
        title = stringResource(R.string.hw_title),
        subtitle = hw?.let { "${it.manufacturer} ${it.model}" },
    ) {
        if (hw == null) {
            item { Note(stringResource(R.string.loading)) }
            return@Screen
        }
        item { DeviceCard(hw) }
        item { CpuCard(hw, s) }
        item { GpuCard(hw) }
        if (s != null) item { RamCard(s) }
        if (s != null) item { MemoryStorageCard(hw, s) }
        hw.display?.let { d -> item { DisplayCard(d) } }
        if (s != null) item { BatteryHwCard(s) }
        if (s != null) item { NetworkHwCard(hw, s, settings.bits) }
        if (hw.cameras.isNotEmpty()) item { CamerasCard(hw.cameras) }
        item { MediaCard(hw) }
        item { FeaturesCard(hw) }
        if (s != null) item { ThermalHwCard(s, settings.fahrenheit) }
        if (hw.sensors.isNotEmpty()) item { SensorsCard(hw.sensors) }
        item { Note(stringResource(R.string.hw_note), Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, color: Color, caption: String? = null, content: @Composable () -> Unit) {
    Card {
        CardHeader(title, color, icon, caption)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DeviceCard(hw: HardwareInfo) {
    val uptime = SystemClock.elapsedRealtime() / 1000
    val sleep = HardwareInfo.deepSleepSeconds()
    Section(stringResource(R.string.hw_device), Icons.Outlined.Smartphone, Metric.Cpu) {
        StatLine(stringResource(R.string.hw_model), "${hw.manufacturer} ${hw.model}")
        StatLine(stringResource(R.string.hw_codename), hw.device)
        StatLine(stringResource(R.string.hw_android), "${hw.androidVersion} (API ${hw.sdk})")
        StatLine(stringResource(R.string.hw_patch), hw.securityPatch)
        StatLine(stringResource(R.string.hw_build), hw.build)
        hw.kernel?.let { StatLine(stringResource(R.string.hw_kernel), it.substringBefore('-')) }
        StatLine(stringResource(R.string.hw_uptime), Format.duration(uptime))
        if (uptime > 0) StatLine(stringResource(R.string.hw_deep_sleep), Format.percent(sleep.toDouble() / uptime))
    }
}

@Composable
private fun CpuCard(hw: HardwareInfo, s: Snapshot?) {
    val soc = hw.soc
    Section(stringResource(R.string.hw_cpu), Icons.Outlined.DeveloperBoard, Metric.Cpu, soc.vendor) {
        Text(soc.name ?: soc.model ?: soc.hardware, style = MaterialTheme.typography.titleLarge)
        val raw = listOfNotNull(soc.model, soc.hardware).distinct().filter { it != soc.name }.joinToString(" · ")
        if (raw.isNotEmpty()) Note(raw)
        Spacer(Modifier.height(8.dp))
        val cores = s?.cpu?.cores
        if (cores != null && cores.any { it.curMhz != null }) {
            CoreBars(cores.map { c -> if (c.curMhz != null && c.maxMhz != null && c.maxMhz > 0) c.curMhz / c.maxMhz else null }, Metric.Cpu, height = 44.dp)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                cores.forEach { c ->
                    Text(
                        c.curMhz?.let { Format.number(it / 1000, 1) } ?: "–",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalSurfaces.current.muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        StatLine(stringResource(R.string.hw_cores), (s?.cpu?.cores?.size ?: soc.clusters.sumOf { it.first }).toString())
        soc.clusters.filter { it.third != null }.forEach { (count, min, max) ->
            StatLine(
                stringResource(R.string.hw_cluster, count),
                listOfNotNull(min?.let { Format.ghz(it).toString() }, max?.let { Format.ghz(it).toString() }).joinToString(" – "),
            )
        }
        soc.governor?.let { StatLine(stringResource(R.string.hw_governor), it) }
        StatLine(stringResource(R.string.hw_abi), soc.abis.firstOrNull() ?: "–")
    }
}

@Composable
private fun GpuCard(hw: HardwareInfo) {
    val g = hw.gpu
    Section(stringResource(R.string.hw_gpu), Icons.Outlined.ViewInAr, Metric.Gpu, g.vendor) {
        Text(g.renderer ?: stringResource(R.string.not_reported), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        g.glEs?.let { StatLine("OpenGL ES", it) }
        StatLine("Vulkan", g.vulkan ?: stringResource(R.string.hw_no))
    }
}

@Composable
private fun RamCard(s: Snapshot) {
    val m = s.memory
    // 24 h of memory use from the history, for "is it always this full?".
    val day = rememberLoaded(Unit) { app.history.samples(System.currentTimeMillis() - 86_400_000L).map { it.memUsed } }
    Section(stringResource(R.string.hw_ram), Icons.Outlined.Memory, Metric.Memory, Format.bytes(m.totalBytes).toString()) {
        SegmentBar(
            listOf(
                Triple(stringResource(R.string.ram_apps), m.appsBytes, Metric.Memory),
                Triple(stringResource(R.string.ram_cache), m.reclaimableBytes, Metric.Memory.copy(alpha = 0.45f)),
                Triple(stringResource(R.string.ram_free), (m.availableBytes - m.reclaimableBytes).coerceAtLeast(0), LocalSurfaces.current.muted.copy(alpha = 0.35f)),
            ),
            m.totalBytes,
        )
        Spacer(Modifier.height(6.dp))
        StatLine(stringResource(R.string.ram_pressure), ramPressure(m))
        StatLine(stringResource(R.string.ram_threshold), Format.bytes(m.thresholdBytes).toString())
        if (m.swapTotalBytes > 0) {
            StatLine(stringResource(R.string.hw_zram), "${Format.bytes(m.swapUsedBytes)} / ${Format.bytes(m.swapTotalBytes)}")
            m.zramRatio?.let { StatLine(stringResource(R.string.ram_zram_ratio), Format.number(it, 1) + " : 1") }
        }
        if (!day.isNullOrEmpty() && day.size > 10) {
            Spacer(Modifier.height(6.dp))
            Sparkline(day, Metric.Memory, height = 44.dp, max = 1.0)
            Note(stringResource(R.string.ram_day_chart, Format.percent(day.average()), Format.percent(day.max())))
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.ram_per_app_note))
    }
}

@Composable
private fun ramPressure(m: xyz.activityplus.android.core.MemoryState): String = stringResource(
    when {
        m.lowMemory || m.availableBytes < m.thresholdBytes * 1.3 -> R.string.ram_pressure_high
        m.availableBytes < m.totalBytes * 0.2 -> R.string.ram_pressure_medium
        else -> R.string.ram_pressure_low
    }
)

@Composable
private fun MemoryStorageCard(hw: HardwareInfo, s: Snapshot) {
    Section(stringResource(R.string.hw_storage), Icons.Outlined.Storage, Metric.Disk) {
        StatLine(stringResource(R.string.hw_internal), Format.bytes(s.storage.totalBytes).toString(), Metric.Disk)
        Meter(1 - s.storage.freeFraction, Metric.Disk, Modifier.padding(vertical = 4.dp))
        StatLine(stringResource(R.string.storage_free), Format.bytes(s.storage.freeBytes).toString())
        hw.volumes.forEach { v ->
            Spacer(Modifier.height(6.dp))
            StatLine(v.name, "${Format.bytes(v.freeBytes)} / ${Format.bytes(v.totalBytes)}", Metric.Disk)
        }
    }
}

@Composable
private fun DisplayCard(d: HardwareInfo.DisplayInfo) {
    Section(stringResource(R.string.hw_display), Icons.Outlined.PhoneAndroid, Metric.Network) {
        StatLine(stringResource(R.string.hw_resolution), "${d.widthPx} × ${d.heightPx}")
        d.inches?.let { StatLine(stringResource(R.string.hw_diagonal), Format.number(it, 1) + "″") }
        StatLine(stringResource(R.string.hw_density), "${d.dpi} dpi")
        StatLine(stringResource(R.string.hw_refresh_now), Format.number(d.refreshNow.toDouble(), 0) + " Hz")
        if (d.refreshRates.size > 1) StatLine(stringResource(R.string.hw_refresh_modes), d.refreshRates.joinToString(" / ") + " Hz")
        StatLine("HDR", d.hdr.joinToString(", ").ifEmpty { stringResource(R.string.hw_no) })
        StatLine(stringResource(R.string.hw_wide_color), stringResource(if (d.wideColor) R.string.hw_yes else R.string.hw_no))
    }
}

@Composable
private fun BatteryHwCard(s: Snapshot) {
    val b = s.battery
    Section(stringResource(R.string.card_battery), Icons.Outlined.BatteryChargingFull, Metric.Energy) {
        b.technology?.let { StatLine(stringResource(R.string.health_technology), it) }
        StatLine(stringResource(R.string.health_design), b.designMah?.let { Format.number(it, 0) + " mAh" } ?: stringResource(R.string.not_reported))
        b.estimatedFullMah?.let { StatLine(stringResource(R.string.health_measured), Format.number(it, 0) + " mAh") }
        StatLine(stringResource(R.string.health_cycles), b.cycleCount?.toString() ?: stringResource(R.string.not_reported))
        StatLine(stringResource(R.string.health_system), if (b.health == BatteryHealth.GOOD) stringResource(R.string.health_good) else b.health.name.lowercase())
        StatLine(stringResource(R.string.battery_voltage), Format.number(b.voltageV, 3) + " V")
        StatLine(stringResource(R.string.battery_current), b.currentMa?.let { Format.number(it, 0) + " mA" } ?: stringResource(R.string.not_reported))
    }
}

@Composable
private fun NetworkHwCard(hw: HardwareInfo, s: Snapshot, bits: Boolean) {
    val n = s.network
    Section(stringResource(R.string.card_network), Icons.Outlined.Language, Metric.Network) {
        if (n.transport == Transport.WIFI) {
            n.wifiGeneration?.let { StatLine(stringResource(R.string.hw_wifi_standard), "Wi-Fi $it") }
            n.wifiFrequencyMhz?.let { f ->
                val band = when {
                    f >= 5925 -> "6 GHz"
                    f >= 4900 -> "5 GHz"
                    else -> Format.number(2.4, 1) + " GHz"
                }
                StatLine(stringResource(R.string.hw_wifi_band), "$band · ${stringResource(R.string.hw_channel, channel(f))}")
            }
            n.wifiRssi?.let { StatLine(stringResource(R.string.net_signal), "$it dBm") }
            if (n.wifiTxMbps != null || n.wifiRxMbps != null) {
                StatLine(stringResource(R.string.hw_link), "↑ ${n.wifiTxMbps ?: "–"} · ↓ ${n.wifiRxMbps ?: "–"} Mbit/s")
            } else n.wifiLinkMbps?.let { StatLine(stringResource(R.string.hw_link), "$it Mbit/s") }
        }
        StatLine(stringResource(R.string.hw_vpn), stringResource(if (n.vpn) R.string.hw_yes else R.string.hw_no))
        hw.operator?.let { StatLine(stringResource(R.string.hw_operator), it) }
        hw.simSlots?.takeIf { it > 0 }?.let { StatLine(stringResource(R.string.hw_sim_slots), it.toString()) }
        var showIps by rememberSaveable { mutableStateOf(false) }
        if (n.addresses.isNotEmpty()) {
            // Addresses are hidden until asked for, so screenshots of this page stay shareable.
            Text(
                stringResource(if (showIps) R.string.hw_hide_ip else R.string.hw_show_ip),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp).clickable { showIps = !showIps },
            )
            if (showIps) n.addresses.forEach { Note(it) }
        }
        Spacer(Modifier.height(2.dp))
        StatLine(stringResource(R.string.net_down), Format.rate(n.rxBytesPerSecond, bits).toString())
    }
}

private fun channel(mhz: Int): Int = when {
    mhz == 2484 -> 14
    mhz in 2412..2472 -> (mhz - 2407) / 5
    mhz in 5160..5885 -> (mhz - 5000) / 5
    mhz >= 5955 -> (mhz - 5950) / 5
    else -> 0
}

@Composable
private fun CamerasCard(cameras: List<HardwareInfo.Camera>) {
    Section(stringResource(R.string.hw_cameras), Icons.Outlined.CameraAlt, Metric.Disk, cameras.size.toString()) {
        cameras.forEach { c ->
            val facing = stringResource(
                when (c.facing) {
                    HardwareInfo.Facing.BACK -> R.string.hw_cam_back
                    HardwareInfo.Facing.FRONT -> R.string.hw_cam_front
                    HardwareInfo.Facing.EXTERNAL -> R.string.hw_cam_external
                }
            )
            val parts = listOfNotNull(
                c.megapixels?.let { Format.number(it, 1) + " MP" },
                c.focalMm?.let { Format.number(it.toDouble(), 2) + " mm" },
                c.aperture?.let { "f/" + Format.number(it.toDouble(), 1) },
                if (c.ois) "OIS" else null,
                if (c.flash) stringResource(R.string.hw_flash) else null,
            )
            StatLine("$facing (${c.id})", parts.joinToString(" · "))
        }
    }
}

@Composable
private fun MediaCard(hw: HardwareInfo) {
    Section(stringResource(R.string.hw_media), Icons.Outlined.Movie, Metric.Gpu) {
        StatLine(
            "Widevine",
            hw.media.widevine?.let { if (it == "L1") "L1 · " + stringResource(R.string.hw_widevine_l1) else it } ?: stringResource(R.string.not_reported),
        )
        hw.media.decoders.forEach { (name, hardware) ->
            StatLine(name, stringResource(if (hardware) R.string.hw_decoder_hw else R.string.hw_decoder_sw), if (hardware) Metric.Energy else Color.Unspecified)
        }
    }
}

@Composable
private fun FeaturesCard(hw: HardwareInfo) {
    Section(stringResource(R.string.hw_features), Icons.Outlined.Tune, Metric.Cpu) {
        hw.features.forEach { (f, has) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(featureLabel(f), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted)
                if (has) Badge(stringResource(R.string.hw_yes), Metric.Energy) else Text("–", color = LocalSurfaces.current.muted)
            }
        }
    }
}

@Composable
private fun featureLabel(f: HardwareInfo.Feature) = stringResource(
    when (f) {
        HardwareInfo.Feature.NFC -> R.string.hw_f_nfc
        HardwareInfo.Feature.FINGERPRINT -> R.string.hw_f_fingerprint
        HardwareInfo.Feature.FACE -> R.string.hw_f_face
        HardwareInfo.Feature.ESIM -> R.string.hw_f_esim
        HardwareInfo.Feature.USB_HOST -> R.string.hw_f_usb_host
        HardwareInfo.Feature.INFRARED -> R.string.hw_f_ir
        HardwareInfo.Feature.BLUETOOTH_LE -> R.string.hw_f_ble
        HardwareInfo.Feature.WIFI_AWARE -> R.string.hw_f_wifi_aware
        HardwareInfo.Feature.UWB -> R.string.hw_f_uwb
    }
)

@Composable
private fun ThermalHwCard(s: Snapshot, fahrenheit: Boolean) {
    val t = s.thermal
    Section(stringResource(R.string.card_temperature), Icons.Outlined.DeviceThermostat, Metric.Heat, thermalLabel(t.status)) {
        StatLine(stringResource(R.string.temp_battery), Format.temperature(s.battery.temperatureC, fahrenheit).toString())
        t.headroom?.let { StatLine(stringResource(R.string.temp_headroom), Format.percent((1 - it).coerceIn(0.0, 1.0))) }
        if (t.sensors.isEmpty()) {
            Note(stringResource(R.string.hw_no_zones))
        } else {
            t.sensors.take(12).forEach { StatLine(it.name, Format.temperature(it.celsius, fahrenheit).toString()) }
        }
    }
}

@Composable
private fun SensorsCard(sensors: List<HardwareInfo.SensorInfo>) {
    var all by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.hw_sensors), Icons.Outlined.Sensors, Metric.Network, sensors.size.toString()) {
        (if (all) sensors else sensors.take(8)).forEach { sensor ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(sensor.name, style = MaterialTheme.typography.bodyMedium)
                Note(listOf(sensor.type, sensor.vendor).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
        if (sensors.size > 8) {
            Text(
                stringResource(if (all) R.string.hw_show_less else R.string.hw_show_all, sensors.size),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp).clickable { all = !all },
            )
        }
    }
}
