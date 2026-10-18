package xyz.activityplus.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.ForegroundTracker
import xyz.activityplus.android.core.UpdateDetect
import xyz.activityplus.android.data.UpdateLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes down app updates for "Since the update": a scan of PackageManager when the service starts
 * and once a day, and one shortly after an app is replaced. Android delivers ACTION_PACKAGE_REPLACED
 * of other apps only to receivers registered at runtime, which is why the service registers it.
 * The same daily run keeps each finished day's mobile and Wi-Fi bytes per app, which NetworkStats
 * can answer only per range. No polling, alarm or wake lock: it runs on the service's own ticks.
 */
class UpdateRecorder(private val context: Context, private val scope: CoroutineScope) {
    /** Day of the last daily run; null after a start, so every start scans once. */
    @Volatile private var lastDay: String? = null
    @Volatile private var retryAfter = 0L
    private val pending = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Play updates many apps in a row: one scan a little later covers all of them.
            if (!pending.compareAndSet(false, true)) return
            scope.launch(Dispatchers.IO) {
                delay(SETTLE_MS)
                pending.set(false)
                runCatching { scan(this@UpdateRecorder.context) }.onFailure { Log.w(TAG, "scan after replace failed", it) }
            }
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") })
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** From every sample of the service; one date check unless a new day began. */
    fun onTick(nowMillis: Long) {
        val today = ActivityPlusApp.dayKey(nowMillis)
        if (today == lastDay || nowMillis < retryAfter) return
        lastDay = today
        scope.launch(Dispatchers.IO) {
            try {
                scan(context, nowMillis)
                recordNetwork(context, nowMillis)
            } catch (e: Exception) {
                // An hour's rest, and the day is tried again.
                retryAfter = nowMillis + 3_600_000
                lastDay = null
                Log.w(TAG, "daily update scan failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateRecorder"
        private const val SETTLE_MS = 15_000L
        /** NetworkStats keeps weeks, but the stores that are compared with it keep 30 days, and a week of "before" is enough. */
        private const val NET_DAYS = 14
        private val scanning = AtomicBoolean(false)
        private val reading = AtomicBoolean(false)
        /** The screens ask on every visit; a scan within this time is as good. */
        private const val STALE_MS = 30 * 60_000L
        @Volatile private var lastScan = 0L

        /** [scan] unless one ran lately. */
        fun scanIfStale(context: Context, nowMillis: Long = System.currentTimeMillis()) {
            if (nowMillis - lastScan >= STALE_MS) scan(context, nowMillis)
        }

        /**
         * Compares PackageManager with the last scan and writes what changed. Blocking and cheap
         * (one binder call); the app detail and the cards call it too, so the log is also current
         * while the service is off. Never two at a time.
         */
        fun scan(context: Context, nowMillis: Long = System.currentTimeMillis()) {
            if (!scanning.compareAndSet(false, true)) return
            try {
                val now = context.packageManager.getInstalledPackages(0).map {
                    UpdateDetect.Installed(it.packageName, it.versionName, it.longVersionCode, it.firstInstallTime, it.lastUpdateTime)
                }
                // An empty answer is PackageManager failing, not a phone without apps: keep what is known.
                if (now.isEmpty()) return
                val log = UpdateLog.get(context)
                log.save(UpdateDetect.detect(log.known(), now, nowMillis), now)
                val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                log.prune(nowMillis, today.minusDays(30).toString())
                lastScan = nowMillis
            } finally {
                scanning.set(false)
            }
        }

        /**
         * Reads the finished days of the last two weeks that are not stored yet, through the same
         * reader as the apps page (usage access needed). Blocking; takes seconds on the first run.
         */
        fun recordNetwork(context: Context, nowMillis: Long = System.currentTimeMillis()) {
            if (!ForegroundTracker.hasUsageAccess(context) || !reading.compareAndSet(false, true)) return
            try {
                val zone = ZoneId.systemDefault()
                val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                val log = UpdateLog.get(context)
                val first = today.minusDays(NET_DAYS.toLong())
                val done = log.netDays(first.toString(), today.toString())
                var day: LocalDate = first
                while (day < today) {
                    if (day.toString() !in done) {
                        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        val apps = ActivityPlusApp.instance.usage.read(from, to, withStorage = false)
                        // An empty answer is "no usage access after all"; do not mark the day as read.
                        if (apps.isNotEmpty()) {
                            log.saveNetDay(day.toString(), apps.filter { !it.pkg.startsWith("#") }.associate { it.pkg to (it.wifiBytes to it.mobileBytes) })
                        }
                    }
                    day = day.plusDays(1)
                }
            } finally {
                reading.set(false)
            }
        }
    }
}
