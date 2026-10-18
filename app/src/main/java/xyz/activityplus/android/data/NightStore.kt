package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.activityplus.android.core.NightCheck

/**
 * The screen-off spans of the night check (NightRecorder), in their own small file: one row per
 * span with its four clocks, battery at both ends and the Doze changes inside. 60 days, so the
 * usual night of two weeks is there for every night of the last month and a half.
 */
class NightStore private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spans(
                start_ts INTEGER PRIMARY KEY, end_ts INTEGER NOT NULL,
                start_elapsed INTEGER NOT NULL, end_elapsed INTEGER NOT NULL,
                start_uptime INTEGER NOT NULL, end_uptime INTEGER NOT NULL,
                start_level REAL NOT NULL, end_level REAL NOT NULL, start_mah REAL, end_mah REAL,
                plugged INTEGER NOT NULL, doze TEXT, start_signal INTEGER, end_signal INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(s: NightCheck.Span) {
        writableDatabase.insertWithOnConflict("spans", null, ContentValues().apply {
            put("start_ts", s.startMillis); put("end_ts", s.endMillis)
            put("start_elapsed", s.startElapsed); put("end_elapsed", s.endElapsed)
            put("start_uptime", s.startUptime); put("end_uptime", s.endUptime)
            put("start_level", s.startLevel); put("end_level", s.endLevel)
            put("start_mah", s.startMah); put("end_mah", s.endMah)
            put("plugged", if (s.plugged) 1 else 0); put("doze", NightCheck.encodeDoze(s.doze))
            put("start_signal", s.startSignal); put("end_signal", s.endSignal)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        _saved.value = s.endMillis
    }

    /** Spans overlapping [fromMillis]..[toMillis], oldest first. */
    fun spans(fromMillis: Long, toMillis: Long): List<NightCheck.Span> {
        val out = ArrayList<NightCheck.Span>()
        readableDatabase.rawQuery(
            "SELECT start_ts, end_ts, start_elapsed, end_elapsed, start_uptime, end_uptime, start_level, end_level, " +
                "start_mah, end_mah, plugged, doze, start_signal, end_signal FROM spans WHERE end_ts > ? AND start_ts < ? ORDER BY start_ts",
            arrayOf(fromMillis.toString(), toMillis.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += NightCheck.Span(
                    startMillis = c.getLong(0), endMillis = c.getLong(1),
                    startElapsed = c.getLong(2), endElapsed = c.getLong(3),
                    startUptime = c.getLong(4), endUptime = c.getLong(5),
                    startLevel = c.getDouble(6), endLevel = c.getDouble(7),
                    startMah = if (c.isNull(8)) null else c.getDouble(8),
                    endMah = if (c.isNull(9)) null else c.getDouble(9),
                    plugged = c.getInt(10) != 0,
                    doze = NightCheck.decodeDoze(if (c.isNull(11)) null else c.getString(11)),
                    startSignal = if (c.isNull(12)) null else c.getInt(12),
                    endSignal = if (c.isNull(13)) null else c.getInt(13),
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
        writableDatabase.delete("spans", "end_ts < ?", arrayOf((nowMillis - keepDays * 86_400_000L).toString()))
    }

    fun clear() {
        writableDatabase.delete("spans", null, null)
        writableDatabase.delete("meta", null, null)
    }

    companion object {
        const val FILE = "nights.db"
        const val KEEP_DAYS = 60
        const val META_PRUNED = "pruned_ts"

        @Volatile private var instance: NightStore? = null

        private val _saved = MutableStateFlow(0L)
        /** End of the last span saved, so an open screen shows the night as soon as the screen comes on. */
        val saved: StateFlow<Long> = _saved.asStateFlow()

        fun get(context: Context): NightStore =
            instance ?: synchronized(this) { instance ?: NightStore(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file before the first night was recorded. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
