package xyz.activityplus.android.ui.screens

import android.app.NotificationManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import xyz.activityplus.android.R
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberResumes

/**
 * The "Charging as Live Update" switch of the charging settings. Android 16 only; older versions
 * have no Live Updates, so there is nothing to switch. If the system does not let the app promote
 * notifications, one line says so and the button opens the page where the user can allow it.
 */
@Composable
fun ChargeLiveSetting(on: Boolean) {
    if (Build.VERSION.SDK_INT < 36) return
    val context = LocalContext.current
    Toggle(stringResource(R.string.cl_set_title), stringResource(R.string.cl_set_hint), on) { value ->
        app.prefs.update { it.copy(chargeLive = value) }
    }
    // Looked up again when the user comes back from the system page.
    val resumes = rememberResumes()
    val allowed = remember(resumes) {
        context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    }
    if (on && !allowed) {
        Note(stringResource(R.string.cl_set_blocked))
        TextButton(onClick = { Actions.liveUpdateSettings(context) }) { Text(stringResource(R.string.cl_set_open)) }
    }
}
