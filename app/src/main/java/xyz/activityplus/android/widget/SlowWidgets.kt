package xyz.activityplus.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.format.DateUtils
import android.widget.RemoteViews
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.Tab
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.GraphScale
import xyz.activityplus.android.core.NightCheck.Verdict
import xyz.activityplus.android.data.NightStore
import xyz.activityplus.android.share.ReportBuilder
import xyz.activityplus.android.ui.screens.loadNightCheck
import xyz.activityplus.android.ui.screens.appName
import kotlin.math.abs

/** What the history widget's graph shows. */
enum class HistoryMode { LEVEL, POWER }

/** The history widget's choice, in the same preferences file as the other widgets. */
object HistoryWidgetPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun mode(c: Context, id: Int): HistoryMode =
        HistoryMode.entries.find { it.name == sp(c).getString("h.$id", null) } ?: HistoryMode.LEVEL

    fun setMode(c: Context, id: Int, mode: HistoryMode) = sp(c).edit().putString("h.$id", mode.name).apply()

    fun remove(c: Context, ids: IntArray) = sp(c).edit().apply { ids.forEach { remove("h.$it") } }.apply()
}

/**
 * The night check and history widgets. Their content changes slowly (a night, a graph of hours),
 * so unlike the value widgets they are redrawn every five minutes at most, even though the
 * service calls [WidgetUpdater.updateAll] every 30 seconds while the screen is on. A widget
 * that was never drawn by this process is drawn at once.
 */
object SlowWidgets {
    private const val EVERY_MS = 5 * 60_000L
    private const val HOURS = 6

    private val drawn = HashMap<Int, Long>()
    private var lastNightSaved = 0L

    private fun due(id: Int, now: Long, force: Boolean = false): Boolean = synchronized(drawn) {
        val last = drawn[id]
        val go = force || last == null || now < last || now - last >= EVERY_MS
        if (go) drawn[id] = now
        go
    }

