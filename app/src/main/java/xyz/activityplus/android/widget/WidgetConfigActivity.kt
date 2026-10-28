package xyz.activityplus.android.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.data.StatusItem
import xyz.activityplus.android.ui.StatusText
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.MinTouch
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.screens.Screen
import xyz.activityplus.android.ui.theme.ActivityPlusTheme
import xyz.activityplus.android.ui.theme.forText

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
                                    // The whole row is the control (label and box read as one), at least 48 dp tall.
                                    val row = Modifier.fillMaxWidth().heightIn(min = MinTouch)
                                    Row(
                                        if (single) row.selectable(selected = on, role = Role.RadioButton) { chosen = listOf(item) }
                                        else row.toggleable(
                                            value = on, enabled = on || chosen.size < max, role = Role.Checkbox,
                                        ) { c -> chosen = if (c) chosen + item else chosen - item },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (single) {
                                            RadioButton(selected = on, onClick = null)
                                        } else {
                                            Checkbox(checked = on, onCheckedChange = null, enabled = on || chosen.size < max)
                                        }
                                        Text(
                                            StatusText.label(this@WidgetConfigActivity, item),
                                            color = Color(StatusText.metricColor(item)).forText(),
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
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).navigationBarsPadding().padding(bottom = 8.dp),
                            ) { Text(stringResource(R.string.widget_config_done)) }
                        }
                    }
                }
            }
        }
    }

    private fun save(id: Int, items: List<StatusItem>) {
        WidgetPrefs.setItems(this, id, items)
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch { WidgetUpdater.refreshFromScratch(context) }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
