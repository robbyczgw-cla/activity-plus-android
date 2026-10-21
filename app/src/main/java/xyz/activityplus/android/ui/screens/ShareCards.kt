package xyz.activityplus.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Csv
import xyz.activityplus.android.share.ExportData
import xyz.activityplus.android.share.ReportBuilder
import xyz.activityplus.android.share.ShareActions
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLoaded
import java.time.LocalDate

/*
 * Share a report and export the data. The report is built when the dialog opens, so it always
 * shows the numbers of that moment; the user can edit the text before choosing an app.
 */

/** A share icon for a top bar; opens the report dialog. */
@Composable
fun ShareReportIconButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_action))
    }
    if (open) ShareReportDialog { open = false }
}

/** The same, as a text button for the bottom of a card. */
@Composable
fun ShareReportTextButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.share_action))
    }
    if (open) ShareReportDialog { open = false }
}

@Composable
private fun ShareReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report = rememberLoaded(Unit) { ReportBuilder.build(context) }
    var text by remember(report) { mutableStateOf(report?.text ?: "") }
    val failed = { Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_action)) },
        text = {
            Column(Modifier.dialogContent()) {
                Note(stringResource(R.string.share_dialog_hint))
                Spacer(Modifier.height(10.dp))
                if (report == null) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    )
                    TextButton(onClick = {
                        // Drawing and writing the PNG is work for the background; the sheet opens on the main thread.
                        scope.launch {
                            val intent = withContext(Dispatchers.IO) { runCatching { ShareActions.imageIntent(context, report) }.getOrNull() }
                            if (intent != null && ShareActions.shareImage(context, intent)) onDismiss() else failed()
                        }
                    }) { Text(stringResource(R.string.share_image)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = report != null && text.isNotBlank(),
                onClick = { if (ShareActions.shareText(context, report!!.title, text)) onDismiss() else failed() },
            ) { Text(stringResource(R.string.share_text)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Settings, under Data: share the report, and save the history as files. */
@Composable
fun ShareAndExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var share by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The system's own "save as" screen: the app needs no storage permission, the user picks the place.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { ExportData.write(context, it) }
                }.getOrNull()
            }
            busy = false
            Toast.makeText(
                context,
                if (written != null) context.getString(R.string.export_done, written.size) else context.getString(R.string.export_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    Card {
        Text(stringResource(R.string.share_settings_title), style = MaterialTheme.typography.titleSmall)
        Note(stringResource(R.string.share_settings_hint))
        TextButton(onClick = { share = true }) { Text(stringResource(R.string.share_action)) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleSmall)
        Note(stringResource(R.string.export_hint))
        TextButton(enabled = !busy, onClick = { save.launch(Csv.zipName(LocalDate.now())) }) {
            Text(stringResource(if (busy) R.string.export_working else R.string.export_action))
        }
        ImportDataSection()
    }
    if (share) ShareReportDialog { share = false }
}
