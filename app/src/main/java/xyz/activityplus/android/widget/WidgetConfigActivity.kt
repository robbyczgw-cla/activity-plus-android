package xyz.activityplus.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.R
import xyz.activityplus.android.data.StatusItem
import xyz.activityplus.android.ui.StatusText
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.screens.Screen
import xyz.activityplus.android.ui.theme.ActivityPlusTheme

/** Picks the values of one widget: a single value, or up to six for the panel. */
class WidgetConfigActivity : ComponentActivity() {
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
        val awm = AppWidgetManager.getInstance(this)
        val single = awm.getAppWidgetInfo(id)?.provider?.className == ValueWidget::class.java.name
        val max = if (single) 1 else 6
        val initial = WidgetPrefs.items(this, id, if (single) WidgetPrefs.defaultValue else WidgetPrefs.defaultPanel)

        setContent {
            ActivityPlusTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var chosen by remember { mutableStateOf(initial) }
                    Screen(
                        title = stringResource(R.string.widget_config_title),
                        subtitle = stringResource(if (single) R.string.widget_config_single else R.string.widget_config_panel),
                    ) {
                        item {
                            Card {
                                StatusItem.entries.forEach { item ->
                                    val on = item in chosen
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        if (single) {
                                            RadioButton(selected = on, onClick = { chosen = listOf(item) })
                                        } else {
                                            Checkbox(
                                                checked = on,
                                                enabled = on || chosen.size < max,
                                                onCheckedChange = { c -> chosen = if (c) chosen + item else chosen - item },
                                            )
                                        }
                                        Text(
                                            StatusText.label(this@WidgetConfigActivity, item),
                                            color = Color(StatusText.metricColor(item)),
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Note(stringResource(R.string.widget_config_hint))
                            Spacer(Modifier.height(12.dp))
                            Button(
                                enabled = chosen.isNotEmpty(),
                                onClick = { save(id, chosen) },
                                modifier = Modifier.fillMaxWidth().height(52.dp).navigationBarsPadding().padding(bottom = 8.dp),
                            ) { Text(stringResource(R.string.widget_config_done)) }
                        }
                    }
                }
            }
        }
    }

    private fun save(id: Int, items: List<StatusItem>) {
        WidgetPrefs.setItems(this, id, items)
        val latest = ActivityPlusApp.instance.monitor.latest.value
        if (latest != null) WidgetUpdater.updateAll(this, latest) else WidgetUpdater.refreshFromScratch(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
