package xyz.activityplus.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.activityplus.android.R
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.tapRow
import xyz.activityplus.android.ui.theme.Metric

/**
 * Shown on the pro page while the read permissions added in 0.4.0 are missing: with Shizuku one tap
 * grants them, otherwise the updated computer line.
 */
@Composable
fun ExtraGrantsCard(access: ProShell.Access, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Card {
        CardHeader(stringResource(R.string.grants_title), Metric.Cpu, Icons.Outlined.LockOpen)
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.grants_body))
        if (access == ProShell.Access.SHIZUKU) {
            Button(onClick = {
                scope.launch {
                    failed = !runCatching { ProShell.grantExtrasWithShizuku(context) }.getOrDefault(false)
                    onChanged()
                }
            }, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.grants_shizuku)) }
            if (failed) Note(stringResource(R.string.grants_failed), Modifier.padding(top = 6.dp))
        } else {
            Note(stringResource(R.string.grants_computer), Modifier.padding(top = 6.dp))
            val command = ProShell.computerCommand(context)
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.tapRow(stringResource(R.string.a11y_copy_command)) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("adb", command))
                },
            )
        }
        Note(stringResource(R.string.grants_safe), Modifier.padding(top = 6.dp))
    }
}
