package xyz.activityplus.android.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.DrainTracker
import xyz.activityplus.android.core.SessionTracker
import xyz.activityplus.android.widget.WidgetUpdater
import xyz.activityplus.android.core.Snapshot

/**
 * Keeps measuring while the app is closed: the status notification, one history row per minute,
 * battery drain per app, and alerts. Samples every few seconds with the screen on and once a
 * minute with it off (and not at all while the phone sleeps).
 */
class MonitorService : Service() {
    private val app get() = ActivityPlusApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drain = DrainTracker()
    private val sessions = SessionTracker()
    private lateinit var alerts: AlertEngine
    private val labels = HashMap<String, String>()
    private val background by lazy { BackgroundSampler(this, scope) }

    private var minuteStart = 0L
    private var powerSum = 0.0
    private var powerCount = 0
    private var lastPrune = 0L
    private var lastWidgets = 0L
    private var screenOn = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val b = app.monitor.latest.value?.battery
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    if (b != null) drain.onScreenOff(now, b.chargeMah, b.levelFraction, b.voltageV, !b.plugged)
                    register()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    lastWidgets = 0L
                    // A fresh reading: the last one is from before the phone slept.
                    scope.launch {
                        val fresh = runCatching { app.monitor.batteryNow() }.getOrNull()
                        if (fresh != null) {
                            drain.onScreenOn(
                                now, fresh.chargeMah, fresh.levelFraction, fresh.voltageV, !fresh.plugged,
                                fresh.estimatedFullMah ?: fresh.designMah,
                            )
                        }
                    }
                    register()
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> drain.onPowerChanged()
            }
            background.onBroadcast(intent.action, now)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts = AlertEngine(this, app.prefs)
        // Continue the session that was running when the service stopped, if the plug state still matches.
        runCatching { app.history.sessions(1).firstOrNull() }.getOrNull()?.let { last ->
            val plugged = app.monitor.batteryNow().plugged
            if (last.charging == plugged && System.currentTimeMillis() - last.endMillis < 6 * 3_600_000L) sessions.resume(last)
        }
        val n = StatusNotification.build(this, app.monitor.latest.value, app.prefs.current, null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(StatusNotification.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(StatusNotification.ID, n)
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        register()
        scope.launch { app.monitor.latest.filterNotNull().collect { onSample(it) } }
        scope.launch { app.prefs.settings.collect { register() } }
    }

    private fun register() {
        val interval = if (screenOn) app.prefs.current.intervalSeconds * 1000L else 60_000L
        app.monitor.register(CLIENT, interval)
    }

    private fun onSample(s: Snapshot) {
        val settings = app.prefs.current
        val fgLabel = s.foregroundPackage?.let { label(it) }
        if (s.screenOn) {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(StatusNotification.ID, StatusNotification.build(this, s, settings, fgLabel))
        }
        drain.onTick(s.timeMillis, s.screenOn, !s.battery.plugged, s.battery.powerMw, s.foregroundPackage)
        sessions.onSample(s.timeMillis, s.battery.plugged, s.battery.levelFraction, s.battery.powerMw, s.screenOn, s.battery.temperatureC)
            ?.let { finished -> runCatching { app.history.saveSession(finished) } }
        if (settings.alerts) alerts.check(s, fgLabel)
        background.onTick(s.timeMillis, s.battery.plugged)
        // Widgets every 30 s while someone can see them; launchers ignore faster updates anyway.
        if (s.screenOn && s.timeMillis - lastWidgets >= 30_000) {
            lastWidgets = s.timeMillis
            runCatching { if (WidgetUpdater.hasWidgets(this)) WidgetUpdater.updateAll(this, s) }
        }

        s.battery.powerMw?.let { powerSum += it; powerCount++ }
        val minute = s.timeMillis / 60_000
        if (minute != minuteStart) {
            minuteStart = minute
            val avg = if (powerCount > 0) powerSum / powerCount else null
            powerSum = 0.0; powerCount = 0
            runCatching {
                app.history.insert(s, avg)
                sessions.current?.let { app.history.saveSession(it) }
                val measured = drain.drain()
                if (measured.isNotEmpty()) app.history.addEnergy(ActivityPlusApp.dayKey(s.timeMillis), measured)
                if (s.timeMillis - lastPrune > 3_600_000) {
                    app.history.prune(s.timeMillis)
                    lastPrune = s.timeMillis
                }
            }
        }
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        app.monitor.unregister(CLIENT)
        // Keep what was measured since the last full minute.
        runCatching {
            val measured = drain.drain()
            if (measured.isNotEmpty()) app.history.addEnergy(ActivityPlusApp.dayKey(System.currentTimeMillis()), measured)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CLIENT = "service"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
