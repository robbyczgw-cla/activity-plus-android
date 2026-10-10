package xyz.activityplus.android.data

import android.content.Context
import xyz.activityplus.android.core.SpeedTest

/** The last few speed test runs, in their own preferences file. The text format lives in [SpeedTest]. */
object SpeedTestStore {
    private const val FILE = "speedtest"
    private const val KEY = "runs"

    /** Newest first. */
    fun load(context: Context): List<SpeedTest.Run> =
        prefs(context).getString(KEY, null)?.let { SpeedTest.decode(it) } ?: emptyList()

    fun add(context: Context, run: SpeedTest.Run) {
        prefs(context).edit().putString(KEY, SpeedTest.encode(SpeedTest.withRun(load(context), run))).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
