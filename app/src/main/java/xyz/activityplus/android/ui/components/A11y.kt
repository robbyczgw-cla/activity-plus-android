package xyz.activityplus.android.ui.components

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ChartText

/** Smallest touch target Android asks for. */
val MinTouch = 48.dp

/** From this font scale on, rows give up the side-by-side layout when it no longer fits. */
const val LARGE_TEXT = 1.3f

/**
 * A row that opens or toggles something: at least [MinTouch] tall (the app rows with a 30 dp icon
 * and a little padding were 40 dp), announced as a button, and with [onClickLabel] saying what a
 * double tap does ("Open app info") instead of the bare "double tap to activate".
 * Put it before the row's padding so the padding stays inside the target; the content is centred in
 * the target when it is shorter.
 */
fun Modifier.tapRow(onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    heightIn(min = MinTouch)
        .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        .wrapContentHeight(Alignment.CenterVertically)

/**
 * Limits font scaling for [content] to [max]. For text that is already large (the big numbers on
 * the cards) or sits in a fixed-width slot (bottom navigation labels), where 2.0 times would push
 * it out of its box; everything else follows the system setting fully.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max)) { content() }
    }
}

/** A bottom navigation label: one line, scaled up to [LARGE_TEXT] so five of them still fit. */
@Composable
fun NavLabel(text: String) = CappedFontScale(LARGE_TEXT) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The sentences of [ChartText], from string resources. Also used by the widgets, which have no Compose. */
class ChartWords(private val res: Resources) : ChartText.Words {
    override fun empty(subject: String) = res.getString(R.string.a11y_chart_empty, subject)
    override fun flat(subject: String, value: String) = res.getString(R.string.a11y_chart_flat, subject, value)
    override fun range(subject: String, low: String, high: String, now: String?) =
        if (now != null) res.getString(R.string.a11y_chart_range, subject, low, high, now)
        else res.getString(R.string.a11y_chart_span, subject, low, high)

    override fun bars(subject: String, low: String, high: String) = res.getString(R.string.a11y_chart_bars, subject, low, high)
}

@Composable
fun rememberChartWords(): ChartText.Words {
    val res = LocalContext.current.resources
    return remember(res) { ChartWords(res) }
}
