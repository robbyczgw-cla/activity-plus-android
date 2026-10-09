package xyz.activityplus.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ChargeHealth
import xyz.activityplus.android.core.ChargerMeasurement
import xyz.activityplus.android.core.ChargerTestRun
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.ChargingStore
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.Sparkline
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.Metric

/**
 * Measures what the charger and cable deliver for 30 seconds from the live snapshots, saves the
 * result under a name, and ranks the saved tests. The button shows only while charging.
 */
@Composable
fun ChargerTestCard(s: Snapshot, fahrenheit: Boolean) {
    val b = s.battery
    val context = LocalContext.current
    val store = remember { ChargingStore.get(context) }
    var version by remember { mutableIntStateOf(0) }
    val tests = rememberLoaded(version) { store.tests() }
    var run by remember { mutableStateOf<ChargerTestRun?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<ChargerMeasurement?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    val active = run
    LaunchedEffect(active) {
        if (active == null) return@LaunchedEffect
        app.monitor.latest.filterNotNull().collect { snap ->
            val sb = snap.battery
            elapsed = active.elapsedSeconds(snap.timeMillis)
            when (active.add(snap.timeMillis, sb.charging, sb.powerMw, sb.voltageV, sb.currentMa)) {
                ChargerTestRun.State.RUNNING -> Unit
                ChargerTestRun.State.ABORTED -> { message = R.string.chg_test_stopped; run = null }
                ChargerTestRun.State.DONE -> {
                    val r = active.result()
                    if (r != null) pending = r else message = R.string.chg_test_no_current
                    run = null
                }
            }
        }
    }
    // The screen has to stay awake for the 30 seconds, or the samples slow down.
    val view = LocalView.current
    DisposableEffect(active != null) {
        view.keepScreenOn = active != null
        onDispose { view.keepScreenOn = false }
    }

    val list = tests.orEmpty()
    if (!b.charging && active == null && pending == null && list.isEmpty()) return
    Card {
        CardHeader(stringResource(R.string.chg_test_title), Metric.Energy, Icons.Outlined.ElectricBolt)
        Spacer(Modifier.height(8.dp))
        if (active != null) {
            Text(
                stringResource(R.string.chg_test_running, elapsed, active.durationSeconds),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { elapsed.toFloat() / active.durationSeconds }, modifier = Modifier.fillMaxWidth(), color = Metric.Energy)
            TextButton(onClick = { run = null }) { Text(stringResource(R.string.cancel)) }
        } else {
            if (b.charging) {
                Note(stringResource(R.string.chg_test_intro))
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    message = null
                    elapsed = 0
                    run = ChargerTestRun(s.timeMillis, b.levelFraction, b.temperatureC)
                }) { Text(stringResource(R.string.chg_test_button)) }
            }
            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Metric.Warn)
            }
        }
        if (list.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.chg_test_past), style = MaterialTheme.typography.titleSmall)
            list.forEach { t ->
                val m = t.m
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name.ifBlank { stringResource(R.string.chg_test_unnamed) },
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        )
                        Note(
                            listOf(
                                stringResource(R.string.chg_test_peak, Format.watts(m.peakMw).toString()),
                                Format.number(m.avgVoltageV, 2) + " V",
                                Format.number(m.avgCurrentMa, 0) + " mA",
                                Format.number(m.startLevel * 100, 0) + " % · " + Format.temperature(m.startTempC, fahrenheit),
                                DateUtils.formatDateTime(context, m.timeMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
                            ).joinToString(" · ")
                        )
                    }
                    Text("Ø " + Format.watts(m.avgMw), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { store.deleteTest(t.id); version++ }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                    }
                }
            }
            Note(stringResource(R.string.chg_test_compare_note))
        }
    }

    pending?.let { m ->
        var name by remember(m) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.chg_test_save_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.chg_test_save_summary, Format.watts(m.avgMw).toString(), Format.watts(m.peakMw).toString()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                        label = { Text(stringResource(R.string.chg_test_name_label)) },
                        placeholder = { Text(stringResource(R.string.chg_test_name_hint)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { store.saveTest(name, m); version++; pending = null }) { Text(stringResource(R.string.chg_test_save)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.chg_test_discard)) } },
        )
    }
}

/** Inside the Health card: the full capacity estimated from each finished charge. Hidden below two. */
@Composable
fun ChargingHealthTrend() {
    val context = LocalContext.current
    val estimates = rememberLoaded { ChargingStore.get(context).capacity() } ?: return
    val trend = ChargeHealth.trend(estimates) ?: return
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.chg_trend_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Sparkline(estimates.map { it.mah }, Metric.Energy, height = 48.dp, min = null)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // No day, so it reads "July", or "July 2025" when that is not this year.
        val since = DateUtils.formatDateTime(context, trend.firstMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY)
        Text(
            stringResource(R.string.chg_trend_change, ChargeHealth.signedPercent(trend.change), since),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Note(pluralStringResource(R.plurals.chg_trend_count, trend.count, trend.count))
    }
    Note(stringResource(R.string.chg_trend_note))
}
