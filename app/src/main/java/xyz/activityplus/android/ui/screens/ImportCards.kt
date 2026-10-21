package xyz.activityplus.android.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.activityplus.android.R
import xyz.activityplus.android.core.DataTransfer
import xyz.activityplus.android.core.Table
import xyz.activityplus.android.core.Tables
import xyz.activityplus.android.share.ImportData
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine

/*
 * Import data, under Export data in Settings: pick the ZIP, see what is in it, agree, see what happened.
 * Every step ends in something on screen, also a file that is not an export and a database that fails.
 */

private sealed interface ImportStage {
    object Idle : ImportStage
    object Working : ImportStage
    class Confirm(val uri: Uri, val preview: DataTransfer.Preview) : ImportStage
    class Message(@StringRes val title: Int, val body: String) : ImportStage
    class Done(val report: DataTransfer.Report) : ImportStage
}

/** The title, hint and button; the dialogs open from it. Called inside the export card's column. */
@Composable
fun ImportDataSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ImportStage>(ImportStage.Idle) }

    // The system's own "open" screen: no storage permission, the user picks the file.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stage = ImportStage.Working
        scope.launch {
            val inspection = withContext(Dispatchers.IO) { runCatching { ImportData.inspect(context, uri) }.getOrNull() }
            stage = when {
                inspection == null -> ImportStage.Message(R.string.import_problem_title, context.getString(R.string.import_err_read))
                inspection.preview != null -> ImportStage.Confirm(uri, inspection.preview)
                else -> ImportStage.Message(R.string.import_problem_title, problemText(context, inspection.rejected!!))
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleSmall)
    Note(stringResource(R.string.import_hint))
    TextButton(
        enabled = stage !is ImportStage.Working,
        onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
    ) { Text(stringResource(if (stage is ImportStage.Working) R.string.import_working else R.string.import_action)) }

    when (val s = stage) {
        is ImportStage.Confirm -> AlertDialog(
            onDismissRequest = { stage = ImportStage.Idle },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { PreviewBody(s.preview) },
            confirmButton = {
                TextButton(onClick = {
                    stage = ImportStage.Working
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { runCatching { ImportData.run(context, s.uri, s.preview.format) }.getOrNull() }
                        stage = if (report != null) ImportStage.Done(report)
                        else ImportStage.Message(R.string.import_failed_title, context.getString(R.string.import_failed))
                    }
                }) { Text(stringResource(R.string.import_go)) }
            },
            dismissButton = { TextButton(onClick = { stage = ImportStage.Idle }) { Text(stringResource(R.string.cancel)) } },
        )
        is ImportStage.Message -> AlertDialog(
            onDismissRequest = { stage = ImportStage.Idle },
            title = { Text(stringResource(s.title)) },
            text = { Text(s.body) },
            confirmButton = { TextButton(onClick = { stage = ImportStage.Idle }) { Text(stringResource(R.string.import_ok)) } },
        )
        is ImportStage.Done -> AlertDialog(
            onDismissRequest = { stage = ImportStage.Idle },
            title = { Text(stringResource(R.string.import_done_title)) },
            text = { ResultBody(s.report) },
            confirmButton = { TextButton(onClick = { stage = ImportStage.Idle }) { Text(stringResource(R.string.import_ok)) } },
        )
        ImportStage.Idle, ImportStage.Working -> Unit
    }
}

private fun problemText(context: android.content.Context, r: DataTransfer.Rejected): String = when (r.problem) {
    DataTransfer.Problem.NOT_AN_EXPORT -> context.getString(R.string.import_err_not_export)
    DataTransfer.Problem.NEWER_FORMAT -> context.getString(R.string.import_err_newer, r.format ?: 0)
    DataTransfer.Problem.UNKNOWN_FORMAT -> context.getString(R.string.import_err_format)
    DataTransfer.Problem.NOTHING_TO_IMPORT -> context.getString(R.string.import_err_empty)
}

private val DATE = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH

/** What the file holds: where from, which days, how many rows of what. */
@Composable
private fun PreviewBody(p: DataTransfer.Preview) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        val device = p.device ?: stringResource(R.string.import_source_unknown)
        val saved = p.exportedMillis?.let { DateUtils.formatDateTime(context, it, DATE) } ?: stringResource(R.string.import_date_unknown)
        Text(stringResource(R.string.import_source, device, saved), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        val first = p.firstMillis
        val last = p.lastMillis
        Text(
            if (first != null && last != null) {
                stringResource(R.string.import_range, DateUtils.formatDateTime(context, first, DATE), DateUtils.formatDateTime(context, last, DATE))
            } else stringResource(R.string.import_range_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (p.partial) {
            Spacer(Modifier.height(4.dp))
            Note(stringResource(R.string.import_partial, p.version?.let { "Activity+ $it" } ?: stringResource(R.string.import_version_unknown)))
        }
        Spacer(Modifier.height(8.dp))
        for (t in p.tables.filter { it.table.listed }) StatLine(stringResource(label(t.table)), "%,d".format(t.rows))
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.import_confirm_hint))
    }
}

/** What happened: the totals first, then each table that had anything to say. */
@Composable
private fun ResultBody(r: DataTransfer.Report) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            if (r.added == 0 && r.duplicates > 0) stringResource(R.string.import_done_nothing) else stringResource(R.string.import_done_added, r.added),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (r.duplicates > 0 && r.added > 0) Note(stringResource(R.string.import_done_duplicates, r.duplicates))
        if (r.invalid > 0) Note(stringResource(R.string.import_done_invalid, r.invalid))
        if (r.unreadable > 0) Note(stringResource(R.string.import_done_unreadable, r.unreadable))
        Spacer(Modifier.height(8.dp))
        for (t in r.tables.filter { it.table.listed && (it.added > 0 || it.duplicates > 0) }) {
            StatLine(stringResource(label(t.table)), stringResource(R.string.import_row_result, t.added, t.duplicates))
        }
    }
}

@StringRes
private fun label(t: Table): Int = when (t) {
    Tables.SAMPLES -> R.string.import_t_samples
    Tables.APP_ENERGY -> R.string.import_t_app_energy
    Tables.SESSIONS -> R.string.import_t_sessions
    Tables.PRO_REPORTS -> R.string.import_t_pro_reports
    Tables.CHARGER_TESTS -> R.string.import_t_charger_tests
    Tables.CHARGE_CAPACITY -> R.string.import_t_charge_capacity
    Tables.BACKGROUND_DAYS -> R.string.import_t_background_days
    Tables.WAKEUP_DAYS -> R.string.import_t_wakeup_days
    Tables.NIGHT_SPANS -> R.string.import_t_night_spans
    Tables.STORAGE_DEVICE -> R.string.import_t_storage_device
    Tables.STORAGE_APPS -> R.string.import_t_storage_apps
    Tables.SENSOR_EVENTS -> R.string.import_t_sensor_events
    Tables.APP_UPDATES -> R.string.import_t_app_updates
    Tables.NET_USAGE -> R.string.import_t_net_usage
    Tables.THERMAL_EVENTS -> R.string.import_t_thermal_events
    else -> error("No label for ${t.file}")
}
