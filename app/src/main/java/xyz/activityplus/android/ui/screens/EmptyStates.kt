package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.Metric

/*
 * Shared pieces for missing data. The rule: a card whose data is not there yet stays in place and
 * says why and when that changes; what this phone can never show collapses into one quiet line
 * at the end of the screen ([NotReportedNote]).
 */

/**
 * Battery per app with nothing measured: without usage access Activity+ cannot tell which app is on
 * screen, so that is the reason to give, with the way to allow it; otherwise it simply has not run yet.
 */
@Composable
fun DrainEmptyCard(title: String, access: Boolean) {
    val context = LocalContext.current
    Card {
        CardHeader(title, Metric.Energy)
        Spacer(Modifier.height(8.dp))
        Note(stringResource(if (access) R.string.drain_empty else R.string.es_drain_needs_access))
        if (!access) {
            TextButton(onClick = { Actions.usageAccess(context) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.access_button) + " ›")
            }
        }
    }
}

/** "Not reported by this phone: Cameras, Sensors." Nothing when [parts] is empty. */
@Composable
fun NotReportedNote(parts: List<String>) {
    if (parts.isEmpty()) return
    Note(stringResource(R.string.es_hw_not_reported, parts.joinToString(", ")), Modifier.padding(horizontal = 4.dp))
}

/**
 * Below the app detail's sections. Those come only when they concern the app, and four of them need
 * a grant; without this line a short detail would look broken rather than quiet.
 */
@Composable
fun AppDetailCoverage(onPro: () -> Unit) {
    val context = LocalContext.current
    // Checked again on every return: the grant is given on the pro page or from a computer.
    val granted = remember(rememberResumes()) { ProShell.hasComputerGrant(context) && ProShell.hasBatteryStats(context) }
    AppDetailCoverageContent(granted, onPro)
}

@Composable
fun AppDetailCoverageContent(granted: Boolean, onPro: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Note(stringResource(R.string.es_detail_sections))
    if (!granted) {
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.es_detail_grant))
        TextButton(onClick = onPro, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.es_set_up) + " ›") }
    }
}
