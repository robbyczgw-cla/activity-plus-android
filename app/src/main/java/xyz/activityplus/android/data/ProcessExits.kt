package xyz.activityplus.android.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import xyz.activityplus.android.core.Interruptions

/** The system's record of how our process ended. Android 11 and later. */
object ProcessExits {
    fun read(context: Context): List<Interruptions.Exit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java)
        // pid 0 asks for all processes of the package; only ours count.
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            .filter { it.processName == context.packageName }
            .map { Interruptions.Exit(it.reason, it.timestamp) }
    }
}
