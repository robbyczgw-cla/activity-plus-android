package xyz.activityplus.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryHealth
import xyz.activityplus.android.core.BatteryMath
import xyz.activityplus.android.core.BatterySession
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.ChargeStatus
import xyz.activityplus.android.core.DrainTracker
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.PlugType
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Monitor
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.BigValue
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLive
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import kotlin.math.abs

@Composable
fun BatteryScreen(onPro: () -> Unit = {}) {
    // One sample per second while this screen is open: charging power changes fast.
    val (s, series) = rememberLive(fastMillis = 1000L)
    val settings = rememberSettings()
    val access = rememberUsageAccess()
    val minute = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000); minute.intValue++
        }
    }
    val data = rememberLoaded(access, minute.intValue) { loadApps(1, withStorage = false) }
    val sessions = rememberLoaded(minute.intValue) { app.history.sessions(12) }
    val context = LocalContext.current
    val background = rememberLoaded(minute.intValue) { loadBackgroundToday(context) }

    Screen(title = stringResource(R.string.tab_battery)) {
        if (s == null) return@Screen
        val b = s.battery
        item { LiveCard(s, series, settings.fahrenheit) }
        val running = sessions?.firstOrNull()?.takeIf { it.charging == b.plugged }
        if (b.plugged) item { ChargingCard(b, running) } else item { SinceUnplugCard(b, running) }
        item { ChargerTestCard(s, settings.fahrenheit) }
        // Which app is drawing the power right now.
        if (!b.plugged && s.foregroundPackage != null && data != null) item { NowDrawingCard(s, data) }
        item { ProEntryCard(onPro) }
        item { HealthCard(b) }
        val energy = data?.energy
        if (energy != null && energy.isNotEmpty()) {
            item { DrainByAppCard(b, energy, data) }
        } else if (data != null) {
            item { Card { Note(stringResource(R.string.drain_empty)) } }
        }
        if (background != null) item { BackgroundTodayCard(background, onPro) }
        val past = sessions?.filter { it !== running && it.seconds >= 120 }
        if (!past.isNullOrEmpty()) item { SessionsCard(past) }
    }
}

