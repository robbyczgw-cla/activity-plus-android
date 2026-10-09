package xyz.activityplus.android.ui.screens

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Interruptions
import xyz.activityplus.android.data.ProcessExits
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.theme.Metric

/** What the card says, or null when it has nothing to say. Runs on IO. */
data class Interruption(val count: Int, val maker: Interruptions.Maker, val exempt: Boolean)

fun loadInterruption(context: Context): Interruption? {
    val now = System.currentTimeMillis()
    if (now - app.prefs.interruptionDismissed() < Interruptions.WINDOW_MILLIS) return null
    val count = Interruptions.count(ProcessExits.read(context), now) { app.prefs.wasLive(it) }
    if (count.count < Interruptions.MIN_COUNT) return null
    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    return Interruption(count.count, Interruptions.maker(Build.MANUFACTURER), exempt)
}

@Composable
fun InterruptionCard(i: Interruption, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, tint = Metric.Warn)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.interruption_title, i.count),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.interruption_body))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(makerLine(i.maker)), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { Actions.appInfo(context, context.packageName) }) {
                Text(stringResource(R.string.interruption_app_info))
            }
            if (!i.exempt) {
                FilledTonalButton(onClick = { Actions.ignoreBatteryOptimizations(context) }) {
                    Text(stringResource(R.string.interruption_battery))
                }
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.interruption_dismiss)) }
    }
}

private fun makerLine(m: Interruptions.Maker): Int = when (m) {
    Interruptions.Maker.VIVO -> R.string.interruption_vivo
    Interruptions.Maker.XIAOMI -> R.string.interruption_xiaomi
    Interruptions.Maker.SAMSUNG -> R.string.interruption_samsung
    Interruptions.Maker.ONEPLUS, Interruptions.Maker.OPPO, Interruptions.Maker.REALME -> R.string.interruption_oppo
    Interruptions.Maker.HUAWEI, Interruptions.Maker.HONOR, Interruptions.Maker.OTHER -> R.string.interruption_generic
}
