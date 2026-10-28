package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.SelfCost
import xyz.activityplus.android.core.SelfCost.Counters
import xyz.activityplus.android.pro.BackgroundSchedule
import java.time.ZoneId

/**
 * What Activity+ costs itself (SelfCostSampler), in its own small file: the last reading to subtract
 * from and the sum per day. Created with the first reading; 30 days, like the history.
 */
class SelfCostStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE last(id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, battery_ms INTEGER, $COLUMN_DEFS)")
        db.execSQL("CREATE TABLE days(day TEXT PRIMARY KEY, $COLUMN_DEFS)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun lastReading(): SelfCost.Reading? =
        readableDatabase.rawQuery("SELECT ts, battery_ms, $COLUMN_LIST FROM last WHERE id = 0", null).use { c ->
            if (!c.moveToFirst()) null
            else SelfCost.Reading(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), counters(c, 2))
        }

    /** Keeps [reading] as the new base and books [interval] per day, in one transaction. */
    fun save(reading: SelfCost.Reading, interval: SelfCost.Interval?, zone: ZoneId) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("last", null, values(reading.counters).apply {
                put("id", 0); put("ts", reading.timeMillis); put("battery_ms", reading.batteryMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (interval != null) for ((day, c) in SelfCost.byDay(interval, zone)) addDay(db, day, c)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** No UPSERT: Android 10 ships SQLite 3.22. */
    private fun addDay(db: SQLiteDatabase, day: String, c: Counters) {
        db.execSQL(
            "UPDATE days SET " + COLUMNS.joinToString { "$it = $it + ?" } + " WHERE day = ?",
            arrayOf<Any>(*c.toArray().toTypedArray(), day),
        )
        if (db.compileStatement("SELECT changes()").use { it.simpleQueryForLong() } == 0L) {
            db.insert("days", null, values(c).apply { put("day", day) })
        }
    }

    /** Per day over [fromDay]..[toDay]; days without a row were not measured. */
    fun series(fromDay: String, toDay: String): Map<String, Counters> {
        val out = HashMap<String, Counters>()
        readableDatabase.rawQuery("SELECT day, $COLUMN_LIST FROM days WHERE day >= ? AND day <= ?", arrayOf(fromDay, toDay))
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = counters(c, 1) }
        return out
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun prune(nowMillis: Long, zone: ZoneId, keepDays: Int = 30) {
        writableDatabase.delete("days", "day < ?", arrayOf(BackgroundSchedule.dayKey(nowMillis - keepDays * 86_400_000L, zone)))
    }

    fun clear() {
        val db = writableDatabase
        for (t in listOf("last", "days", "meta")) db.delete(t, null, null)
    }

    private fun values(c: Counters) = ContentValues().apply {
        val a = c.toArray()
        COLUMNS.forEachIndexed { i, name -> put(name, a[i]) }
    }

    private fun counters(c: android.database.Cursor, at: Int) = Counters.of(LongArray(Counters.FIELDS) { c.getLong(at + it) })

    companion object {
        const val FILE = "selfcost.db"
        const val META_PRUNED = "pruned_ts"

        /** In the order of Counters' fields, so [counters] can read them by position. */
        private val COLUMNS = listOf("cpu_ms", "wl_ms", "wl_count", "wakeups", "jobs", "data_bytes", "service_ms")
        private val COLUMN_LIST = COLUMNS.joinToString()
        private val COLUMN_DEFS = COLUMNS.joinToString { "$it INTEGER NOT NULL" }

        @Volatile private var instance: SelfCostStore? = null

        fun get(context: Context): SelfCostStore =
            instance ?: synchronized(this) { instance ?: SelfCostStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file when nothing was read yet. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
