package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.CapacityEstimate
import xyz.activityplus.android.core.ChargerMeasurement

/** A saved charger test: the measurement plus the name the user gave it. */
data class SavedChargerTest(val id: Long, val name: String, val m: ChargerMeasurement)

/**
 * Charger tests and the full-capacity estimates of finished charges, in their own small file
 * (not history.db, which prunes after 30 days: the capacity trend needs months).
 */
class ChargingStore private constructor(context: Context) : SQLiteOpenHelper(context, "charging.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE charger_tests(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, name TEXT NOT NULL, seconds INTEGER NOT NULL,
                avg_mw REAL NOT NULL, peak_mw REAL NOT NULL, avg_v REAL NOT NULL, avg_ma REAL NOT NULL,
                start_level REAL NOT NULL, start_temp REAL NOT NULL
            )
            """.trimIndent()
        )
        // One estimate per charge session; the end time is the key, so a repeat overwrites.
        db.execSQL(
            "CREATE TABLE capacity(ts INTEGER PRIMARY KEY, mah REAL NOT NULL, level_from REAL NOT NULL, level_to REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveTest(name: String, m: ChargerMeasurement) {
        writableDatabase.insert("charger_tests", null, ContentValues().apply {
            put("ts", m.timeMillis); put("name", name.trim()); put("seconds", m.seconds)
            put("avg_mw", m.avgMw); put("peak_mw", m.peakMw); put("avg_v", m.avgVoltageV); put("avg_ma", m.avgCurrentMa)
            put("start_level", m.startLevel); put("start_temp", m.startTempC)
        })
    }

    /** Best charger first. */
    fun tests(): List<SavedChargerTest> {
        val out = ArrayList<SavedChargerTest>()
        readableDatabase.rawQuery(
            "SELECT id, ts, name, seconds, avg_mw, peak_mw, avg_v, avg_ma, start_level, start_temp FROM charger_tests ORDER BY avg_mw DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += SavedChargerTest(
                    c.getLong(0), c.getString(2),
                    ChargerMeasurement(c.getLong(1), c.getInt(3), c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9)),
                )
            }
        }
        return out
    }

    fun deleteTest(id: Long) {
        writableDatabase.delete("charger_tests", "id = ?", arrayOf(id.toString()))
    }

    fun saveCapacity(e: CapacityEstimate) {
        writableDatabase.insertWithOnConflict("capacity", null, ContentValues().apply {
            put("ts", e.timeMillis); put("mah", e.mah); put("level_from", e.levelFrom); put("level_to", e.levelTo)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Oldest first. */
    fun capacity(): List<CapacityEstimate> {
        val out = ArrayList<CapacityEstimate>()
        readableDatabase.rawQuery("SELECT ts, mah, level_from, level_to FROM capacity ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out += CapacityEstimate(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
        }
        return out
    }

    companion object {
        @Volatile private var instance: ChargingStore? = null

        fun get(context: Context): ChargingStore =
            instance ?: synchronized(this) { instance ?: ChargingStore(context.applicationContext).also { instance = it } }
    }
}
