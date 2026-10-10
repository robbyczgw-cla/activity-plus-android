package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.SpeedTest
import xyz.activityplus.android.core.StorageSpeedTest
import xyz.activityplus.android.data.SpeedTestStore
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Storage speed" on the Storage page: a test with progress and cancel, and the last runs under the button. */
@Composable
fun SpeedTestCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress = remember { MutableStateFlow<StorageSpeedTest.Progress?>(null) }
    val current by progress.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableIntStateOf(0) }
    val runs = rememberLoaded(saved) { SpeedTestStore.load(context) }.orEmpty()

    fun start() {
        message = null
        job = scope.launch {
            try {
                when (val outcome = StorageSpeedTest.run(context.cacheDir) { progress.value = it }) {
                    is StorageSpeedTest.Outcome.Done -> {
                        SpeedTestStore.add(context, outcome.run)
                        saved++
                    }
                    StorageSpeedTest.Outcome.NoRoom -> message = R.string.speed_no_room
                    StorageSpeedTest.Outcome.Failed -> message = R.string.speed_failed
                }
            } finally {
                progress.value = null
                job = null
            }
        }
    }

    Card {
        CardHeader(stringResource(R.string.speed_title), Metric.Disk, Icons.Outlined.Speed)
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.speed_note))
        Spacer(Modifier.height(10.dp))
        if (job != null) {
            LinearProgressIndicator(
                progress = { current?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Metric.Disk,
            )
            Spacer(Modifier.height(4.dp))
            Note(stringResource(
                when (current?.phase) {
                    StorageSpeedTest.Phase.READ -> R.string.speed_phase_read
                    StorageSpeedTest.Phase.RANDOM -> R.string.speed_phase_random
                    else -> R.string.speed_phase_write
                },
            ))
            OutlinedButton(
                onClick = {
                    message = R.string.speed_canceled
                    job?.cancel()
                },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text(stringResource(R.string.speed_cancel)) }
        } else {
            Button(onClick = ::start, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(stringResource(R.string.speed_test))
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Note(stringResource(it))
        }
        runs.firstOrNull()?.let { latest ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    when (SpeedTest.classify(latest.writeMbps)) {
                        SpeedTest.Verdict.SLOW -> R.string.speed_verdict_slow
                        SpeedTest.Verdict.TYPICAL -> R.string.speed_verdict_typical
                        SpeedTest.Verdict.FAST -> R.string.speed_verdict_fast
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        runs.forEach { RunRows(it) }
    }
}

@Composable
private fun RunRows(run: SpeedTest.Run) {
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(run.time))
    Spacer(Modifier.height(12.dp))
    Text(time, style = MaterialTheme.typography.bodySmall, color = LocalSurfaces.current.muted)
    StatLine(stringResource(R.string.speed_write), stringResource(R.string.speed_rate, mbps(run.writeMbps)))
    StatLine(stringResource(R.string.speed_read), stringResource(R.string.speed_rate, mbps(run.readMbps)))
    StatLine(
        stringResource(R.string.speed_random),
        stringResource(R.string.speed_random_value, mbps(run.randomMbps), Format.number(run.randomIops, 0)),
    )
}

private fun mbps(value: Double) = Format.number(value, if (value < 10) 1 else 0)
