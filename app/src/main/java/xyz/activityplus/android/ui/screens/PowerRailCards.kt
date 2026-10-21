package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.PowerRails
import xyz.activityplus.android.core.RailCategory
import xyz.activityplus.android.data.PowerMonitors
import xyz.activityplus.android.data.PowerRailStore
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.service.PowerRailSampler
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.BigValue
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.SegmentBar
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import kotlin.math.roundToLong

/*
 * "Where the power goes": power per component from Android's power monitors. The numbers come from
 * PowerRails (pure) and PowerRailSampler (energy per 15 minutes in power.db); this file only draws them.
 */

/** Whether this phone reports power per component at all; null while Android is asked, false shows [PowerRailUnavailable]. */
@Composable
fun rememberPowerRailsSupport(): Boolean? {
    val context = LocalContext.current
    val possible = PowerMonitors.possible()
    return produceState<Boolean?>(if (possible) null else false, possible) {
        if (possible) value = withContext(Dispatchers.IO) { PowerMonitors.supported(context) }
    }.value
}

internal enum class PowerWindow { NOW, HOUR, TODAY }

/** Below this the meters report nothing usable. */
private const val SILENT_MW = 20.0

/** Now needs exact readings; the others are averages of what the sampler stored. */
private const val NOW_MIN_MS = 4_000L
private const val NOW_SPAN_MS = 10_000L

private class StoredWindow(val rates: Map<String, Double>, val coveredMs: Long, val batteryMw: Double?)

private fun loadWindow(context: Context, window: PowerWindow, nowMillis: Long): StoredWindow {
    val from = PowerRails.slotOf(if (window == PowerWindow.HOUR) nowMillis - 3_600_000L else ActivityPlusApp.startOfDay(nowMillis))
    val rows = PowerRailStore.get(context).rows(from)
    // The same span from the minute history, to put the monitors next to what the battery gave out.
    val draw = PowerRails.batteryDrawMw(app.history.samples(from).map { it.powerMw to it.charging })
    return StoredWindow(PowerRails.rates(rows), PowerRails.coveredMs(rows), draw)
}

@Composable
fun PowerRailCard(battery: BatteryState, onPro: () -> Unit) {
    val context = LocalContext.current
    // Re-checked on return: the grant may have just been given on the pro page.
    val fine = remember(rememberResumes()) { ProShell.hasFinePowerMonitors(context) }
    val options = if (fine) PowerWindow.entries else listOf(PowerWindow.HOUR, PowerWindow.TODAY)
    var chosen by remember { mutableStateOf(PowerWindow.NOW) }
    val window = chosen.takeIf { it in options } ?: PowerWindow.HOUR

    val live = remember(fine) { PowerRails.Live(NOW_SPAN_MS) }
    var liveRates by remember(fine) { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var liveMs by remember(fine) { mutableLongStateOf(0L) }
    var recorded by remember { mutableIntStateOf(0) }

    // Only while the screen is visible: exact readings every 2 s for Now, otherwise a stored sample now and then.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, fine, window) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            live.clear()
            liveRates = emptyMap()
            liveMs = 0L
            while (true) {
                val readings = PowerRailSampler.sample(context, if (fine) 30_000L else 60_000L)
                if (readings != null && fine) {
                    live.add(readings)
                    liveRates = live.rates()
                    liveMs = live.spanMs()
                }
                recorded++
                delay(if (fine && window == PowerWindow.NOW) 2_000L else if (fine) 30_000L else 60_000L)
            }
        }
    }
    // Reloaded after each stored sample; Now does not read the store.
    val stored = rememberLoaded(window, if (window == PowerWindow.NOW) 0 else recorded) {
        if (window == PowerWindow.NOW) null else loadWindow(context, window, System.currentTimeMillis())
    }

    val rates: Map<String, Double>?
    val coveredMs: Long
    val drawMw: Double?
    val enough: Boolean
    if (window == PowerWindow.NOW) {
        rates = liveRates
        coveredMs = liveMs
        drawMw = battery.avgPowerMw?.takeIf { !battery.plugged && it < 0 }?.let { -it }
        enough = liveMs >= NOW_MIN_MS
    } else {
        rates = stored?.rates
        coveredMs = stored?.coveredMs ?: 0L
        drawMw = stored?.batteryMw
        enough = PowerRails.enoughData(coveredMs, fine)
    }

    PowerRailContent(
        options, window, { chosen = it }, rates, coveredMs, enough, drawMw, fine,
        canGetExact = !fine && ProShell.finePowerMonitorsExist(context), onPro = onPro,
    )
}

