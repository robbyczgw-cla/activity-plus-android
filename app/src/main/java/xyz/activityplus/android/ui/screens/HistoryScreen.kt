package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.data.HistoryStore
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

enum class HistoryMetric(val color: Color) {
    LEVEL(Metric.Energy), POWER(Metric.Energy), TEMPERATURE(Metric.Heat),
    MEMORY(Metric.Memory), NETWORK(Metric.Network), CLOCK(Metric.Cpu),
}

enum class HistoryRange(val hours: Int) { H12(12), H24(24), D7(24 * 7), D30(24 * 30) }

/** A downsampled point: average value and the app on screen most of that time. */
private data class Bucket(val start: Long, val value: Double?, val app: String?, val charging: Boolean)

@Composable
fun HistoryScreen() {
    val settings = rememberSettings()
    var metric by rememberSaveable { mutableStateOf(HistoryMetric.POWER) }
    var range by rememberSaveable { mutableStateOf(HistoryRange.H24) }
    val now = remember(range) { System.currentTimeMillis() }
    val from = now - range.hours * 3_600_000L
    val rows = rememberLoaded(range, now) { app.history.samples(from, now) }
    val energy = rememberLoaded(range, now) {
        app.history.energy(ActivityPlusApp.dayKey(from), ActivityPlusApp.dayKey(now))
    }
    val context = LocalContext.current

    Screen(title = stringResource(R.string.tab_history), subtitle = stringResource(R.string.history_subtitle)) {
        item {
            Column {
                Chips(HistoryRange.entries, range, { rangeLabel(it) }) { range = it }
                Chips(HistoryMetric.entries, metric, { metricLabel(it) }) { metric = it }
            }
        }
        item {
            Card {
                if (rows == null) {
                    Note(stringResource(R.string.loading))
                } else if (rows.size < 2) {
                    Note(stringResource(R.string.history_empty))
                } else {
                    // A young history fills the chart instead of hiding at its right edge.
                    Chart(rows, maxOf(from, rows.first().ts), now, metric, settings)
                }
            }
        }
        if (!energy.isNullOrEmpty()) {
            item {
                Card {
                    CardHeader(stringResource(R.string.history_drain_title), Metric.Energy, caption = rangeLabel(range))
                    Spacer(Modifier.height(8.dp))
                    val total = energy.sumOf { it.mwh }.coerceAtLeast(1.0)
                    energy.take(8).forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(e.pkg, 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(appName(context, e.pkg, emptyMap()), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(3.dp))
                                Meter(e.mwh / total, Metric.Energy, height = 4.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(Format.energy(e.mwh).toString(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        item { Note(stringResource(R.string.history_note), Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun rangeLabel(r: HistoryRange) = stringResource(
    when (r) {
        HistoryRange.H12 -> R.string.range_12h
        HistoryRange.H24 -> R.string.range_24h
        HistoryRange.D7 -> R.string.range_7d
        HistoryRange.D30 -> R.string.range_30d
    }
)

@Composable
private fun metricLabel(m: HistoryMetric) = stringResource(
    when (m) {
        HistoryMetric.LEVEL -> R.string.metric_level
        HistoryMetric.POWER -> R.string.metric_power
        HistoryMetric.TEMPERATURE -> R.string.metric_temperature
        HistoryMetric.MEMORY -> R.string.metric_memory
        HistoryMetric.NETWORK -> R.string.metric_network
        HistoryMetric.CLOCK -> R.string.metric_clock
    }
)

private fun valueOf(r: HistoryStore.Row, m: HistoryMetric): Double? = when (m) {
    HistoryMetric.LEVEL -> r.level
    HistoryMetric.POWER -> r.powerMw?.let { abs(it) }
    HistoryMetric.TEMPERATURE -> r.tempC
    HistoryMetric.MEMORY -> r.memUsed
    HistoryMetric.NETWORK -> r.rxBps + r.txBps
    HistoryMetric.CLOCK -> r.clock
}

private fun format(v: Double, m: HistoryMetric, s: Settings): String = when (m) {
    HistoryMetric.LEVEL, HistoryMetric.MEMORY, HistoryMetric.CLOCK -> Format.percent(v)
    HistoryMetric.POWER -> Format.watts(v).toString()
    HistoryMetric.TEMPERATURE -> Format.temperature(v, s.fahrenheit).toString()
    HistoryMetric.NETWORK -> Format.rate(v, s.bits).toString()
}

private fun buckets(rows: List<HistoryStore.Row>, from: Long, to: Long, m: HistoryMetric, count: Int): List<Bucket> {
    val width = (to - from).toDouble() / count
    val groups = rows.groupBy { ((it.ts - from) / width).toInt().coerceIn(0, count - 1) }
    return (0 until count).map { i ->
        val g = groups[i]
        val values = g?.mapNotNull { valueOf(it, m) }
        Bucket(
            start = from + (i * width).toLong(),
            value = values?.takeIf { it.isNotEmpty() }?.average(),
            app = g?.mapNotNull { it.fgPkg }?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key,
            charging = g?.any { it.charging } == true,
        )
    }
}

@Composable
private fun Chart(rows: List<HistoryStore.Row>, from: Long, to: Long, m: HistoryMetric, settings: Settings) {
    val context = LocalContext.current
    val data = remember(rows, m, from) { buckets(rows, from, to, m, ((to - from) / 60_000).toInt().coerceIn(2, 160)) }
    var selected by remember(rows, m) { mutableStateOf<Int?>(null) }
    val values = data.mapNotNull { it.value }
    val lo = if (m == HistoryMetric.TEMPERATURE) (values.minOrNull() ?: 0.0) - 2 else 0.0
    val hi = when (m) {
        HistoryMetric.LEVEL, HistoryMetric.MEMORY, HistoryMetric.CLOCK -> 1.0
        else -> (values.maxOrNull() ?: 1.0).let { if (it - lo < 1e-9) lo + 1 else it * 1.1 }
    }
    val sel = selected?.let { data.getOrNull(it) }
    val muted = LocalSurfaces.current.muted
    val line = LocalSurfaces.current.line

    // Header: the inspected point, or the range's average and peak.
    if (sel != null) {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sel.start)),
            color = muted, style = MaterialTheme.typography.bodySmall,
        )
        Text(sel.value?.let { format(it, m, settings) } ?: "–", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
            if (sel.app != null) {
                AppIcon(sel.app, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.history_on_screen, appName(context, sel.app, emptyMap())), style = MaterialTheme.typography.bodyMedium)
            } else if (sel.charging) {
                Text(stringResource(R.string.status_charging), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    } else {
        Text(stringResource(R.string.history_average), color = muted, style = MaterialTheme.typography.bodySmall)
        Text(values.takeIf { it.isNotEmpty() }?.average()?.let { format(it, m, settings) } ?: "–", style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.history_peak, values.maxOrNull()?.let { format(it, m, settings) } ?: "–"),
            color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.height(28.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(data) {
                detectTapGestures { o -> selected = (o.x / size.width * data.size).toInt().coerceIn(0, data.lastIndex) }
            }
            .pointerInput(data) {
                detectDragGestures(onDragEnd = {}) { change, _ ->
                    selected = (change.position.x / size.width * data.size).toInt().coerceIn(0, data.lastIndex)
                }
            }
    ) {
        val stepX = size.width / (data.size - 1)
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * size.height).toFloat()
        for (k in 1..3) {
            val gy = size.height * k / 4
            drawLine(line, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        // Charging periods as a soft band, so a flat battery line reads as "plugged in".
        data.forEachIndexed { i, b ->
            if (b.charging) drawRect(Metric.Energy.copy(alpha = 0.08f), Offset(i * stepX, 0f), androidx.compose.ui.geometry.Size(stepX, size.height))
        }
        val path = Path()
        val fill = Path()
        var open = false
        var startX = 0f
        var lastX = 0f
        data.forEachIndexed { i, b ->
            val v = b.value
            val x = i * stepX
            if (v == null) {
                if (open) { fill.lineTo(lastX, size.height); fill.lineTo(startX, size.height); fill.close() }
                open = false
                return@forEachIndexed
            }
            if (!open) {
                path.moveTo(x, y(v)); fill.moveTo(x, size.height); fill.lineTo(x, y(v)); startX = x; open = true
            } else {
                path.lineTo(x, y(v)); fill.lineTo(x, y(v))
            }
            lastX = x
        }
        if (open) { fill.lineTo(lastX, size.height); fill.lineTo(startX, size.height); fill.close() }
        drawPath(fill, Brush.verticalGradient(listOf(m.color.copy(alpha = 0.35f), m.color.copy(alpha = 0.03f))))
        drawPath(path, m.color, style = Stroke(width = 2.dp.toPx()))
        selected?.let { i ->
            val x = i * stepX
            drawLine(muted, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
            data[i].value?.let { drawCircle(m.color, 4.dp.toPx(), Offset(x, y(it))) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val fmt = if (to - from > 2 * DAY) DateFormat.getDateInstance(DateFormat.SHORT) else DateFormat.getTimeInstance(DateFormat.SHORT)
        Text(fmt.format(Date(from)), color = muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.history_now), color = muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}