    fun hasWidgets(context: Context): Boolean {
        val awm = AppWidgetManager.getInstance(context)
        return listOf(NightWidget::class.java, HistoryWidget::class.java).any { awm.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    fun updateAll(context: Context, awm: AppWidgetManager, now: Long) {
        // A night saved since the last look (the screen-on that ends it) is shown without waiting.
        val saved = NightStore.saved.value
        val newNight = saved != lastNightSaved
        lastNightSaved = saved
        awm.getAppWidgetIds(ComponentName(context, NightWidget::class.java)).forEach { id ->
            if (due(id, now, newNight)) awm.updateAppWidget(id, night(context))
        }
        awm.getAppWidgetIds(ComponentName(context, HistoryWidget::class.java)).forEach { id ->
            if (due(id, now)) awm.updateAppWidget(id, history(context, awm, id, now))
        }
    }

    /** Draws one history widget right now: after it was configured or resized. */
    fun redrawHistory(context: Context, id: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val now = System.currentTimeMillis()
        due(id, now, force = true)
        awm.updateAppWidget(id, history(context, awm, id, now))
    }

    private fun open(context: Context, code: Int, tab: Tab) = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).putExtra("tab", tab.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val MUTED = 0xFF9AA0BC.toInt()
    private const val GREEN = 0xFF26A862.toInt()
    private const val AMBER = 0xFFF2A33A.toInt()
    private const val BLUE = 0xFF6E8BFF.toInt()

    // Night check ----------------------------------------------------------------------------

    private fun night(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_night)
        v.setOnClickPendingIntent(R.id.root, open(c, 101, Tab.BATTERY))
        val result = runCatching { loadNightCheck(c).result }.getOrNull()
        val m = result?.takeIf { it.verdict != Verdict.NOT_ENOUGH_DATA }?.measured
        v.setTextViewText(R.id.label, c.getString(R.string.bg_night_title))
        v.setTextViewText(
            R.id.updated,
            m?.let { time(c, it.fromMillis) + " – " + time(c, it.toMillis) } ?: "",
        )
        if (result == null || m == null) {
            v.setTextViewText(R.id.verdict, c.getString(R.string.nc_title_none))
            v.setTextColor(R.id.verdict, MUTED)
            for (value in listOf(R.id.value0, R.id.value1, R.id.value2)) v.setTextViewText(value, "–")
        } else {
            v.setTextViewText(R.id.verdict, ReportBuilder.verdictTitle(c, result))
            v.setTextColor(R.id.verdict, if (result.verdict == Verdict.SLEPT_WELL) GREEN else AMBER)
            v.setTextViewText(R.id.value0, Format.number(m.drainPerHour, 1) + " %/h")
            v.setTextViewText(R.id.value1, Format.percent(m.deepShare))
            v.setTextViewText(
                R.id.value2,
                when (result.verdict) {
                    Verdict.APP_KEPT_AWAKE -> appName(c, result.suspect!!.pkg, emptyMap())
                    Verdict.SYSTEM_DID_NOT_SLEEP -> c.getString(R.string.wn_blame_android)
                    Verdict.SLEPT_WELL -> c.getString(R.string.wn_blame_none)
                    else -> "–"
                },
            )
        }
        v.setTextViewText(R.id.label0, c.getString(R.string.nc_stat_drain))
        v.setTextViewText(R.id.label1, c.getString(R.string.nc_stat_deep))
        v.setTextViewText(R.id.label2, c.getString(R.string.wn_blame))
        return v
    }

    private fun time(c: Context, millis: Long) = DateUtils.formatDateTime(c, millis, DateUtils.FORMAT_SHOW_TIME)

    // History graph ----------------------------------------------------------------------------

    private fun history(c: Context, awm: AppWidgetManager, id: Int, now: Long): RemoteViews {
        val mode = HistoryWidgetPrefs.mode(c, id)
        val v = RemoteViews(c.packageName, R.layout.widget_history)
        v.setOnClickPendingIntent(R.id.root, open(c, 102, Tab.HISTORY))
        v.setTextViewText(R.id.title, c.getString(if (mode == HistoryMode.LEVEL) R.string.wh_title_level else R.string.wh_title_power, HOURS))

        val from = now - HOURS * 3_600_000L
        val rows = runCatching { ActivityPlusApp.instance.history.samples(from, now) }.getOrNull().orEmpty()
        val samples = rows.mapNotNull { r ->
            val value = when (mode) {
                HistoryMode.LEVEL -> r.level * 100
                HistoryMode.POWER -> r.powerMw?.let { abs(it) / 1000.0 }
            }
            value?.let { GraphScale.Sample(r.ts, it, if (r.charging) 1 else 0) }
        }
        if (samples.size < 2) {
            v.setImageViewBitmap(R.id.graph, null)
            v.setTextViewText(R.id.value, "")
            v.setTextViewText(R.id.range, c.getString(R.string.wh_empty))
            return v
        }
        val (w, h) = graphSize(c, awm, id)
        val scaled = GraphScale.scale(
            samples, from, now, w.toFloat(), h.toFloat(),
            minSpan = if (mode == HistoryMode.LEVEL) 10.0 else 0.5,
            pad = 4f * c.resources.displayMetrics.density,
            includeZero = mode == HistoryMode.POWER,
            bounds = if (mode == HistoryMode.LEVEL) 0.0..100.0 else null,
        )
        v.setImageViewBitmap(R.id.graph, draw(scaled, w, h, c.resources.displayMetrics.density, mode))

        val lo = samples.minOf { it.value }
        val hi = samples.maxOf { it.value }
        val text = { x: Double -> if (mode == HistoryMode.LEVEL) Format.number(x, 0) + " %" else Format.watts(x * 1000).toString() }
        v.setTextViewText(R.id.value, text(samples.last().value))
        v.setTextViewText(R.id.range, c.getString(R.string.wh_range, text(lo), text(hi)))
        return v
    }

    /** Pixels of the graph area from the widget's size on the launcher, kept small: the bitmap travels inside the RemoteViews. */
    private fun graphSize(c: Context, awm: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = awm.getAppWidgetOptions(id)
        val d = c.resources.displayMetrics.density
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        // The layout's padding and the header and footer rows around the graph.
        val w = ((wDp - 24) * d).toInt().coerceIn(200, 700)
        val h = ((hDp - 24 - 40) * d).toInt().coerceIn(60, 400)
        return w to h
    }

    private fun draw(s: GraphScale.Scaled, w: Int, h: Int, density: Float, mode: HistoryMode): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = 4f * density
        val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = density }
        // Three quiet lines to read the height against: top, middle and bottom of the plot.
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = pad + (h - 2 * pad) * f
            c.drawLine(0f, y, w.toFloat(), y, grid)
        }
        val floor = s.zeroY ?: (h - pad)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * density; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (seg in s.segments) {
            val color = when {
                seg.tag == 1 -> GREEN
                mode == HistoryMode.LEVEL -> BLUE
                else -> AMBER
            }
            val pts = seg.points
            if (pts.size == 1) {
                fill.shader = null; fill.color = color
                c.drawCircle(pts[0].x, pts[0].y, 2.5f * density, fill)
                continue
            }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            // The area under the line fades out downwards.
            val area = Path(path).apply { lineTo(pts.last().x, floor); lineTo(pts[0].x, floor); close() }
            fill.shader = LinearGradient(0f, pts.minOf { it.y }, 0f, floor, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            line.color = color
            c.drawPath(path, line)
        }
        // The newest point, so the eye finds "now" at the right edge.
        s.segments.lastOrNull()?.points?.lastOrNull()?.let { p ->
            fill.shader = null; fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(p.x, p.y, 3.5f * density, fill)
        }
        return bmp
    }
}
