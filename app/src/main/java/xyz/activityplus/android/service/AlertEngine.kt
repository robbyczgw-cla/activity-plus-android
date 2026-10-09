package xyz.activityplus.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryMath
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.core.ThermalStatus
import xyz.activityplus.android.data.Prefs
import kotlin.math.abs

/**
 * Alerts in one sentence that names the cause: heat, storage, a charger too weak for the load,
 * an app drawing a lot of power. Each kind has a cooldown so it never nags.
 */
class AlertEngine(private val context: Context, private val prefs: Prefs) {
    private var plugDrainSince = 0L
    private var heavyPkg: String? = null
    private var heavySince = 0L
    private var heavySum = 0.0
    private var heavyCount = 0

    fun check(s: Snapshot, foregroundLabel: String?) {
        val now = s.timeMillis
        val b = s.battery
        val f = prefs.current.fahrenheit

        if (b.temperatureC >= 45 || s.thermal.status >= ThermalStatus.SEVERE) {
            val temp = Format.temperature(b.temperatureC, f).toString()
            notify("hot", now, 2 * HOUR, context.getString(R.string.alert_hot_title, temp),
                if (foregroundLabel != null) context.getString(R.string.alert_hot_app, foregroundLabel)
                else context.getString(R.string.alert_hot_body))
        }

        if (s.storage.freeFraction < 0.05) {
            notify("storage", now, 24 * HOUR, context.getString(R.string.alert_storage_title),
                context.getString(R.string.alert_storage_body, Format.bytes(s.storage.freeBytes).toString()))
        }

        // Plugged in, yet the battery loses charge for five minutes.
        val current = b.currentMa
        if (b.plugged && current != null && current < -50) {
            if (plugDrainSince == 0L) plugDrainSince = now
            if (now - plugDrainSince >= 5 * MINUTE) {
                notify("plugdrain", now, 6 * HOUR, context.getString(R.string.alert_plugdrain_title),
                    context.getString(R.string.alert_plugdrain_body, Format.number(abs(current), 0)))
            }
        } else {
            plugDrainSince = 0L
        }

        // One app on screen drawing more than 4.5 W for three minutes.
        val power = b.powerMw
        val pkg = s.foregroundPackage
        if (s.screenOn && !b.plugged && power != null && pkg != null) {
            if (pkg != heavyPkg) {
                heavyPkg = pkg; heavySince = now; heavySum = 0.0; heavyCount = 0
            }
            heavySum += -power; heavyCount++
            val avg = heavySum / heavyCount
            if (now - heavySince >= 3 * MINUTE && avg >= 4500 && foregroundLabel != null) {
                val left = BatteryMath.hoursLeft(b.chargeMah, b.voltageV, avg)
                val body = if (left != null) {
                    context.getString(R.string.alert_power_body_left, Format.duration((left * 3600).toLong()))
                } else context.getString(R.string.alert_power_body)
                notify("power.$pkg", now, 3 * HOUR,
                    context.getString(R.string.alert_power_title, foregroundLabel, Format.watts(avg).toString()), body)
            }
        } else {
            heavyPkg = null
        }
    }

    fun notify(key: String, now: Long, cooldown: Long, title: String, body: String) {
        if (now - prefs.lastAlert(key) < cooldown) return
        prefs.setLastAlert(key, now)
        val open = PendingIntent.getActivity(
            context, key.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, ActivityPlusApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
