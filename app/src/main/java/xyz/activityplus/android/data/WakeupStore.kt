package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.core.Wakeups.AppCounters
import xyz.activityplus.android.core.Wakeups.Counters
import xyz.activityplus.android.pro.BackgroundDelta
import xyz.activityplus.android.pro.BackgroundSchedule
import java.time.ZoneId

/**
 * Wakeups, wakelocks, CPU and jobs per app from Android's counters (WakeupSampler), in their own
 * file: the last reading to subtract from, each interval between readings (for the night), and the
 * sum per app per day. Created only once the counters were readable; days 30, intervals 8 days.
 */
class WakeupStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // One row: the last reading, per uid as Wakeups.encode text.
        db.execSQL("CREATE TABLE last(id INTEGER PRIMARY KEY, ts INTEGER, battery_ms INTEGER, uids TEXT)")
        db.execSQL("CREATE TABLE days(day TEXT NOT NULL, pkg TEXT NOT NULL, $COLUMN_DEFS, PRIMARY KEY(day, pkg))")
        db.execSQL("CREATE TABLE intervals(to_ts INTEGER PRIMARY KEY, from_ts INTEGER NOT NULL, reset INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE interval_apps(to_ts INTEGER NOT NULL, pkg TEXT NOT NULL, $COLUMN_DEFS, PRIMARY KEY(to_ts, pkg))")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun lastSnapshot(): Wakeups.Snapshot? =
        readableDatabase.rawQuery("SELECT ts, battery_ms, uids FROM last WHERE id = 0", null).use { c ->
            if (!c.moveToFirst()) null
            else Wakeups.Snapshot(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), Wakeups.decode(c.getString(2) ?: ""))
        }

    /** Keeps [snapshot] as the new base and books [interval] per interval and per day, in one transaction. */
    fun save(snapshot: Wakeups.Snapshot, interval: Wakeups.Interval?, zone: ZoneId) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("last", null, ContentValues().apply {
                put("id", 0); put("ts", snapshot.timeMillis); put("battery_ms", snapshot.batteryMs)
                put("uids", Wakeups.encode(snapshot.byUid))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (interval != null) {
                db.insertWithOnConflict("intervals", null, ContentValues().apply {
                    put("to_ts", interval.toMillis); put("from_ts", interval.fromMillis); put("reset", if (interval.reset) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // Per interval only rows worth showing; the day sums below keep everything.
                for (a in interval.apps.filter { Wakeups.worthShowing(it.counters) }) {
                    db.insertWithOnConflict("interval_apps", null, values(a.counters).apply {
                        put("to_ts", interval.toMillis); put("pkg", a.pkg)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                for ((day, apps) in Wakeups.byDay(interval, zone)) for (a in apps) addDay(db, day, a)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** No UPSERT: Android 10 ships SQLite 3.22. */
    private fun addDay(db: SQLiteDatabase, day: String, a: AppCounters) {
        db.execSQL(
            "UPDATE days SET " + COLUMNS.joinToString { "$it = $it + ?" } + " WHERE day = ? AND pkg = ?",
            arrayOf<Any>(*a.counters.toArray().toTypedArray(), day, a.pkg),
        )
        if (db.compileStatement("SELECT changes()").use { it.simpleQueryForLong() } == 0L) {
            db.insert("days", null, values(a.counters).apply { put("day", day); put("pkg", a.pkg) })
        }
    }

    /** Per app for one day (yyyy-MM-dd). */
    fun day(day: String): List<AppCounters> {
        val out = ArrayList<AppCounters>()
        readableDatabase.rawQuery("SELECT pkg, $COLUMN_LIST FROM days WHERE day = ?", arrayOf(day))
            .use { c -> while (c.moveToNext()) out += AppCounters(c.getString(0), counters(c, 1)) }
        return out
    }

    /** One app per day over [fromDay]..[toDay]; days without a row are missing. */
    fun series(pkg: String, fromDay: String, toDay: String): Map<String, Counters> {
        val out = HashMap<String, Counters>()
        readableDatabase.rawQuery(
            "SELECT day, $COLUMN_LIST FROM days WHERE pkg = ? AND day >= ? AND day <= ?", arrayOf(pkg, fromDay, toDay),
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = counters(c, 1) }
        return out
    }

    /** Every app per day over [fromDay]..[toDay], for the "usual day" of all apps at once. */
    fun allSeries(fromDay: String, toDay: String): Map<String, Map<String, Counters>> {
        val out = HashMap<String, HashMap<String, Counters>>()
        readableDatabase.rawQuery(
            "SELECT pkg, day, $COLUMN_LIST FROM days WHERE day >= ? AND day <= ?", arrayOf(fromDay, toDay),
        ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = counters(c, 2) }
        return out
    }

    /**
     * Days with any interval, to tell "app did nothing" from "not measured". From the intervals,
     * so it only reaches 8 days back; that covers the week the detail shows.
     */
    fun measuredDays(zone: ZoneId): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT from_ts, to_ts FROM intervals", null).use { c ->
            while (c.moveToNext()) {
                BackgroundDelta.splitByDay(c.getLong(0), c.getLong(1), zone).forEach { out += it.first }
            }
        }
        return out
    }

    /** The intervals overlapping [fromMillis]..[toMillis], with their apps. */
    fun intervals(fromMillis: Long, toMillis: Long): List<Wakeups.Interval> {
        val db = readableDatabase
        val args = arrayOf(fromMillis.toString(), toMillis.toString())
        val apps = HashMap<Long, MutableList<AppCounters>>()
        db.rawQuery(
            "SELECT a.to_ts, a.pkg, " + COLUMNS.joinToString { "a.$it" } +
                " FROM interval_apps a JOIN intervals i ON i.to_ts = a.to_ts WHERE i.to_ts > ? AND i.from_ts < ?",
            args,
        ).use { c -> while (c.moveToNext()) apps.getOrPut(c.getLong(0)) { ArrayList() } += AppCounters(c.getString(1), counters(c, 2)) }
        val out = ArrayList<Wakeups.Interval>()
        db.rawQuery("SELECT from_ts, to_ts, reset FROM intervals WHERE to_ts > ? AND from_ts < ? ORDER BY to_ts", args).use { c ->
            while (c.moveToNext()) out += Wakeups.Interval(c.getLong(0), c.getLong(1), c.getInt(2) != 0, apps[c.getLong(1)].orEmpty())
        }
        return out
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun prune(nowMillis: Long, zone: ZoneId, keepDays: Int = 30, keepIntervalDays: Int = 8) {
        val db = writableDatabase
        db.delete("days", "day < ?", arrayOf(BackgroundSchedule.dayKey(nowMillis - keepDays * 86_400_000L, zone)))
        // Intervals only serve the night and the measured days of the last week.
        val cutoff = (nowMillis - keepIntervalDays * 86_400_000L).toString()
        db.delete("intervals", "to_ts < ?", arrayOf(cutoff))
        db.delete("interval_apps", "to_ts < ?", arrayOf(cutoff))
    }

    fun clear() {
        val db = writableDatabase
        for (t in listOf("last", "days", "intervals", "interval_apps", "meta")) db.delete(t, null, null)
    }

    private fun values(c: Counters) = ContentValues().apply {
        put("cpu_ms", c.cpuMs); put("wl_ms", c.wakelockMs); put("wl_count", c.wakelocks); put("wakeups", c.wakeups)
        put("jobs", c.jobs); put("job_ms", c.jobMs); put("syncs", c.syncs); put("gps_ms", c.gpsMs)
    }

    private fun counters(c: Cursor, at: Int) = Counters.of(LongArray(Counters.FIELDS) { c.getLong(at + it) })

    companion object {
        const val FILE = "wakeups.db"
        const val META_MORNING_DAY = "morning_day"
        const val META_MORNING_TS = "morning_ts"
        const val META_PRUNED = "pruned_ts"

        /** In the order of Counters' fields, so [counters] can read them by position. */
        private val COLUMNS = listOf("cpu_ms", "wl_ms", "wl_count", "wakeups", "jobs", "job_ms", "syncs", "gps_ms")
        private val COLUMN_LIST = COLUMNS.joinToString()
        private val COLUMN_DEFS = COLUMNS.joinToString { "$it INTEGER NOT NULL" }

        @Volatile private var instance: WakeupStore? = null

        fun get(context: Context): WakeupStore =
            instance ?: synchronized(this) { instance ?: WakeupStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file when the counters were never read. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