/** The card for readings already taken; [rates] is null while the stored ones load. */
@Composable
internal fun PowerRailContent(
    options: List<PowerWindow>,
    window: PowerWindow,
    onChoose: (PowerWindow) -> Unit,
    rates: Map<String, Double>?,
    coveredMs: Long,
    enough: Boolean,
    drawMw: Double?,
    fine: Boolean,
    canGetExact: Boolean,
    onPro: () -> Unit,
) {
    Card {
        CardHeader(
            stringResource(R.string.power_title), Metric.Energy, Icons.Outlined.PieChart,
            if (window != PowerWindow.NOW && coveredMs > 0) stringResource(R.string.power_covered, Format.duration(coveredMs / 1000)) else null,
        )
        Spacer(Modifier.height(8.dp))
        Chips(options, window, { windowLabel(it) }, onChoose)
        Spacer(Modifier.height(10.dp))
        if (rates == null) {
            Note(stringResource(R.string.loading))
        } else if (!enough) {
            Note(
                if (window == PowerWindow.NOW) stringResource(R.string.power_wait_now)
                else stringResource(R.string.power_wait, Format.duration(PowerRails.minWindowMs(fine) / 1000))
            )
        } else if (rates.values.sum() < SILENT_MW) {
            // A whole phone never runs on a few milliwatts: such meters are placeholders (the emulator's are).
            Note(stringResource(R.string.power_silent))
        } else {
            RailBreakdown(rates, drawMw)
        }
        Spacer(Modifier.height(8.dp))
        Note(stringResource(if (fine) R.string.power_note_exact else R.string.power_note_coarse))
        // Exact readings need a permission that only some Android 16+ builds define.
        if (canGetExact) {
            TextButton(onClick = onPro, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.power_get_exact)) }
        }
    }
}

/**
 * One line where the card would be on a phone that cannot show it: Android before 15 has no power
 * monitors to ask, and many phones on 15 and later report none. Neither changes with time or a grant.
 */
@Composable
fun PowerRailUnavailable(possible: Boolean = PowerMonitors.possible()) {
    Note(
        stringResource(if (possible) R.string.power_unsupported else R.string.es_power_needs_android15),
        Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RailBreakdown(rates: Map<String, Double>, drawMw: Double?) {
    val breakdown = remember(rates) { PowerRails.breakdown(rates) }
    val rest = PowerRails.restMw(breakdown.totalMw, drawMw)
    val total = breakdown.totalMw + (rest ?: 0.0)
    var details by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Bottom) {
        BigValue(Format.watts(breakdown.totalMw))
        Spacer(Modifier.width(8.dp))
        Note(stringResource(R.string.power_measured), Modifier.padding(bottom = 6.dp))
    }
    Spacer(Modifier.height(8.dp))
    val parts = breakdown.categories.filter { it.mw >= 1 }.map { Triple(categoryLabel(it.category), it.mw.roundToLong(), categoryColor(it.category)) } +
        listOfNotNull(rest?.let { Triple(stringResource(R.string.power_rest), it.roundToLong(), LocalSurfaces.current.line) })
    SegmentBar(parts, parts.sumOf { it.second }) { mw ->
        val share = if (total > 0) mw / total else 0.0
        Format.watts(mw.toDouble()).toString() + " · " + if (share < 0.01) "<1 %" else Format.percent(share)
    }
    if (rest != null) Note(stringResource(R.string.power_rest_note), Modifier.padding(top = 6.dp))

    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
        Text(stringResource(if (details) R.string.power_details_hide else R.string.power_details_show))
    }
    if (details) {
        // The raw names, because they differ per phone and an "Other" row is useless without one.
        breakdown.details.forEach { d ->
            StatLine(
                d.name + " · " + categoryLabel(d.category) + if (d.counted) "" else " · " + stringResource(R.string.power_not_added),
                Format.watts(d.mw).toString(),
            )
        }
    }
}

@Composable
private fun windowLabel(w: PowerWindow) = stringResource(
    when (w) {
        PowerWindow.NOW -> R.string.power_now
        PowerWindow.HOUR -> R.string.power_hour
        PowerWindow.TODAY -> R.string.power_today
    }
)

@Composable
private fun categoryLabel(c: RailCategory) = stringResource(categoryName(c))

@StringRes
private fun categoryName(c: RailCategory) = when (c) {
    RailCategory.DISPLAY -> R.string.power_cat_display
    RailCategory.CPU -> R.string.power_cat_cpu
    RailCategory.GPU -> R.string.power_cat_gpu
    RailCategory.MOBILE_RADIO -> R.string.power_cat_radio
    RailCategory.WIFI_BT -> R.string.power_cat_wifi
    RailCategory.CAMERA -> R.string.power_cat_camera
    RailCategory.MEMORY -> R.string.power_cat_memory
    RailCategory.GNSS -> R.string.power_cat_gnss
    RailCategory.AUDIO -> R.string.power_cat_audio
    RailCategory.OTHER -> R.string.power_cat_other
}

@Composable
private fun categoryColor(c: RailCategory): Color = when (c) {
    RailCategory.DISPLAY -> Metric.Warn
    RailCategory.CPU -> Metric.Cpu
    RailCategory.GPU -> Metric.Gpu
    RailCategory.MOBILE_RADIO -> Metric.Network
    RailCategory.WIFI_BT -> Metric.Energy
    RailCategory.CAMERA -> Metric.Heat
    RailCategory.MEMORY -> Metric.Memory
    RailCategory.GNSS -> Metric.Disk
    RailCategory.AUDIO -> LocalSurfaces.current.muted.copy(alpha = 0.6f)
    RailCategory.OTHER -> LocalSurfaces.current.muted
}
