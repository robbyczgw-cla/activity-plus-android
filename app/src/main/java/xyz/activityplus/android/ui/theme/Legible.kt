package xyz.activityplus.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import xyz.activityplus.android.core.Contrast

/**
 * The metric colours are one palette for both themes (like the Mac app), which is too light for text
 * on the white card and a little dark for text on the dark one. These keep the hue and only move
 * the shade as far as WCAG AA needs, and only for colours that fall short, so the palette looks the
 * same wherever it already passes.
 */
@Composable
private fun Color.adjusted(min: Double): Color {
    val s = LocalSurfaces.current
    // Translucent colours are tints on purpose (muted legend parts); there is no single colour to test.
    return remember(this, s) {
        if (!isSpecified || alpha < 1f) this
        else Color(Contrast.ensure(toArgb(), min, s.card.toArgb(), s.cardRaised.toArgb()))
    }
}

/** For text on a card: 4.5:1. */
@Composable
fun Color.forText(): Color = adjusted(Contrast.TEXT)

/** For lines, bars and icons that carry information: 3:1. */
@Composable
fun Color.forGraphic(): Color = adjusted(Contrast.GRAPHIC)
