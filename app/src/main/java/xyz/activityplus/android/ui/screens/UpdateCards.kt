package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.SinceUpdate
import xyz.activityplus.android.data.UpdateLoader
import xyz.activityplus.android.service.UpdateRecorder
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.UpdateText
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

/*
 * "Since the update" (core/SinceUpdate): the Apps page lists recent updates with a clear change, the
 * app detail names the last update of that app, and the diagnosis flags a strong increase. Everything
 * is read from what the other cards already keep; no cause is claimed and nothing is done on its own.
 */

/** One update with the name to show. */
class ShownUpdate(val result: SinceUpdate.Result, val label: String) {
    val pkg get() = result.update.pkg
}

/** The recent updates, newest first. Reads the stores: off the main thread. */
fun loadUpdates(context: Context): List<ShownUpdate> {
    // Cheap and throttled, so the list is also right while the service is off.
    UpdateRecorder.scanIfStale(context)
    return UpdateLoader.load(context).map { ShownUpdate(it, appName(context, it.update.pkg, emptyMap())) }
}

/** For the diagnosis: the updates followed by a strong increase, with the name to show. */
fun loadSinceUpdate(context: Context): List<Pair<SinceUpdate.Result, String>> =
    loadUpdates(context).filter { it.result.strongest != null }.map { it.result to it.label }

class AfterUpdates(val changed: List<ShownUpdate>, val unchanged: List<ShownUpdate>, val waiting: Boolean)

/** Null while loading. With nothing to judge yet the card still comes, and says why. */
@Composable
fun rememberAfterUpdates(): AfterUpdates? {
    val context = LocalContext.current
    val all = rememberLoaded { loadUpdates(context) } ?: return null
    val changed = all.filter { it.result.clear }
    val unchanged = all.filter { it.result.enough && !it.result.clear }
    return AfterUpdates(changed, unchanged, all.any { !it.result.enough })
}

@Composable
fun AfterUpdatesCard(u: AfterUpdates, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card {
        CardHeader(stringResource(R.string.su_title), Metric.Warn, Icons.Outlined.SystemUpdate, stringResource(R.string.su_caption))
        Spacer(Modifier.height(8.dp))
        // Nothing updated lately; with only very recent updates the waiting line below says it all.
        if (u.changed.isEmpty() && u.unchanged.isEmpty() && !u.waiting) {
            Note(stringResource(R.string.es_updates_none, SinceUpdate.RECENT_DAYS))
        }
        u.changed.forEach { UpdateRow(it, showChanges = true) { onOpenApp(it.pkg) } }
        if (u.unchanged.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                val n = u.unchanged.size
                Text(context.resources.getQuantityString(R.plurals.su_none, n, n) + if (expanded) " ‹" else " ›")
            }
            if (expanded) u.unchanged.forEach { UpdateRow(it, showChanges = false) { onOpenApp(it.pkg) } }
        }
        if (u.waiting) Note(stringResource(R.string.su_waiting))
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.su_note))
    }
}

@Composable
private fun UpdateRow(u: ShownUpdate, showChanges: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        AppIcon(u.pkg, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Note(UpdateText.updated(context, u.result.update.timeMillis, u.result.update.newName))
            if (showChanges) u.result.changes.forEach { ChangeLine(it) }
        }
    }
}

/** One change, worse in the warning colour and better in green. */
@Composable
private fun ChangeLine(c: SinceUpdate.Change) {
    val context = LocalContext.current
    Text(
        "· " + UpdateText.effect(context, c).replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.bodyMedium,
        color = if (c.direction == SinceUpdate.Direction.UP) Metric.Warn else Metric.Energy,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Section of the app detail sheet: the app's last update and what changed since. Nothing if it was not updated lately. */
@Composable
fun AppUpdateSection(pkg: String) {
    val context = LocalContext.current
    val r = rememberLoaded(pkg) { loadUpdates(context).firstOrNull { it.pkg == pkg }?.result } ?: return
    val e = r.update
    val date = UpdateText.date(context, e.timeMillis)
    val version = UpdateText.version(e.newName)
    Spacer(Modifier.height(10.dp))
    Text(
        if (version != null) stringResource(R.string.su_detail_head, date, version) else stringResource(R.string.su_detail_head_nov, date),
        style = MaterialTheme.typography.titleSmall, color = LocalSurfaces.current.muted,
    )
    when {
        r.clear -> r.changes.forEach { ChangeLine(it) }
        r.enough -> Note(stringResource(R.string.su_detail_none))
        else -> Note(stringResource(R.string.su_detail_early))
    }
}
