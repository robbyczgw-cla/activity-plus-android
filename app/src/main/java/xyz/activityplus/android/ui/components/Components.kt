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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ChartText
import xyz.activityplus.android.core.Contrast
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.ValueStyle
import xyz.activityplus.android.ui.theme.forGraphic
import xyz.activityplus.android.ui.theme.forText

/**
 * Rounded card like the Mac Overview cards. A card with [onClick] reads as one button to a screen
 * reader (its texts merged), and [onClickLabel] says where it leads.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalSurfaces.current
    val label = onClickLabel ?: stringResource(R.string.a11y_open_details)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(s.card)
            .let { if (onClick != null) it.clickable(onClickLabel = label, role = Role.Button, onClick = onClick) else it }
            .padding(16.dp),
        content = content,
    )
}

/**
 * Colored icon + title on the left, a quiet caption on the right ("CPU ... Now"). A heading for
 * screen readers, with the caption read as part of it.
 */
@Composable
fun CardHeader(title: String, color: Color, icon: ImageVector? = null, caption: String? = null) {
    val textColor = color.forText()
    val iconColor = color.forGraphic()
    // Cut-off captions are the design at normal size; with large text the caption would shrink to
    // nothing, so it moves under the title instead.
    Layout(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title, color = textColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (caption != null) {
                Text(
                    caption,
                    color = LocalSurfaces.current.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        },
    ) { measurables, c ->
        val gap = 8.dp.roundToPx()
        val titleBox = measurables[0]
        val captionBox = measurables.getOrNull(1)
        if (!c.hasBoundedWidth) {
            // Intrinsic probes of the cards' rows. The title is a Row with a weighted text, which needs a bounded width.
            val t = titleBox.measure(Constraints(maxWidth = titleBox.maxIntrinsicWidth(Constraints.Infinity)))
            val cp = captionBox?.measure(Constraints())
            val h = maxOf(t.height, cp?.height ?: 0)
            return@Layout layout(t.width + (cp?.let { gap + it.width } ?: 0), h) {
                t.place(0, (h - t.height) / 2)
                cp?.place(t.width + gap, (h - cp.height) / 2)
            }
        }
        if (captionBox == null) {
            val t = titleBox.measure(Constraints(maxWidth = c.maxWidth))
            return@Layout layout(c.maxWidth, t.height) { t.place(0, 0) }
        }
        val wanted = titleBox.maxIntrinsicWidth(Constraints.Infinity) + gap + captionBox.maxIntrinsicWidth(Constraints.Infinity)
        if (wanted > c.maxWidth && fontScale >= LARGE_TEXT) {
            val t = titleBox.measure(Constraints(maxWidth = c.maxWidth))
            val cp = captionBox.measure(Constraints(maxWidth = c.maxWidth))
            layout(c.maxWidth, t.height + cp.height) {
                t.place(0, 0)
                cp.place(c.maxWidth - cp.width, t.height)
            }
        } else {
            // The title comes first, the caption gets what is left (and is cut when that is little).
            val t = titleBox.measure(Constraints(maxWidth = c.maxWidth))
            val cp = captionBox.measure(Constraints(maxWidth = (c.maxWidth - gap - t.width).coerceAtLeast(0)))
            val h = maxOf(t.height, cp.height)
            layout(c.maxWidth, h) {
                t.place(0, (h - t.height) / 2)
                cp.place(c.maxWidth - cp.width, (h - cp.height) / 2)
            }
        }
    }
}

/**
 * "27.7 GB": value large, unit small. Read as one phrase, and the size follows the system font
 * scale only up to 1.3 (unit 1.6): 30 sp is large text already, and at 2.0 it would not fit the
 * half-width cards.
 */
@Composable
fun BigValue(value: Format.Scaled?, modifier: Modifier = Modifier, placeholder: String = "–") {
    // One phrase, and a spoken "no value" where the screen shows the dash.
    val spoken = value?.toString() ?: stringResource(R.string.a11y_no_value)
    Row(modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.Bottom) {
        CappedFontScale(LARGE_TEXT) {
            Text(value?.value ?: placeholder, style = ValueStyle, color = MaterialTheme.colorScheme.onSurface)
        }
        if (value != null && value.unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            CappedFontScale(1.6f) {
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
}

/**
 * Label left, value right; the rows under each card. Read as "Voltage, 3.96 V" in one go. With large
 * text, a row whose label and value no longer fit side by side puts the value under the label.
 */
@Composable
fun StatLine(label: String, value: String, valueColor: Color = Color.Unspecified) {
    // The value gets at most 62 % of the row, so a long build string wraps instead of hiding its label.
    // A plain Layout, not BoxWithConstraints: cards measure their height intrinsically, and
    // subcomposed layouts cannot answer that (it crashed the overview).
    val muted = LocalSurfaces.current.muted
    val large = LocalDensity.current.fontScale >= LARGE_TEXT
    val spoken = stringResource(R.string.a11y_label_value, label, value)
    Layout(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clearAndSetSemantics { contentDescription = spoken },
        content = {
            Text(label, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = if (large) 4 else 2, overflow = TextOverflow.Ellipsis)
            Text(
                value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor.forText(),
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = if (large) 6 else 3, overflow = TextOverflow.Ellipsis,
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
        if (fontScale >= LARGE_TEXT &&
            measurables[0].maxIntrinsicWidth(Constraints.Infinity) + gap + measurables[1].maxIntrinsicWidth(Constraints.Infinity) > c.maxWidth
        ) {
            // Stacked: the value is read right after its label and uses the full width.
            val l = measurables[0].measure(Constraints(maxWidth = c.maxWidth))
            val v = measurables[1].measure(Constraints(maxWidth = c.maxWidth))
            return@Layout layout(c.maxWidth, l.height + v.height) {
                l.place(0, 0)
                v.place(c.maxWidth - v.width, l.height)
            }
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
    // The text sits on its own 16 % tint of the colour, which is lighter than the card in the dark
    // theme and darker in the light one; shade the text until it reads on that, not just on the card.
    val s = LocalSurfaces.current
    val textColor = remember(color, s) {
        val c = color.toArgb()
        Color(Contrast.ensure(c, Contrast.TEXT, Contrast.over(c, s.card.toArgb(), 0.16f), Contrast.over(c, s.cardRaised.toArgb(), 0.16f)))
    }
    Text(
        text,
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/**
 * Line with a soft fill below, like the Mac sparklines. Null points leave gaps.
 * A screen reader gets [label] with the range and the last value ([format] writes the numbers);
 * [latest] is false where the last point is not "now" (a typical day, one point per day).
 */
@Composable
fun Sparkline(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    min: Double? = 0.0,
    max: Double? = null,
    label: String = stringResource(R.string.a11y_chart_generic),
    format: (Double) -> String = { Format.number(it, 1) },
    latest: Boolean = true,
) {
    val words = rememberChartWords()
    val spoken = ChartText.describe(label, values, format, words, latest)
    val stroke = color.forGraphic()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = spoken }) {
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
        drawPath(fill, Brush.verticalGradient(listOf(stroke.copy(alpha = 0.38f), stroke.copy(alpha = 0.04f))))
        drawPath(line, stroke, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Horizontal bar, used per core and for storage. With a [label] it is a progress value for a screen
 * reader ("Storage used, 68 %"); without one it only repeats a number printed next to it (the thin
 * bars in the app lists) and is skipped.
 */
@Composable
fun Meter(fraction: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, label: String? = null) {
    val track = LocalSurfaces.current.cardRaised
    val f = fraction.toFloat().coerceIn(0f, 1f)
    val spoken = Format.percent(f.toDouble())
    val semantics = if (label == null) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics {
            contentDescription = label
            stateDescription = spoken
            progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
        }
    }
    Box(modifier.fillMaxWidth().height(height).then(semantics).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color.forGraphic())
        )
    }
}

/** Vertical bars side by side, one per CPU core (the Mac "bar per core" style). [label] is what they show, for a screen reader. */
@Composable
fun CoreBars(
    fractions: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    label: String = stringResource(R.string.a11y_cpu_cores),
) {
    val track = LocalSurfaces.current.cardRaised
    val bar = color.forGraphic()
    val spoken = ChartText.describeBars(label, fractions, { Format.percent(it) }, rememberChartWords())
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = spoken }) {
        if (fractions.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (fractions.size - 1)) / fractions.size
        fractions.forEachIndexed { i, f ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            if (f != null) {
                val h = (f.coerceIn(0.0, 1.0) * size.height).toFloat()
                drawRoundRect(bar, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h),
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
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
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

/** One bar split into colored parts, with a legend below; used for the RAM breakdown. [valueText] replaces the byte sizes in the legend. */
@Composable
fun SegmentBar(
    parts: List<Triple<String, Long, Color>>,
    total: Long,
    modifier: Modifier = Modifier,
    valueText: (Long) -> String = { Format.bytes(it).toString() },
) {
    if (total <= 0) return
    Column(modifier) {
        // The legend below says everything the colours do, so the bar itself is skipped by screen readers.
        Row(Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics {}.clip(RoundedCornerShape(50)).background(LocalSurfaces.current.cardRaised)) {
            parts.forEach { (_, bytes, color) ->
                val f = (bytes.toDouble() / total).toFloat()
                if (f > 0.002f) Box(Modifier.weight(f).height(12.dp).background(color.forGraphic()))
            }
            val rest = 1f - parts.sumOf { it.second }.toFloat() / total
            if (rest > 0.002f) Spacer(Modifier.weight(rest))
        }
        Spacer(Modifier.height(8.dp))
        parts.forEach { (label, bytes, color) ->
            val value = valueText(bytes)
            val spoken = stringResource(R.string.a11y_label_value, label, value)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color.forGraphic()))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
