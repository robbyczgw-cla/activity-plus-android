package xyz.activityplus.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.ValueStyle

/** Rounded card like the Mac Overview cards. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSurfaces.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(s.card)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content,
    )
}

/** Colored icon + title on the left, a quiet caption on the right ("CPU ... Now"). */
@Composable
fun CardHeader(title: String, color: Color, icon: ImageVector? = null, caption: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        if (caption != null) {
            Text(
                caption,
                color = LocalSurfaces.current.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** "27.7 GB": value large, unit small. */
@Composable
fun BigValue(value: Format.Scaled?, modifier: Modifier = Modifier, placeholder: String = "–") {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value?.value ?: placeholder, style = ValueStyle, color = MaterialTheme.colorScheme.onSurface)
        if (value != null && value.unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                value.unit,
                color = LocalSurfaces.current.muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** Label left, value right; the rows under each card. */
@Composable
fun StatLine(label: String, value: String, valueColor: Color = Color.Unspecified) {
    // The value gets at most 62 % of the row, so a long build string wraps instead of hiding its label.
    // A plain Layout, not BoxWithConstraints: cards measure their height intrinsically, and
    // subcomposed layouts cannot answer that (it crashed the overview).
    val muted = LocalSurfaces.current.muted
    Layout(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        content = {
            Text(label, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        },
    ) { measurables, c ->
        val gap = 8.dp.roundToPx()
        if (!c.hasBoundedWidth) {
            val l = measurables[0].measure(Constraints())
            val v = measurables[1].measure(Constraints())
            val h = maxOf(l.height, v.height)
            return@Layout layout(l.width + gap + v.width, h) { l.place(0, (h - l.height) / 2); v.place(l.width + gap, (h - v.height) / 2) }
        }
        val v = measurables[1].measure(Constraints(maxWidth = (c.maxWidth * 0.62f).toInt()))
        val l = measurables[0].measure(Constraints(maxWidth = (c.maxWidth - v.width - gap).coerceAtLeast(0)))
        val h = maxOf(l.height, v.height)
        layout(c.maxWidth, h) {
            l.place(0, (h - l.height) / 2)
            v.place(c.maxWidth - v.width, (h - v.height) / 2)
        }
    }
}

/** Small pill: "Elevated", "Charging". */
@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Line with a soft fill below, like the Mac sparklines. Null points leave gaps. */
@Composable
fun Sparkline(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    min: Double? = 0.0,
    max: Double? = null,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val points = values
        if (points.count { it != null } < 2) return@Canvas
        val lo = min ?: points.filterNotNull().min()
        val hi = (max ?: points.filterNotNull().max()).let { if (it - lo < 1e-9) lo + 1 else it }
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * size.height).toFloat()

        val line = Path()
        val fill = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        points.forEachIndexed { i, v ->
            val x = i * stepX
            if (v == null) {
                if (started) {
                    fill.lineTo(lastX, size.height); fill.lineTo(firstX, size.height); fill.close()
                }
                started = false
                return@forEachIndexed
            }
            if (!started) {
                line.moveTo(x, y(v)); fill.moveTo(x, size.height); fill.lineTo(x, y(v))
                firstX = x; started = true
            } else {
                line.lineTo(x, y(v)); fill.lineTo(x, y(v))
            }
            lastX = x
        }
        if (started) {
            fill.lineTo(lastX, size.height); fill.lineTo(firstX, size.height); fill.close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.04f))))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Horizontal bar, used per core and for storage. */
@Composable
fun Meter(fraction: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val track = LocalSurfaces.current.cardRaised
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** Vertical bars side by side, one per CPU core (the Mac "bar per core" style). */
@Composable
fun CoreBars(fractions: List<Double?>, color: Color, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val track = LocalSurfaces.current.cardRaised
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (fractions.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (fractions.size - 1)) / fractions.size
        fractions.forEachIndexed { i, f ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            if (f != null) {
                val h = (f.coerceIn(0.0, 1.0) * size.height).toFloat()
                drawRoundRect(color, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalSurfaces.current.muted,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** Shown where data needs a permission or the phone does not report it. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalSurfaces.current.muted, modifier = modifier)
}

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

val CardSpacing = Arrangement.spacedBy(12.dp)

/** One bar split into colored parts, with a legend below; used for the RAM breakdown. */
@Composable
fun SegmentBar(parts: List<Triple<String, Long, Color>>, total: Long, modifier: Modifier = Modifier) {
    if (total <= 0) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(LocalSurfaces.current.cardRaised)) {
            parts.forEach { (_, bytes, color) ->
                val f = (bytes.toDouble() / total).toFloat()
                if (f > 0.002f) Box(Modifier.weight(f).height(12.dp).background(color))
            }
            val rest = 1f - parts.sumOf { it.second }.toFloat() / total
            if (rest > 0.002f) Spacer(Modifier.weight(rest))
        }
        Spacer(Modifier.height(8.dp))
        parts.forEach { (label, bytes, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f))
                Text(Format.bytes(bytes).toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
