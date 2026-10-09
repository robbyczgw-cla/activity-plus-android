package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.pro.BackgroundDelta
import java.time.ZoneId

/**
 * Background battery per app from Android's own report, in its own small file: the last raw
 * reading to subtract from, each interval between two readings, and the sum per app per day.
 * Created only once the automatic measurement has run; 30 days, like the history.
 */
class BackgroundStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // One row: the last reading, per uid as BackgroundDelta.encode text.
        db.execSQL("CREATE TABLE last(id INTEGER PRIMARY KEY, ts INTEGER, on_battery_ms INTEGER, start_clock INTEGER, hw_mah REAL, uids TEXT)")
        db.execSQL(
            """
            CREATE TABLE days(
                day TEXT NOT NULL, pkg TEXT NOT NULL,
                mah REAL NOT NULL, cpu_ms INTEGER NOT NULL, wakelock_ms INTEGER NOT NULL,
                PRIMARY KEY(day, pkg)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE intervals(to_ts INTEGER PRIMARY KEY, from_ts INTEGER, reset INTEGER, hw_mah REAL)")
        db.execSQL(
            """
            CREATE TABLE interval_apps(
                to_ts INTEGER NOT NULL, pkg TEXT NOT NULL,
                mah REAL NOT NULL, cpu_ms INTEGER NOT NULL, wakelock_ms INTEGER NOT NULL,
                PRIMARY KEY(to_ts, pkg)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class AppDay(val pkg: String, val mah: Double, val cpuMs: Long, val wakelockMs: Long)

    /** Several intervals summed, e.g. last night. */
    data class Span(val fromMillis: Long, val toMillis: Long, val apps: List<AppDay>, val hardwareMah: Double) {
        val totalMah get() = apps.sumOf { it.mah } + hardwareMah
    }

    fun lastSnapshot(): BackgroundDelta.Snapshot? =
        readableDatabase.rawQuery("SELECT ts, on_battery_ms, start_clock, hw_mah, uids FROM last WHERE id = 0", null).use { c ->
            if (!c.moveToFirst()) null
            else BackgroundDelta.Snapshot(
                timeMillis = c.getLong(0),
                onBatteryMs = if (c.isNull(1)) null else c.getLong(1),
                startClockMillis = if (c.isNull(2)) null else c.getLong(2),
                byUid = BackgroundDelta.decode(c.getString(4) ?: ""),
                hardwareMah = c.getDouble(3),
            )
        }

    /** Keeps [snapshot] as the new base and books [interval] per day, all in one transaction. */
    fun save(snapshot: BackgroundDelta.Snapshot, interval: BackgroundDelta.Interval?, zone: ZoneId) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("last", null, ContentValues().apply {
                put("id", 0); put("ts", snapshot.timeMillis); put("on_battery_ms", snapshot.onBatteryMs)
                put("start_clock", snapshot.startClockMillis); put("hw_mah", snapshot.hardwareMah)
                put("uids", BackgroundDelta.encode(snapshot.byUid))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (interval != null) {
                db.insertWithOnConflict("intervals", null, ContentValues().apply {
                    put("to_ts", interval.toMillis); put("from_ts", interval.fromMillis)
                    put("reset", if (interval.reset) 1 else 0); put("hw_mah", interval.hardwareMah)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // Per interval only rows worth showing; the day sums below keep everything.
                for (a in interval.apps.filter { it.mah >= 0.05 || it.wakelockMs >= 60_000 }) {
                    db.insertWithOnConflict("interval_apps", null, ContentValues().apply {
                        put("to_ts", interval.toMillis); put("pkg", a.pkg)
                        put("mah", a.mah); put("cpu_ms", a.cpuMs); put("wakelock_ms", a.wakelockMs)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                for ((day, share) in BackgroundDelta.splitByDay(interval.fromMillis, interval.toMillis, zone)) {
                    for (a in interval.apps) addDay(db, day, a.scaled(share))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** No UPSERT: Android 10 ships SQLite 3.22. */
    private fun addDay(db: SQLiteDatabase, day: String, a: BackgroundDelta.Usage) {
        db.execSQL(
            "UPDATE days SET mah = mah + ?, cpu_ms = cpu_ms + ?, wakelock_ms = wakelock_ms + ? WHERE day = ? AND pkg = ?",
            arrayOf<Any>(a.mah, a.cpuMs, a.wakelockMs, day, a.pkg),
        )
        if (db.compileStatement("SELECT changes()").simpleQueryForLong() == 0L) {
            db.insert("days", null, ContentValues().apply {
                put("day", day); put("pkg", a.pkg); put("mah", a.mah); put("cpu_ms", a.cpuMs); put("wakelock_ms", a.wakelockMs)
            })
        }
    }

    /** Per app for one day (yyyy-MM-dd), most first. */
    fun day(day: String): List<AppDay> = range(day, day)

    /** Per app summed over [fromDay]..[toDay] inclusive, most first. */
    fun range(fromDay: String, toDay: String): List<AppDay> {
        val out = ArrayList<AppDay>()
        readableDatabase.rawQuery(
            "SELECT pkg, SUM(mah), SUM(cpu_ms), SUM(wakelock_ms) FROM days WHERE day >= ? AND day <= ? GROUP BY pkg ORDER BY SUM(mah) DESC",
            arrayOf(fromDay, toDay),
        ).use { c -> while (c.moveToNext()) out += AppDay(c.getString(0), c.getDouble(1), c.getLong(2), c.getLong(3)) }
        return out
    }

    /** One app's mAh per day; days without a row are missing. */
    fun series(pkg: String, fromDay: String, toDay: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        readableDatabase.rawQuery(
            "SELECT day, mah FROM days WHERE pkg = ? AND day >= ? AND day <= ?", arrayOf(pkg, fromDay, toDay),
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getDouble(1) }
        return out
    }

    /** Days with any reading at all, to tell "app used nothing" from "not measured". */
    fun measuredDays(fromDay: String, toDay: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT day FROM days WHERE day >= ? AND day <= ?", arrayOf(fromDay, toDay))
            .use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** The intervals that ended in ([afterMillis], [toMillis]], summed; null if there are none. */
    fun span(afterMillis: Long, toMillis: Long): Span? {
        val args = arrayOf(afterMillis.toString(), toMillis.toString())
        val db = readableDatabase
        val (from, to, hw) = db.rawQuery(
            "SELECT MIN(from_ts), MAX(to_ts), SUM(hw_mah), COUNT(*) FROM intervals WHERE to_ts > ? AND to_ts <= ?", args,
        ).use { c ->
            if (!c.moveToFirst() || c.getLong(3) == 0L) return null
            Triple(c.getLong(0), c.getLong(1), c.getDouble(2))
        }
        val apps = ArrayList<AppDay>()
        db.rawQuery(
            "SELECT pkg, SUM(mah), SUM(cpu_ms), SUM(wakelock_ms) FROM interval_apps WHERE to_ts > ? AND to_ts <= ? GROUP BY pkg ORDER BY SUM(mah) DESC",
            args,
        ).use { c -> while (c.moveToNext()) apps += AppDay(c.getString(0), c.getDouble(1), c.getLong(2), c.getLong(3)) }
        return Span(from, to, apps, hw)
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun prune(nowMillis: Long, zone: ZoneId, keepDays: Int = 30) {
        val cutoff = nowMillis - keepDays * 86_400_000L
        val db = writableDatabase
        db.delete("days", "day < ?", arrayOf(xyz.activityplus.android.pro.BackgroundSchedule.dayKey(cutoff, zone)))
        db.delete("intervals", "to_ts < ?", arrayOf(cutoff.toString()))
        db.delete("interval_apps", "to_ts < ?", arrayOf(cutoff.toString()))
    }

    fun clear() {
        val db = writableDatabase
        for (t in listOf("last", "days", "intervals", "interval_apps", "meta")) db.delete(t, null, null)
    }

    companion object {
        const val FILE = "background.db"
        const val META_MORNING_DAY = "morning_day"
        const val META_MORNING_TS = "morning_ts"
        const val META_UNPLUGGED = "unplugged_ts"
        const val META_PRUNED = "pruned_ts"

        @Volatile private var instance: BackgroundStore? = null

        fun get(context: Context): BackgroundStore =
            instance ?: synchronized(this) { instance ?: BackgroundStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file when the feature never ran. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
