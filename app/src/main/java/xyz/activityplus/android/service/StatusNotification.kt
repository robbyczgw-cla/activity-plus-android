package xyz.activityplus.android.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.view.View
import android.widget.RemoteViews
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.ui.StatusText

/**
 * The Android counterpart of the Mac menu bar: a quiet ongoing notification whose small icon is
 * the live value itself, and whose body shows the items the user picked.
 */
object StatusNotification {
    const val ID = 1

    private val cells = listOf(
        Triple(R.id.cell0, R.id.label0, R.id.value0),
        Triple(R.id.cell1, R.id.label1, R.id.value1),
        Triple(R.id.cell2, R.id.label2, R.id.value2),
        Triple(R.id.cell3, R.id.label3, R.id.value3),
        Triple(R.id.cell4, R.id.label4, R.id.value4),
        Triple(R.id.cell5, R.id.label5, R.id.value5),
        Triple(R.id.cell6, R.id.label6, R.id.value6),
        Triple(R.id.cell7, R.id.label7, R.id.value7),
    )

    fun build(context: Context, s: Snapshot?, settings: Settings, foregroundLabel: String?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, ActivityPlusApp.CHANNEL_LIVE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)

        if (s == null) {
            return builder.setSmallIcon(R.drawable.ic_pulse).setContentTitle(context.getString(R.string.app_name)).build()
        }

        val icon = StatusText.iconText(settings.iconItem, s, settings)
        builder.setSmallIcon(if (icon != null) textIcon(context, icon.first, icon.second) else Icon.createWithResource(context, R.drawable.ic_pulse))

        val small = RemoteViews(context.packageName, R.layout.notification_status)
        fill(context, small, s, settings, 4)
        val big = RemoteViews(context.packageName, R.layout.notification_status_big)
        fill(context, big, s, settings, 8)
        // Second row only when there is something to show in it.
        big.setViewVisibility(R.id.row2, if (settings.items.size > 4) View.VISIBLE else View.GONE)
        val footer = if (!settings.appLine) "" else buildString {
            if (foregroundLabel != null) append(context.getString(R.string.notif_on_screen, foregroundLabel))
            s.battery.avgPowerMw?.let { mw ->
                if (isNotEmpty()) append(" · ")
                append(
                    if (mw < 0) context.getString(R.string.notif_drawing, Format.watts(-mw).toString())
                    else context.getString(R.string.notif_charging, Format.watts(mw).toString())
                )
            }
        }
        big.setTextViewText(R.id.footer, footer)
        big.setViewVisibility(R.id.footer, if (footer.isEmpty()) View.GONE else View.VISIBLE)

        return builder
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .build()
    }

    private fun fill(context: Context, views: RemoteViews, s: Snapshot, settings: Settings, count: Int) {
        cells.take(count).forEachIndexed { i, (cell, label, value) ->
            val item = settings.items.getOrNull(i)
            if (item == null) {
                views.setViewVisibility(cell, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(cell, View.VISIBLE)
            views.setTextViewText(label, StatusText.label(context, item))
            StatusText.color(item, s, settings.colorMode)?.let { views.setTextColor(label, it) }
            views.setTextViewText(value, StatusText.value(item, s, settings)?.toString() ?: "–")
        }
    }

    /** Draws the value as a monochrome status bar icon; the system tints it. */
    private fun textIcon(context: Context, value: String, unit: String): Icon {
        val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // Value fills the top two thirds; shrink until it fits the width.
        paint.textSize = size * 0.66f
        while (paint.measureText(value) > size * 1.02f && paint.textSize > 8) paint.textSize -= 1f
        c.drawText(value, size / 2f, size * 0.62f, paint)
        paint.textSize = size * 0.36f
        while (paint.measureText(unit) > size && paint.textSize > 6) paint.textSize -= 1f
        c.drawText(unit, size / 2f, size * 0.98f, paint)
        return Icon.createWithBitmap(bmp)
    }
}
