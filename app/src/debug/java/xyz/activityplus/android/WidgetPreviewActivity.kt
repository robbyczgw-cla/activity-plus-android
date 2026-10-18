package xyz.activityplus.android

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import xyz.activityplus.android.widget.BatteryWidget
import xyz.activityplus.android.widget.HistoryWidget
import xyz.activityplus.android.widget.NightWidget
import xyz.activityplus.android.widget.PanelWidget
import xyz.activityplus.android.widget.ValueWidget
import xyz.activityplus.android.widget.WidgetUpdater

/**
 * Hosts one of each widget, so they can be checked without placing them on a launcher.
 * Binding needs the user's OK once: where `cmd appwidget grantbind` is missing (Android 15 images), the
 * first start shows Android's dialog; tick "Always allow", create, and start this activity again.
 */
class WidgetPreviewActivity : Activity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 4711)
        val awm = AppWidgetManager.getInstance(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF3A5A7A.toInt())
            setPadding(40, 140, 40, 40)
        }
        val dp = resources.displayMetrics.density
        for ((cls, w, h) in listOf(
            Triple(PanelWidget::class.java, 340, 170),
            Triple(NightWidget::class.java, 340, 170),
            Triple(HistoryWidget::class.java, 340, 170),
            Triple(BatteryWidget::class.java, 160, 160),
            Triple(ValueWidget::class.java, 160, 80),
        )) {
            val id = host.allocateAppWidgetId()
            if (!awm.bindAppWidgetIdIfAllowed(id, ComponentName(this, cls))) {
                host.deleteAppWidgetId(id)
                // The dialog reads the caller, which only a call for a result passes on.
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, host.allocateAppWidgetId())
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName(this, cls)),
                    BIND_REQUEST,
                )
                return
            }
            val view = host.createView(this, id, awm.getAppWidgetInfo(id))
            column.addView(view, LinearLayout.LayoutParams((w * dp).toInt(), (h * dp).toInt()).apply { bottomMargin = (16 * dp).toInt() })
        }
        setContentView(column)
        val s = ActivityPlusApp.instance.monitor.latest.value
        if (s != null) WidgetUpdater.updateAll(this, s) else WidgetUpdater.refreshFromScratch(this)
    }

    @Deprecated("Activity result API is not needed for a debug screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BIND_REQUEST) recreate()
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    private companion object {
        const val BIND_REQUEST = 1
    }
}
