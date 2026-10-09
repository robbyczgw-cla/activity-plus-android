package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.BuildConfig
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.data.ColorMode
import xyz.activityplus.android.data.MAX_STATUS_ITEMS
import xyz.activityplus.android.data.StatusItem
import xyz.activityplus.android.service.MonitorService
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.StatusText
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.SectionTitle
import xyz.activityplus.android.ui.rememberLive
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberSettings

@Composable
fun SettingsScreen(onBack: () -> Unit, onWeekly: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberSettings()
    val (s, _) = rememberLive()
    var confirmClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(0) }
    val historySize = rememberLoaded(cleared) { app.history.sizeBytes(context) }

    Screen(
        title = stringResource(R.string.settings),
        action = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
    ) {
        item { SectionTitle(stringResource(R.string.set_statusbar)) }
        item {
            Card {
                Toggle(stringResource(R.string.set_live), stringResource(R.string.set_live_hint), settings.live) { on ->
                    app.prefs.update { it.copy(live = on) }
                    if (on) MonitorService.start(context) else MonitorService.stop(context)
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.set_icon_item), style = MaterialTheme.typography.titleSmall)
                Note(stringResource(R.string.set_icon_item_hint))
                Chips(StatusItem.entries, settings.iconItem, { StatusText.label(context, it) }) { item ->
                    app.prefs.update { it.copy(iconItem = item) }
                }
            }
        }
        item {
            Card {
                Text(stringResource(R.string.set_items), style = MaterialTheme.typography.titleSmall)
                Note(stringResource(R.string.set_items_hint))
                Spacer(Modifier.height(6.dp))
                // Chosen items first in their order, then the rest.
                val ordered = settings.items + StatusItem.entries.filter { it !in settings.items }
                ordered.forEach { item ->
                    val index = settings.items.indexOf(item)
                    val on = index >= 0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = on,
                            enabled = on || settings.items.size < MAX_STATUS_ITEMS,
                            onCheckedChange = { checked ->
                                app.prefs.update { st -> st.copy(items = if (checked) st.items + item else st.items - item) }
                            },
                        )
                        Text(StatusText.label(context, item), color = Color(StatusText.metricColor(item)), modifier = Modifier.weight(1f))
                        Text(
                            s?.let { StatusText.value(item, it, settings)?.toString() } ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (on) {
                            IconButton(enabled = index > 0, onClick = { app.prefs.update { st -> st.copy(items = st.items.swap(index, index - 1)) } }) {
                                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up))
                            }
                            IconButton(enabled = index < settings.items.lastIndex, onClick = { app.prefs.update { st -> st.copy(items = st.items.swap(index, index + 1)) } }) {
                                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down))
                            }
                        }
                    }
                }
                Toggle(stringResource(R.string.set_app_line), stringResource(R.string.set_app_line_hint), settings.appLine) { on ->
                    app.prefs.update { it.copy(appLine = on) }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.set_colors), style = MaterialTheme.typography.titleSmall)
                Chips(ColorMode.entries, settings.colorMode, {
                    stringResource(
                        when (it) {
                            ColorMode.METRIC -> R.string.color_metric
                            ColorMode.LOAD -> R.string.color_load
                            ColorMode.MONO -> R.string.color_mono
                        }
                    )
                }) { mode -> app.prefs.update { it.copy(colorMode = mode) } }
                Spacer(Modifier.height(6.dp))
                Note(stringResource(R.string.set_tile_hint))
            }
        }

        item { SectionTitle(stringResource(R.string.set_general)) }
        item {
            Card {
                Text(stringResource(R.string.set_interval), style = MaterialTheme.typography.titleSmall)
                Chips(listOf(1, 2, 5, 10), settings.intervalSeconds, { "$it s" }) { sec -> app.prefs.update { it.copy(intervalSeconds = sec) } }
                Note(stringResource(R.string.set_interval_hint))
                Spacer(Modifier.height(8.dp))
                LanguageRow()
                Toggle(stringResource(R.string.set_fahrenheit), null, settings.fahrenheit) { on -> app.prefs.update { it.copy(fahrenheit = on) } }
                Toggle(stringResource(R.string.set_bits), null, settings.bits) { on -> app.prefs.update { it.copy(bits = on) } }
                Toggle(stringResource(R.string.set_alerts), stringResource(R.string.set_alerts_hint), settings.alerts) { on ->
                    app.prefs.update { it.copy(alerts = on) }
                }
                Toggle(stringResource(R.string.set_weekly), stringResource(R.string.set_weekly_hint), settings.weekly) { on ->
                    app.prefs.update { it.copy(weekly = on) }
                }
                TextButton(onClick = onWeekly) { Text(stringResource(R.string.set_weekly_open)) }
            }
        }

        item { SectionTitle(stringResource(R.string.chg_section)) }
        item {
            Card {
                Text(stringResource(R.string.chg_set_limit), style = MaterialTheme.typography.titleSmall)
                Note(stringResource(R.string.chg_set_limit_hint))
                Chips(listOf(0, 80, 85, 90, 95), settings.chargeLimit, { if (it == 0) stringResource(R.string.chg_off) else "$it %" }) { limit ->
                    app.prefs.update { it.copy(chargeLimit = limit) }
                }
                Spacer(Modifier.height(6.dp))
                Toggle(stringResource(R.string.chg_set_full), stringResource(R.string.chg_set_full_hint), settings.chargeFullAlarm) { on ->
                    app.prefs.update { it.copy(chargeFullAlarm = on) }
                }
                Toggle(stringResource(R.string.chg_set_warm), stringResource(R.string.chg_set_warm_hint), settings.chargeWarmAlarm) { on ->
                    app.prefs.update { it.copy(chargeWarmAlarm = on) }
                }
                Note(stringResource(R.string.chg_set_note))
            }
        }

        item { SectionTitle(stringResource(R.string.set_data)) }
        item {
            Card {
                Text(stringResource(R.string.set_history_size, historySize?.let { Format.bytes(it).toString() } ?: "–"))
                Note(stringResource(R.string.set_history_hint))
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.set_clear_history)) }
            }
        }
        item {
            Card {
                Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.privacy_body))
            }
        }
        item {
            Card(onClick = { Actions.website(context) }) {
                Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.about_body))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.set_clear_history)) },
            text = { Text(stringResource(R.string.set_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { app.history.clear(); cleared++; confirmClear = false }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Toggle(title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Note(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    val m = toMutableList()
    val t = m[a]; m[a] = m[b]; m[b] = t
    return m
}
