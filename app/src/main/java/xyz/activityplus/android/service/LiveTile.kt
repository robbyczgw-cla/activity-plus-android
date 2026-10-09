package xyz.activityplus.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.data.StatusItem
import xyz.activityplus.android.ui.StatusText

/** Quick Settings tile: power and temperature at a glance, a tap opens the app. */
class LiveTile : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        val app = ActivityPlusApp.instance
        app.monitor.register(CLIENT, 2_000)
        job = scope.launch {
            app.monitor.latest.filterNotNull().collect { s ->
                val tile = qsTile ?: return@collect
                val settings = app.prefs.current
                val items = listOf(settings.iconItem, StatusItem.TEMPERATURE, StatusItem.BATTERY).distinct().take(2)
                val text = items.mapNotNull { StatusText.value(it, s, settings)?.toString() }.joinToString(" · ")
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = text
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        ActivityPlusApp.instance.monitor.unregister(CLIENT)
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val CLIENT = "tile"
    }
}
