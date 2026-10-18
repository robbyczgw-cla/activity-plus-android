package xyz.activityplus.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ChargeLive
import xyz.activityplus.android.core.ChargeLiveGate
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Settings
import java.util.Date

/**
 * Charging as an Android 16 Live Update: a promoted ongoing notification with a progress bar, a
 * point at the charge limit, the power and when the limit or full is reached; the level sits in the
 * status bar chip. Its own channel and id, the status notification stays as it is. What it shows is
 * decided by [ChargeLive]; this class words it and talks to the system. Below Android 16 it does nothing.
 */
class ChargeLiveUpdate(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val gate = ChargeLiveGate()
    private var last: ChargeLive.Plan? = null
    private var lastPost = 0L
    private var channelReady = false

    /** True at the start: a notification left by a killed process has to be cleared once. */
    private var maybeShown = true

    fun onSample(s: Snapshot, settings: Settings) {
        if (Build.VERSION.SDK_INT < 36) return
        val b = s.battery
        // The gate is fed on every sample, so it knows when charging stopped even with the switch off.
        val wanted = gate.onSample(s.timeMillis, b.plugged, b.charging) && settings.chargeLive
        if (!wanted) return cancel()

        val plan = ChargeLive.plan(
            ChargeLive.Input(
                level = b.levelFraction, status = b.status, limitPercent = settings.chargeLimit,
                powerMw = b.avgPowerMw, secondsToFull = b.timeLeftSeconds, percentPerHour = b.percentPerHour,
                tempC = b.temperatureC, wasWarm = last?.warm == true, fahrenheit = settings.fahrenheit, now = s.timeMillis,
            )
        )
        if (!ChargeLive.shouldPost(last, plan, lastPost, s.timeMillis)) return
        // Without promotion (the user turned Live Updates off for the app) it would only be a second ongoing notification.
        if (!nm.canPostPromotedNotifications()) return cancel()
        ensureChannel()
        nm.notify(ID, build(plan))
        last = plan
        lastPost = s.timeMillis
        maybeShown = true
    }

    /** Unplugged, switched off, or the monitor stopped: a stale percentage must not stay up. */
    fun cancel() {
        last = null
        if (!maybeShown) return
        maybeShown = false
        nm.cancel(ID)
    }

    private fun build(plan: ChargeLive.Plan): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            plan.warm -> context.getString(R.string.cl_title_warm, plan.temperatureText)
            plan.stage == ChargeLive.Stage.FULL -> context.getString(R.string.cl_title_full)
            plan.stage == ChargeLive.Stage.HOLDING -> context.getString(R.string.cl_title_holding, plan.levelText)
            else -> context.getString(R.string.cl_title_charging, plan.levelText)
        }
        val style = Notification.ProgressStyle()
            .setProgress(plan.percent)
            // The part already charged is drawn in the accent color, the rest stays grey.
            .setStyledByProgress(true)
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
        plan.limitPoint?.let { style.addProgressPoint(Notification.ProgressStyle.Point(it)) }

        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(title)
            .setContentText(body(plan))
            .setShortCriticalText(plan.shortText)
            .setStyle(style)
            // Live Updates need all of these: ongoing, a title, no custom views, not colorized.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            // This SDK has no setRequestPromotedOngoing yet; the extra is what it sets.
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true) })
            .build()
    }

    /** "18.2 W · 80 % at 14:05"; the level comes first when the title is about the heat. */
    private fun body(plan: ChargeLive.Plan): String {
        val parts = ArrayList<String>(3)
        if (plan.warm) parts += plan.levelText
        when (plan.stage) {
            ChargeLive.Stage.FULL -> parts += context.getString(R.string.cl_unplug)
            ChargeLive.Stage.HOLDING -> parts += context.getString(R.string.cl_not_charging)
            ChargeLive.Stage.CHARGING -> {
                plan.powerText?.let { parts += it }
                plan.targetMillis?.let { millis ->
                    val time = DateFormat.getTimeFormat(context).format(Date(millis))
                    parts += if (plan.target == ChargeLive.Target.LIMIT) {
                        context.getString(R.string.cl_target_limit, Format.percent(plan.targetPercent / 100.0), time)
                    } else context.getString(R.string.cl_target_full, time)
                }
            }
        }
        return parts.joinToString(" · ").ifEmpty { context.getString(R.string.cl_charging) }
    }

    private fun ensureChannel() {
        if (channelReady) return
        // LOW: silent and no heads-up on every update, yet not MIN, which Live Updates reject.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.cl_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = context.getString(R.string.cl_channel_desc)
            }
        )
        channelReady = true
    }

    private companion object {
        const val ID = 2
        const val CHANNEL = "charge_live"
        const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    }
}
