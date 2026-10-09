package xyz.activityplus.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.WeeklyReport
import xyz.activityplus.android.data.Prefs
import xyz.activityplus.android.data.WeeklyLoader
import xyz.activityplus.android.ui.WeeklyText

/**
 * Every Monday morning, one notification whose text is the report's headline sentence.
 * The service calls [check] with every sample; it is cheap until Monday 08:00 has come.
 */
class WeeklyNotifier(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    @Volatile private var running = false

    fun check(now: Long) {
        val settings = prefs.current
        if (!settings.alerts || !settings.weekly || running || !WeeklyReport.notifyTime(now)) return
        // Once per week: the time of the last report is compared with this week's Monday.
        val weeks = WeeklyReport.weeks(now)
        if (prefs.lastAlert(KEY) >= weeks.lastEnd) return
        running = true
        // Remembered before the work, also when there is too little to say: the finished week will not get better.
        prefs.setLastAlert(KEY, now)
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = WeeklyLoader.load(context, now)
                if (!loaded.report.enough) return@launch
                val text = WeeklyText.headline(context, loaded) ?: return@launch
                notify(text)
            } catch (_: Exception) {
                // Best effort; next Monday there is another report.
            } finally {
                running = false
            }
        }
    }

    private fun notify(text: String) {
        val open = PendingIntent.getActivity(
            context, KEY.hashCode(), Intent(context, MainActivity::class.java).putExtra("tab", "WEEKLY"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, ActivityPlusApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(context.getString(R.string.weekly_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(KEY.hashCode(), n)
    }

    private companion object {
        const val KEY = "weekly"
    }
}
