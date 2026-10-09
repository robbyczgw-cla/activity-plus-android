package xyz.activityplus.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.activityplus.android.R

/** Metric colors, the same as activityplus.xyz and the Mac app. */
object Metric {
    val Cpu = Color(0xFF3E6BFF)
    val Memory = Color(0xFF8B5CF6)
    val Gpu = Color(0xFFE8508A)
    val Disk = Color(0xFFE09412)
    val Network = Color(0xFF10A7AD)
    val Energy = Color(0xFF26A862)
    val Heat = Color(0xFFE5484D)
    val Warn = Color(0xFFF2A33A)
}

/** Brand gradient of the icon: blue top left to violet bottom right. */
val BrandStart = Color(0xFF3E8BFF)
val BrandEnd = Color(0xFF9B5CF6)

@Immutable
data class Surfaces(
    val background: Color,
    val card: Color,
    val cardRaised: Color,
    val line: Color,
    val muted: Color,
)

val LocalSurfaces = staticCompositionLocalOf {
    Surfaces(Color(0xFF0E1024), Color(0xFF1A1D36), Color(0xFF23274A), Color(0x1FFFFFFF), Color(0xFF9AA0BC))
}

private val DarkSurfaces = Surfaces(
    background = Color(0xFF0E1024),
    card = Color(0xFF171A33),
    cardRaised = Color(0xFF22264A),
    line = Color(0x1FFFFFFF),
    muted = Color(0xFF9AA0BC),
)

private val LightSurfaces = Surfaces(
    background = Color(0xFFEDF0F6),
    card = Color(0xFFFFFFFF),
    cardRaised = Color(0xFFE2E7F1),
    line = Color(0x1F15172B),
    muted = Color(0xFF555B75),
)

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) = Font(
    R.font.bricolage,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Display = FontFamily(bricolage(500), bricolage(600), bricolage(700), bricolage(800))

private fun typography(base: Typography = Typography()) = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
)

/** Big numbers on cards: "30%", "27.7 GB". */
val ValueStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp)

@Composable
fun ActivityPlusTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val surfaces = if (dark) DarkSurfaces else LightSurfaces
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF6E8BFF),
            secondary = Metric.Memory,
            background = surfaces.background,
            surface = surfaces.background,
            surfaceContainer = surfaces.card,
            surfaceContainerHigh = surfaces.cardRaised,
            onBackground = Color(0xFFF2F3FA),
            onSurface = Color(0xFFF2F3FA),
            onSurfaceVariant = surfaces.muted,
            outlineVariant = surfaces.line,
        )
    } else {
        lightColorScheme(
            primary = Metric.Cpu,
            secondary = Metric.Memory,
            background = surfaces.background,
            surface = surfaces.background,
            surfaceContainer = surfaces.card,
            surfaceContainerHigh = surfaces.cardRaised,
            onBackground = Color(0xFF15172B),
            onSurface = Color(0xFF15172B),
            onSurfaceVariant = surfaces.muted,
            outlineVariant = surfaces.line,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSurfaces provides surfaces) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
