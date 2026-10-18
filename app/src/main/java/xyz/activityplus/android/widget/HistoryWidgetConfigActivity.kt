package xyz.activityplus.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.screens.Screen
import xyz.activityplus.android.ui.theme.ActivityPlusTheme

/** Picks what the history widget draws: the battery level or the power. Optional; the level is the default. */
class HistoryWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out without saving removes a freshly placed widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }
        val initial = HistoryWidgetPrefs.mode(this, id)
        setContent {
            ActivityPlusTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var mode by remember { mutableStateOf(initial) }
                    Screen(
                        title = stringResource(R.string.widget_history_name),
                        subtitle = stringResource(R.string.wh_config_subtitle),
                    ) {
                        item {
                            Card {
                                HistoryMode.entries.forEach { m ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { mode = m },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = mode == m, onClick = { mode = m })
                                        Text(stringResource(if (m == HistoryMode.LEVEL) R.string.wh_mode_level else R.string.wh_mode_power))
                                    }
                                }
                            }
                        }
                        item {
                            Note(stringResource(R.string.widget_config_hint))
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { save(id, mode) },
                                modifier = Modifier.fillMaxWidth().height(52.dp).navigationBarsPadding().padding(bottom = 8.dp),
                            ) { Text(stringResource(R.string.widget_config_done)) }
                        }
                    }
                }
            }
        }
    }

    private fun save(id: Int, mode: HistoryMode) {
        HistoryWidgetPrefs.setMode(this, id, mode)
        SlowWidgets.redrawHistory(this, id)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
