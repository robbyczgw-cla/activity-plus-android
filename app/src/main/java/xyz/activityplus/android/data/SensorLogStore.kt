package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.SensorLog
import xyz.activityplus.android.pro.AppOpsParser

/**
 * Every access to location, camera, microphone and the like that the app-ops report ever showed,
 * in its own small file. Android keeps only the latest access per op, so the history beyond that
 * exists only because the service reads the report every half hour (SensorLogSampler). 30 days.
 * Created only once the log has run.
 */
class SensorLogStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // The same access shows up in every report until a newer one replaces it; the key keeps one row.
        db.execSQL(
            """
            CREATE TABLE events(
                pkg TEXT NOT NULL, op TEXT NOT NULL, ts INTEGER NOT NULL, rejected INTEGER NOT NULL,
                state TEXT NOT NULL, flags TEXT NOT NULL, duration_ms INTEGER NOT NULL,
                PRIMARY KEY(pkg, op, ts, rejected)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Books the accesses of one report, skipping those older than the 30 days and the proxy side of
     * accesses made for another app. A running access grows between reports, so a longer duration
     * replaces the stored one. Returns how many accesses are new.
     */
    fun save(report: AppOpsParser.Report, nowMillis: Long): Int {
        val cutoff = nowMillis - KEEP_MS
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for ((pkg, e) in report.events()) {
                if (e.forOthers || e.timeMillis < cutoff) continue
                val rejected = if (e.rejected) 1 else 0
                val inserted = db.insertWithOnConflict("events", null, ContentValues().apply {
                    put("pkg", pkg); put("op", e.op.name); put("ts", e.timeMillis); put("rejected", rejected)
                    put("state", e.where.name); put("flags", e.flags); put("duration_ms", e.durationMs ?: 0L)
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if (inserted) added++
                else if (e.durationMs != null) {
                    db.execSQL(
                        "UPDATE events SET duration_ms = ? WHERE pkg = ? AND op = ? AND ts = ? AND rejected = ? AND duration_ms < ?",
                        arrayOf<Any>(e.durationMs, pkg, e.op.name, e.timeMillis, rejected, e.durationMs),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** The accesses with a time in [fromMillis]..[toMillis], oldest first. */
    fun accesses(fromMillis: Long, toMillis: Long): List<SensorLog.Access> {
        val out = ArrayList<SensorLog.Access>()
        readableDatabase.rawQuery(
            "SELECT pkg, op, ts, rejected, state, flags, duration_ms FROM events WHERE ts >= ? AND ts <= ? ORDER BY ts",
            arrayOf(fromMillis.toString(), toMillis.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val op = AppOpsParser.Op.entries.find { it.name == c.getString(1) } ?: continue
                val where = AppOpsParser.Where.entries.find { it.name == c.getString(4) } ?: AppOpsParser.Where.UNKNOWN
                out += SensorLog.Access(
                    c.getString(0),
                    AppOpsParser.Event(op, c.getLong(2), where, c.getString(5), c.getLong(6).takeIf { it > 0 }, running = false, rejected = c.getInt(3) == 1),
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

    fun prune(nowMillis: Long) {
        writableDatabase.delete("events", "ts < ?", arrayOf((nowMillis - KEEP_MS).toString()))
    }

    companion object {
        const val FILE = "sensors.db"
        const val KEEP_MS = 30 * 86_400_000L
        /** When the first report was stored: before that, the log holds only what Android still remembered. */
        const val META_SINCE = "since_ts"
        const val META_PRUNED = "pruned_ts"

        @Volatile private var instance: SensorLogStore? = null

        fun get(context: Context): SensorLogStore =
            instance ?: synchronized(this) { instance ?: SensorLogStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file when the log never ran. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