@Composable
private fun LiveCard(s: Snapshot, series: Monitor.Series, fahrenheit: Boolean) {
    val b = s.battery
    Card {
        CardHeader(stringResource(R.string.card_battery), Metric.Energy, Icons.Outlined.BatteryChargingFull, statusLabel(b))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(Format.Scaled(Format.number(b.levelFraction * 100, 0), "%"))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                BigValue(b.avgPowerMw?.let { Format.watts(abs(it)) })
                Note(
                    when {
                        b.avgPowerMw == null -> stringResource(R.string.battery_no_current)
                        b.avgPowerMw!! > 0 -> stringResource(R.string.battery_charging)
                        else -> stringResource(R.string.battery_drawing)
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Meter(b.levelFraction, if (b.levelFraction < 0.15) Metric.Heat else Metric.Energy, height = 10.dp)
        Spacer(Modifier.height(10.dp))
        Sparkline(series.power.map { it?.let(::abs) }, Metric.Energy, height = 56.dp)
        Note(stringResource(R.string.battery_live_chart))
        Spacer(Modifier.height(8.dp))
        // Instant and averaged side by side: the instant value jumps with every frame drawn.
        StatLine(stringResource(R.string.battery_power_now), b.powerMw?.let { Format.watts(it).toString() } ?: "–")
        StatLine(
            stringResource(R.string.battery_current),
            b.currentMa?.let { cur ->
                Format.number(cur, 0) + " mA" + (b.avgCurrentMa?.let { " · Ø " + Format.number(it, 0) } ?: "")
            } ?: stringResource(R.string.not_reported),
        )
        StatLine(stringResource(R.string.battery_voltage), Format.number(b.voltageV, 3) + " V")
        b.percentPerHour?.let { StatLine(stringResource(R.string.battery_rate), (if (it > 0) "+" else "") + Format.number(it, 1) + " %/h") }
        b.timeLeftSeconds?.let {
            StatLine(stringResource(if (b.charging) R.string.charging_time_to_full else R.string.battery_time_left), Format.duration(it))
        }
        StatLine(stringResource(R.string.temp_battery), Format.temperature(b.temperatureC, fahrenheit).toString())
        b.chargeMah?.let { ch ->
            StatLine(stringResource(R.string.battery_charge_now), Format.number(ch, 0) + " mAh · " + Format.energy(ch * b.voltageV).toString())
        }
    }
}

@Composable
private fun statusLabel(b: BatteryState) = stringResource(
    when (b.status) {
        ChargeStatus.CHARGING -> R.string.status_charging
        ChargeStatus.DISCHARGING -> R.string.status_discharging
        ChargeStatus.NOT_CHARGING -> R.string.status_not_charging
        ChargeStatus.FULL -> R.string.status_full
        ChargeStatus.UNKNOWN -> R.string.status_unknown
    }
)

@Composable
private fun speedLabel(speed: BatteryMath.ChargeSpeed) = stringResource(
    when (speed) {
        BatteryMath.ChargeSpeed.TRICKLE -> R.string.speed_trickle
        BatteryMath.ChargeSpeed.SLOW -> R.string.speed_slow
        BatteryMath.ChargeSpeed.NORMAL -> R.string.speed_normal
        BatteryMath.ChargeSpeed.FAST -> R.string.speed_fast
        BatteryMath.ChargeSpeed.SUPER_FAST -> R.string.speed_super
    }
)

@Composable
private fun ChargingCard(b: BatteryState, session: BatterySession?) {
    Card {
        CardHeader(
            stringResource(R.string.charging_title), Metric.Energy, Icons.Outlined.ElectricalServices,
            stringResource(
                when (b.plug) {
                    PlugType.AC -> R.string.plug_ac
                    PlugType.USB -> R.string.plug_usb
                    PlugType.WIRELESS -> R.string.plug_wireless
                    PlugType.DOCK -> R.string.plug_dock
                    PlugType.NONE -> R.string.plug_ac
                }
            ),
        )
        Spacer(Modifier.height(8.dp))
        val watts = b.avgPowerMw?.takeIf { it > 0 }?.div(1000)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(watts?.let { Format.watts(it * 1000) })
            Spacer(Modifier.width(10.dp))
            if (watts != null) Badge(speedLabel(BatteryMath.chargeSpeed(watts)), Metric.Energy)
        }
        Note(stringResource(R.string.charging_speed_note))
        Spacer(Modifier.height(8.dp))
        b.percentPerHour?.takeIf { it > 0 }?.let { StatLine(stringResource(R.string.battery_rate), "+" + Format.number(it, 1) + " %/h") }
        b.timeLeftSeconds?.let { StatLine(stringResource(R.string.charging_time_to_full), Format.duration(it)) }
        b.adapterMaxWatts?.let { StatLine(stringResource(R.string.charging_adapter_offers), Format.number(it, 1) + " W") }
        if (session != null && session.seconds >= 60) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.session_since_plug, Format.duration(session.seconds.toLong())), style = MaterialTheme.typography.titleSmall)
            StatLine(stringResource(R.string.session_level), levelRange(session))
            StatLine(stringResource(R.string.session_energy_in), Format.energy(session.energyMwh).toString())
            StatLine(stringResource(R.string.session_avg_peak), "${Format.watts(session.averageMw)} · ${Format.watts(session.peakMw)}")
        }
        // Why it is not charging although plugged in.
        if (b.status == ChargeStatus.NOT_CHARGING || b.status == ChargeStatus.FULL || (b.avgCurrentMa ?: b.currentMa ?: 0.0) < 0) {
            val reason = when {
                b.status == ChargeStatus.FULL || b.levelFraction >= 0.995 -> R.string.why_full
                b.temperatureC >= 43 || b.health == BatteryHealth.OVERHEAT -> R.string.why_hot
                b.temperatureC <= 5 || b.health == BatteryHealth.COLD -> R.string.why_cold
                b.levelFraction in 0.78..0.92 && b.status == ChargeStatus.NOT_CHARGING -> R.string.why_limit
                (b.avgCurrentMa ?: b.currentMa ?: 0.0) < 0 -> R.string.why_weak
                else -> R.string.why_unknown
            }
            Spacer(Modifier.height(6.dp))
            Badge(stringResource(R.string.not_charging_badge), Metric.Warn)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(reason), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SinceUnplugCard(b: BatteryState, session: BatterySession?) {
    if (session == null || session.seconds < 60) return
    Card {
        CardHeader(
            stringResource(R.string.session_since_unplug_title), Metric.Energy, Icons.Outlined.BatteryStd,
            Format.duration(session.seconds.toLong()),
        )
        Spacer(Modifier.height(8.dp))
        StatLine(stringResource(R.string.session_level), levelRange(session))
        // The level moves in 1 % steps, so the session rate means something only after a while.
        session.percentPerHour?.takeIf { session.seconds >= 900 }?.let {
            StatLine(stringResource(R.string.session_avg_rate), Format.number(it, 1) + " %/h")
        }
        StatLine(stringResource(R.string.session_screen_on), Format.duration(session.screenOnSeconds.toLong()))
        StatLine(stringResource(R.string.session_screen_off), Format.duration(session.screenOffSeconds.toLong()))
        StatLine(stringResource(R.string.session_energy_out), Format.energy(session.energyMwh).toString())
        StatLine(stringResource(R.string.session_avg_peak), "${Format.watts(session.averageMw)} · ${Format.watts(session.peakMw)}")
        // A whole charge at this session's pace, the figure people compare phones by.
        session.percentPerHour?.takeIf { it < -0.2 && session.seconds >= 900 }?.let {
            StatLine(stringResource(R.string.session_full_lasts), Format.duration((100 / -it * 3600).toLong()))
        }
    }
}

@Composable
private fun NowDrawingCard(s: Snapshot, data: AppsData) {
    val context = LocalContext.current
    val pkg = s.foregroundPackage ?: return
    val mw = s.battery.avgPowerMw?.takeIf { it < 0 } ?: return
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ElectricBolt, null, tint = Metric.Energy)
            Spacer(Modifier.width(8.dp))
            AppIcon(pkg, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.now_drawing, appName(context, pkg, data.labels), Format.watts(-mw).toString()),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HealthCard(b: BatteryState) {
    Card {
        CardHeader(stringResource(R.string.health_title), Metric.Energy, Icons.Outlined.HealthAndSafety)
        Spacer(Modifier.height(8.dp))
        // Two numbers on purpose: what Android says, and what the fuel gauge measures.
        StatLine(
            stringResource(R.string.health_system),
            stringResource(
                when (b.health) {
                    BatteryHealth.GOOD -> R.string.health_good
                    BatteryHealth.OVERHEAT -> R.string.health_overheat
                    BatteryHealth.COLD -> R.string.health_cold
                    BatteryHealth.DEAD, BatteryHealth.FAILURE, BatteryHealth.OVER_VOLTAGE -> R.string.health_problem
                    BatteryHealth.UNKNOWN -> R.string.not_reported
                }
            ),
        )
        StatLine(
            stringResource(R.string.health_measured),
            b.estimatedFullMah?.let { Format.number(it, 0) + " mAh" } ?: stringResource(R.string.not_reported),
        )
        StatLine(stringResource(R.string.health_design), b.designMah?.let { Format.number(it, 0) + " mAh" } ?: stringResource(R.string.not_reported))
        b.healthFraction?.let { StatLine(stringResource(R.string.health_estimate), Format.percent(it)) }
        StatLine(stringResource(R.string.health_cycles), b.cycleCount?.toString() ?: stringResource(R.string.not_reported))
        b.technology?.let { StatLine(stringResource(R.string.health_technology), it) }
        ChargingHealthTrend()
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.health_note))
    }
}

