package xyz.activityplus.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import xyz.activityplus.android.core.AppUsageReader
import xyz.activityplus.android.data.HistoryStore
import xyz.activityplus.android.data.Monitor
import xyz.activityplus.android.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Holds the few app-wide objects: live monitor, history, settings, per-app reader. */
class ActivityPlusApp : Application() {
    lateinit var monitor: Monitor
    lateinit var history: HistoryStore
    lateinit var prefs: Prefs
    lateinit var usage: AppUsageReader

    override fun onCreate() {
        super.onCreate()
        instance = this
        monitor = Monitor(this)
        history = HistoryStore(this)
        prefs = Prefs(this)
        usage = AppUsageReader(this)
        val nm = getSystemService(NotificationManager::class.java)
        // DEFAULT, not LOW: since Android 12 the status bar hides icons of silent notifications,
        // and the icon is the whole point. No sound or vibration, and every update is silent.
        nm.deleteNotificationChannel("live")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                description = getString(R.string.channel_live_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_LIVE = "status"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: ActivityPlusApp
            private set

        private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun dayKey(millis: Long): String = synchronized(dayFormat) { dayFormat.format(Date(millis)) }

        fun startOfDay(millis: Long): Long {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = millis
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
