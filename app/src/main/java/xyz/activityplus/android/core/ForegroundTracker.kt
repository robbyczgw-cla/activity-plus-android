package xyz.activityplus.android.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Which app is on screen, from the usage event stream. Reads only the events since the last call,
 * so it is cheap enough for every tick. Needs usage access; returns null without it.
 */
class ForegroundTracker(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L
    private var current: String? = null
    private var currentClass: String? = null

    fun current(): String? {
        if (!hasUsageAccess(context)) return null
        val now = System.currentTimeMillis()
        // First call: look back far enough to find the app that was opened before we started.
        val from = if (lastQuery == 0L) now - 6 * 3_600_000L else lastQuery
        val events = usage.queryEvents(from, now) ?: return current
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    current = event.packageName
                    currentClass = event.className
                }
                // Only the activity that is open ends the session; apps switch activities internally.
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (event.packageName == current && event.className == currentClass) current = null
            }
        }
        // Events are delivered with a delay of up to a second; overlap so none are lost.
        lastQuery = now - 2_000
        return current
    }

    companion object {
        @Suppress("DEPRECATION") // Deprecated in SDK 36, still works on every version we support.
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
