package xyz.activityplus.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.activityplus.android.ActivityPlusApp

/** Starts measuring again after a restart or an app update, if the user turned it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = ActivityPlusApp.instance.prefs.current
                if (prefs.live && prefs.onboarded) runCatching { MonitorService.start(context) }
            }
        }
    }
}
