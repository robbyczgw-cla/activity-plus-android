package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.Thermal
import xyz.activityplus.android.core.ThermalStatus

/**
 * The thermal status changes of ThermalRecorder, in their own small file: a few rows a day, with
 * the app on screen and what was known at that moment. 30 days, like the history. A row with the
 * status -1 is a gap (the monitor stopped there).
 */
class ThermalStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events(
                ts INTEGER PRIMARY KEY, status INTEGER NOT NULL, pkg TEXT,
                headroom REAL, temp_c REAL, sensor TEXT, sensor_c REAL, cooling INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(e: Thermal.Event) {
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("ts", e.timeMillis); put("status", e.status?.ordinal ?: GAP)
            put("pkg", e.pkg); put("headroom", e.headroom); put("temp_c", e.batteryTempC)
            put("sensor", e.sensor); put("sensor_c", e.sensorC); put("cooling", e.coolingActive)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Adds what the computer grant read after the fact to the event at [ts]. */
    fun saveDetail(ts: Long, sensor: String?, sensorC: Double?, coolingActive: Int) {
        writableDatabase.update("events", ContentValues().apply {
            put("sensor", sensor); put("sensor_c", sensorC); put("cooling", coolingActive)
        }, "ts = ?", arrayOf(ts.toString()))
    }

    /** Events from [fromMillis] on, oldest first. */
    fun events(fromMillis: Long): List<Thermal.Event> = query("ts >= ?", arrayOf(fromMillis.toString()), "ts")

    /** The newest event, or null on a fresh file. */
    fun last(): Thermal.Event? = query(null, null, "ts DESC LIMIT 1").firstOrNull()

    /** The newest event before [ts]: the state an episode started from. */
    fun before(ts: Long): Thermal.Event? = query("ts < ?", arrayOf(ts.toString()), "ts DESC LIMIT 1").firstOrNull()

    private fun query(where: String?, args: Array<String>?, order: String): List<Thermal.Event> {
        val out = ArrayList<Thermal.Event>()
        readableDatabase.rawQuery(
            "SELECT ts, status, pkg, headroom, temp_c, sensor, sensor_c, cooling FROM events " +
                (if (where != null) "WHERE $where " else "") + "ORDER BY $order", args,
        ).use { c ->
            while (c.moveToNext()) {
                out += Thermal.Event(
                    timeMillis = c.getLong(0),
                    status = ThermalStatus.entries.getOrNull(c.getInt(1)),
                    pkg = if (c.isNull(2)) null else c.getString(2),
                    headroom = if (c.isNull(3)) null else c.getDouble(3),
                    batteryTempC = if (c.isNull(4)) null else c.getDouble(4),
                    sensor = if (c.isNull(5)) null else c.getString(5),
                    sensorC = if (c.isNull(6)) null else c.getDouble(6),
                    coolingActive = if (c.isNull(7)) null else c.getInt(7),
                )
            }
        }
        return out
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun prune(nowMillis: Long, keepDays: Int = KEEP_DAYS) {
        writableDatabase.delete("events", "ts < ?", arrayOf((nowMillis - keepDays * 86_400_000L).toString()))
    }

    fun clear() {
        writableDatabase.delete("events", null, null)
        writableDatabase.delete("meta", null, null)
    }

    companion object {
        const val FILE = "thermal.db"
        const val KEEP_DAYS = 30
        /** The last time the recorder saw an episode still running, to close it if the service dies. */
        const val META_ALIVE = "alive_ts"
        private const val GAP = -1

        @Volatile private var instance: ThermalStore? = null

        fun get(context: Context): ThermalStore =
            instance ?: synchronized(this) { instance ?: ThermalStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file before anything was recorded. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
