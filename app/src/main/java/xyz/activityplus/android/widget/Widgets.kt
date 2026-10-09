package xyz.activityplus.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.data.StatusItem
import xyz.activityplus.android.ui.StatusText
import kotlin.math.abs

/** Which values each widget instance shows; chosen in [WidgetConfigActivity]. */
object WidgetPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    val defaultValue = listOf(StatusItem.POWER)
    val defaultPanel = listOf(
        StatusItem.POWER, StatusItem.BATTERY, StatusItem.TIME_LEFT,
        StatusItem.TEMPERATURE, StatusItem.RAM_FREE, StatusItem.NETWORK,
    )

    fun items(c: Context, id: Int, fallback: List<StatusItem>): List<StatusItem> =
        sp(c).getString("w.$id", null)?.split(",")?.mapNotNull { n -> StatusItem.entries.find { it.name == n } }
            ?.takeIf { it.isNotEmpty() } ?: fallback

    fun setItems(c: Context, id: Int, items: List<StatusItem>) =
        sp(c).edit().putString("w.$id", items.joinToString(",") { it.name }).apply()

    fun remove(c: Context, ids: IntArray) = sp(c).edit().apply { ids.forEach { remove("w.$it") } }.apply()
}

/** Draws all widgets from one snapshot. Called by the service every 30 s while the screen is on. */
object WidgetUpdater {
    fun updateAll(context: Context, s: Snapshot) {
        val awm = AppWidgetManager.getInstance(context)
        val settings = ActivityPlusApp.instance.prefs.current
        awm.getAppWidgetIds(ComponentName(context, ValueWidget::class.java)).forEach { update(context, awm, it, s, settings, Kind.VALUE) }
        awm.getAppWidgetIds(ComponentName(context, PanelWidget::class.java)).forEach { update(context, awm, it, s, settings, Kind.PANEL) }
        awm.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java)).forEach { update(context, awm, it, s, settings, Kind.BATTERY) }
    }

    fun hasWidgets(context: Context): Boolean {
        val awm = AppWidgetManager.getInstance(context)
        return listOf(ValueWidget::class.java, PanelWidget::class.java, BatteryWidget::class.java)
            .any { awm.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    enum class Kind { VALUE, PANEL, BATTERY }

    fun update(context: Context, awm: AppWidgetManager, id: Int, s: Snapshot, settings: Settings, kind: Kind) {
        val views = when (kind) {
            Kind.VALUE -> value(context, id, s, settings)
            Kind.PANEL -> panel(context, id, s, settings)
            Kind.BATTERY -> battery(context, s)
        }
        val open = PendingIntent.getActivity(
            context, 100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.root, open)
        awm.updateAppWidget(id, views)
    }

    private fun value(c: Context, id: Int, s: Snapshot, settings: Settings): RemoteViews {
        val item = WidgetPrefs.items(c, id, WidgetPrefs.defaultValue).first()
        return RemoteViews(c.packageName, R.layout.widget_value).apply {
            setTextViewText(R.id.label, StatusText.label(c, item))
            setTextColor(R.id.label, StatusText.color(item, s, settings.colorMode) ?: 0xFF9AA0BC.toInt())
            setTextViewText(R.id.value, StatusText.value(item, s, settings)?.toString() ?: "–")
        }
    }

    private val cells = listOf(
        Triple(R.id.cell0, R.id.label0, R.id.value0), Triple(R.id.cell1, R.id.label1, R.id.value1),
        Triple(R.id.cell2, R.id.label2, R.id.value2), Triple(R.id.cell3, R.id.label3, R.id.value3),
        Triple(R.id.cell4, R.id.label4, R.id.value4), Triple(R.id.cell5, R.id.label5, R.id.value5),
    )

    private fun panel(c: Context, id: Int, s: Snapshot, settings: Settings): RemoteViews {
        val items = WidgetPrefs.items(c, id, WidgetPrefs.defaultPanel).take(6)
        val v = RemoteViews(c.packageName, R.layout.widget_panel)
        cells.forEachIndexed { i, (cell, label, value) ->
            val item = items.getOrNull(i)
            if (item == null) {
                v.setViewVisibility(cell, View.INVISIBLE); return@forEachIndexed
            }
            v.setViewVisibility(cell, View.VISIBLE)
            v.setTextViewText(label, StatusText.label(c, item))
            v.setTextColor(label, StatusText.color(item, s, settings.colorMode) ?: 0xFF9AA0BC.toInt())
            v.setTextViewText(value, StatusText.value(item, s, settings)?.toString() ?: "–")
        }
        v.setViewVisibility(R.id.row2, if (items.size > 3) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.updated, android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(s.timeMillis)))
        v.setTextViewText(R.id.footer, footer(c, s))
        return v
    }

    /** "Most today: Chrome 0.7 Wh", or the charging line while plugged in. */
    private fun footer(c: Context, s: Snapshot): String {
        val b = s.battery
        if (b.plugged) {
            val w = b.avgPowerMw?.takeIf { it > 0 } ?: return c.getString(R.string.status_charging)
            return c.getString(R.string.notif_charging, Format.watts(w).toString()) +
                (b.timeLeftSeconds?.let { " · " + c.getString(R.string.widget_full_in, Format.duration(it)) } ?: "")
        }
        val now = System.currentTimeMillis()
        val day = ActivityPlusApp.dayKey(now)
        val top = runCatching { ActivityPlusApp.instance.history.energy(day, day) }.getOrNull()
            ?.firstOrNull { !it.pkg.startsWith("#") && it.pkg != c.packageName } ?: return ""
        val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(top.pkg, 0)).toString() }
            .getOrDefault(top.pkg)
        return c.getString(R.string.widget_most_today, label, Format.energy(top.mwh).toString())
    }

    private fun battery(c: Context, s: Snapshot): RemoteViews {
        val b = s.battery
        val v = RemoteViews(c.packageName, R.layout.widget_battery)
        v.setImageViewBitmap(R.id.ring, ring(b.levelFraction, b.plugged))
        val power = b.avgPowerMw?.let { Format.watts(abs(it)).toString() }
        v.setTextViewText(R.id.line1, listOfNotNull(power, b.percentPerHour?.let { (if (it > 0) "+" else "") + Format.number(it, 1) + " %/h" }).joinToString(" · "))
        v.setTextViewText(
            R.id.line2,
            b.timeLeftSeconds?.let {
                c.getString(if (b.charging) R.string.widget_full_in else R.string.widget_left, Format.duration(it))
            } ?: c.getString(if (b.plugged) R.string.status_charging else R.string.status_discharging),
        )
        return v
    }

    /** Charge ring: green, amber below 30 %, red below 15 %; a bolt while plugged in. */
    private fun ring(level: Double, plugged: Boolean): Bitmap {
        val size = 300
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = 30f
        val rect = RectF(stroke, stroke, size - stroke, size - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = 0x33FFFFFF
        c.drawArc(rect, 0f, 360f, false, p)
        p.color = when {
            level < 0.15 -> 0xFFE5484D.toInt()
            level < 0.30 -> 0xFFF2A33A.toInt()
            else -> 0xFF26A862.toInt()
        }
        c.drawArc(rect, -90f, (360 * level).toFloat().coerceAtLeast(1f), false, p)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 84f
        }
        val label = Format.number(level * 100, 0) + "%"
        c.drawText(label, size / 2f, size / 2f + 28f, text)
        if (plugged) {
            text.textSize = 48f
            c.drawText("⚡", size / 2f, size / 2f + 92f, text)
        }
        return bmp
    }

    /** Without the service: one fresh reading per Android's widget schedule (every 30 min). */
    fun refreshFromScratch(context: Context) {
        val s = runCatching { xyz.activityplus.android.core.SystemSampler(context.applicationContext).sample() }.getOrNull() ?: return
        updateAll(context, s)
    }
}

abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val latest = ActivityPlusApp.instance.monitor.latest.value
        if (latest != null && System.currentTimeMillis() - latest.timeMillis < 120_000) WidgetUpdater.updateAll(context, latest)
        else WidgetUpdater.refreshFromScratch(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) = WidgetPrefs.remove(context, ids)
}

class ValueWidget : BaseWidget()
class PanelWidget : BaseWidget()
class BatteryWidget : BaseWidget()
