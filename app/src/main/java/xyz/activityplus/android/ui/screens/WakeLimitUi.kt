package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.WakeLimit
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.pro.BackgroundSchedule
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.Metric
import java.time.LocalDate
import java.time.ZoneId

/*
 * Google's wakelock limit (core/WakeLimit) as a badge next to an app, from the per-day wakelock time
 * the "Who woke the phone" card already keeps. Reads only; nothing here needs more than that card.
 */

/** The apps regularly above the limit by package; empty without the grant or enough days. Reads the store: off the main thread. */
fun loadWakeLimit(context: Context): Map<String, WakeLimit.Verdict> {
    if (!ProShell.hasBatteryStats(context) || !WakeupStore.exists(context)) return emptyMap()
    val days = BackgroundSchedule.lastDays(LocalDate.now(ZoneId.systemDefault()), WakeLimit.WINDOW_DAYS)
    return WakeLimit.flagged(WakeupStore.get(context).allSeries(days.first(), days.last()), days).associateBy { it.pkg }
}

@Composable
fun WakeLimitBadge() = Badge(stringResource(R.string.wl_badge), Metric.Warn)

/** In an app's wakeup section: the badge, what it means and on how many days the app reached it. */
@Composable
fun WakeLimitNote(pkg: String) {
    val context = LocalContext.current
    val v = rememberLoaded(pkg) { loadWakeLimit(context)[pkg] } ?: return
    Column {
        Spacer(Modifier.height(6.dp))
        WakeLimitBadge()
        Spacer(Modifier.height(4.dp))
        Note(stringResource(R.string.wl_explain) + " " + stringResource(R.string.wl_days, v.exceededDays, v.measuredDays))
    }
}
