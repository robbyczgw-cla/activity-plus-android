package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.FindingText
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLive
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.Metric

@Composable
fun DiagnosisScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()
    val access = rememberUsageAccess()
    var run by remember { mutableIntStateOf(0) }
    // The check waits for the first live sample, then uses today's per-app data.
    val ready = rememberLive().first != null
    val result = rememberLoaded(access, run, ready) {
        val data = loadApps(1, withStorage = true)
        val snapshot = app.monitor.latest.value ?: return@rememberLoaded null
        diagnose(context, snapshot, data)
    }

    Screen(
        title = stringResource(R.string.diag_title),
        subtitle = stringResource(R.string.diag_subtitle),
        action = {
            IconButton(onClick = { run++ }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.diag_again)) }
        },
    ) {
        if (!access) item { UsageAccessCard() }
        when {
            result == null -> item { Note(stringResource(R.string.diag_checking)) }
            result.isEmpty() -> item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Metric.Energy)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.diag_fine_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Note(stringResource(R.string.diag_fine_body))
                }
            }
            else -> items(result) { f ->
                val t = FindingText.of(context, f, settings.fahrenheit)
                Card {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(
                                when (f.severity) {
                                    Diagnosis.Severity.BAD -> Metric.Heat
                                    Diagnosis.Severity.WARN -> Metric.Warn
                                    Diagnosis.Severity.INFO -> Metric.Cpu
                                }
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(t.body, style = MaterialTheme.typography.bodyMedium)
                            if (t.action != null) {
                                FilledTonalButton(onClick = { Actions.open(context, f.fix, f.pkg) }, modifier = Modifier.padding(top = 10.dp)) {
                                    Text(t.action)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Note(stringResource(R.string.diag_note), Modifier.padding(horizontal = 4.dp)) }
    }
}
