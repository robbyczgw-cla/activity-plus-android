package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.AppUsage
import xyz.activityplus.android.core.DrainTracker
import xyz.activityplus.android.core.SYSTEM_ROW
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.theme.LocalSurfaces

/** Page with a big title and a lazy list of cards. */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted)
                    }
                }
                action?.invoke()
            }
        }
        content()
    }
}

@Composable
fun UsageAccessCard() {
    val context = LocalContext.current
    Card {
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.padding(top = 4.dp))
        Note(stringResource(R.string.access_body))
        Button(
            onClick = { Actions.usageAccess(context) },
            modifier = Modifier.padding(top = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text(stringResource(R.string.access_button)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = LocalSurfaces.current.cardRaised,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/** Display name for a package, including the synthetic rows. */
fun appName(context: Context, pkg: String, labels: Map<String, String>): String = when (pkg) {
    DrainTracker.SCREEN_OFF -> context.getString(R.string.row_screen_off)
    DrainTracker.SCREEN_ON_OTHER -> context.getString(R.string.row_screen_on_other)
    SYSTEM_ROW -> context.getString(R.string.row_system)
    else -> labels[pkg] ?: runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Per-app usage plus measured energy for a range of days ending today. */
data class AppsData(
    val apps: List<AppUsage>,
    /** pkg to (mWh, seconds on screen while measured). */
    val energy: Map<String, Pair<Double, Double>>,
    /** Average daily energy of the seven days before the range, for "unusual" badges. */
    val usualEnergy: Map<String, Double>,
) {
    val labels: Map<String, String> = apps.associate { it.pkg to it.label }
}

fun loadApps(days: Int, withStorage: Boolean): AppsData {
    val now = System.currentTimeMillis()
    val from = ActivityPlusApp.startOfDay(now) - (days - 1) * DAY
    val apps = app.usage.read(from, now, withStorage)
    val energy = app.history.energy(ActivityPlusApp.dayKey(from), ActivityPlusApp.dayKey(now))
        .associate { it.pkg to (it.mwh to it.seconds) }
    val usual = if (days == 1) {
        val today = ActivityPlusApp.dayKey(now)
        app.history.energyByDay(ActivityPlusApp.dayKey(from - 7 * DAY)).mapValues { (_, byDay) ->
            byDay.filterKeys { it != today }.values.sum() / 7
        }
    } else emptyMap()
    return AppsData(apps, energy, usual)
}

const val DAY = 86_400_000L