@Composable
private fun DrainByAppCard(b: BatteryState, energy: Map<String, Pair<Double, Double>>, data: AppsData) {
    val context = LocalContext.current
    Card {
        CardHeader(stringResource(R.string.drain_today), Metric.Energy, caption = stringResource(R.string.drain_today_caption))
        Spacer(Modifier.height(8.dp))
        val total = energy.values.sumOf { it.first }.coerceAtLeast(1.0)
        energy.entries.sortedByDescending { it.value.first }.take(10).forEach { (pkg, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appName(context, pkg, data.labels), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    // Duration, average power while it was measured, and the share of the battery.
                    val cap = b.capacityMah
                    val percent = if (cap != null && b.voltageV > 0) " · " + Format.number(v.first / (cap * b.voltageV) * 100, 1) + " %" else ""
                    Note(Format.duration(v.second.toLong()) + " · Ø " + Format.watts(v.first / (v.second / 3600).coerceAtLeast(1e-6)).toString() + percent)
                    Spacer(Modifier.height(3.dp))
                    Meter(v.first / total, Metric.Energy, height = 4.dp)
                }
                Spacer(Modifier.width(10.dp))
                Text(Format.energy(v.first).toString(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        energy[DrainTracker.SCREEN_OFF]?.let { off ->
            val cap = b.capacityMah
            if (cap != null && off.second > 1800) {
                val pph = off.first / (off.second / 3600) / (cap * b.voltageV) * 100
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bedtime, null, tint = LocalSurfaces.current.muted)
                    Spacer(Modifier.width(8.dp))
                    Note(stringResource(R.string.screen_off_rate, Format.number(pph, 1) + " %"))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.method_foreground))
    }
}

@Composable
private fun SessionsCard(sessions: List<BatterySession>) {
    val context = LocalContext.current
    Card {
        CardHeader(stringResource(R.string.sessions_title), Metric.Energy, Icons.Outlined.History)
        Spacer(Modifier.height(6.dp))
        sessions.take(8).forEach { se ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (se.charging) "⚡ " else "") + levelRange(se),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    Text(Format.energy(se.energyMwh).toString(), style = MaterialTheme.typography.bodyLarge)
                }
                Note(
                    listOfNotNull(
                        DateUtils.formatDateTime(context, se.startMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
                        Format.duration(se.seconds.toLong()),
                        "Ø " + Format.watts(se.averageMw),
                        se.percentPerHour?.let { (if (it > 0) "+" else "") + Format.number(it, 1) + " %/h" },
                    ).joinToString(" · ")
                )
            }
        }
    }
}

private fun levelRange(s: BatterySession) =
    "${Format.number(s.startLevel * 100, 0)} → ${Format.number(s.endLevel * 100, 0)} %"
