package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.BatterySession
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.pro.ProReport

/**
 * 30 days of history in one small SQLite file: one row per minute, plus energy per app per day.
 * Everything stays on the phone; deleting app data removes it.
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE samples(
                ts INTEGER PRIMARY KEY,
                level REAL, power_mw REAL, temp_c REAL, charging INTEGER,
                mem_used REAL, rx_bps REAL, tx_bps REAL, clock REAL,
                thermal INTEGER, screen_on INTEGER, fg_pkg TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE app_energy(
                day TEXT NOT NULL, pkg TEXT NOT NULL,
                mwh REAL NOT NULL, seconds REAL NOT NULL,
                PRIMARY KEY(day, pkg)
            )
            """.trimIndent()
        )
        createSessions(db)
        createPro(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSessions(db)
        if (oldVersion < 3) createPro(db)
    }

    private fun createPro(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pro(ts INTEGER PRIMARY KEY, json TEXT NOT NULL)")
    }

    fun savePro(r: ProReport) {
        writableDatabase.insertWithOnConflict(
            "pro", null, ContentValues().apply { put("ts", r.timeMillis); put("json", r.toJson()) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun proReports(limit: Int = 10): List<ProReport> {
        val out = ArrayList<ProReport>()
        readableDatabase.rawQuery("SELECT json FROM pro ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) runCatching { ProReport.fromJson(c.getString(0)) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun createSessions(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions(
                start_ts INTEGER PRIMARY KEY, charging INTEGER, end_ts INTEGER,
                start_level REAL, end_level REAL, energy_mwh REAL, peak_mw REAL,
                on_s REAL, off_s REAL, max_temp REAL
            )
            """.trimIndent()
        )
    }

    /** Saves the running session too; the start time is the key, so it is overwritten each minute. */
    fun saveSession(s: BatterySession) {
        val v = ContentValues().apply {
            put("start_ts", s.startMillis); put("charging", if (s.charging) 1 else 0); put("end_ts", s.endMillis)
            put("start_level", s.startLevel); put("end_level", s.endLevel); put("energy_mwh", s.energyMwh)
            put("peak_mw", s.peakMw); put("on_s", s.screenOnSeconds); put("off_s", s.screenOffSeconds); put("max_temp", s.maxTempC)
        }
        writableDatabase.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sessions(limit: Int = 30, charging: Boolean? = null): List<BatterySession> {
        val out = ArrayList<BatterySession>()
        val where = if (charging == null) "" else "WHERE charging = ${if (charging) 1 else 0}"
        readableDatabase.rawQuery(
            "SELECT charging, start_ts, end_ts, start_level, end_level, energy_mwh, peak_mw, on_s, off_s, max_temp " +
                "FROM sessions $where ORDER BY start_ts DESC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) {
                out += BatterySession(
                    c.getInt(0) == 1, c.getLong(1), c.getLong(2), c.getDouble(3), c.getDouble(4),
                    c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9),
                )
            }
        }
        return out
    }

    /** One row per minute: [snapshot] with [avgPowerMw] averaged over that minute. */
    fun insert(snapshot: Snapshot, avgPowerMw: Double?) {
        val v = ContentValues().apply {
            put("ts", snapshot.timeMillis / 60_000 * 60_000)
            put("level", snapshot.battery.levelFraction)
            put("power_mw", avgPowerMw)
            put("temp_c", snapshot.battery.temperatureC)
            put("charging", if (snapshot.battery.charging) 1 else 0)
            put("mem_used", snapshot.memory.usedFraction)
            put("rx_bps", snapshot.network.rxBytesPerSecond)
            put("tx_bps", snapshot.network.txBytesPerSecond)
            put("clock", snapshot.cpu.clockFraction)
            put("thermal", snapshot.thermal.status.ordinal)
            put("screen_on", if (snapshot.screenOn) 1 else 0)
            put("fg_pkg", snapshot.foregroundPackage)
        }
        writableDatabase.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Adds energy to an app's total for the day. No UPSERT: Android 10 ships SQLite 3.22. */
    fun addEnergy(day: String, entries: Map<String, Pair<Double, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((pkg, value) in entries) {
                val (mwh, seconds) = value
                db.execSQL(
                    "UPDATE app_energy SET mwh = mwh + ?, seconds = seconds + ? WHERE day = ? AND pkg = ?",
                    arrayOf<Any>(mwh, seconds, day, pkg)
                )
                val changed = db.compileStatement("SELECT changes()").simpleQueryForLong()
                if (changed == 0L) {
                    db.insert("app_energy", null, ContentValues().apply {
                        put("day", day); put("pkg", pkg); put("mwh", mwh); put("seconds", seconds)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class Row(
        val ts: Long, val level: Double, val powerMw: Double?, val tempC: Double, val charging: Boolean,
        val memUsed: Double, val rxBps: Double, val txBps: Double, val clock: Double?,
        val thermal: Int, val screenOn: Boolean, val fgPkg: String?,
    )

    fun samples(fromMillis: Long, toMillis: Long = Long.MAX_VALUE): List<Row> {
        val rows = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT ts, level, power_mw, temp_c, charging, mem_used, rx_bps, tx_bps, clock, thermal, screen_on, fg_pkg " +
                "FROM samples WHERE ts >= ? AND ts <= ? ORDER BY ts",
            arrayOf(fromMillis.toString(), toMillis.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows += Row(
                    ts = c.getLong(0), level = c.getDouble(1),
                    powerMw = if (c.isNull(2)) null else c.getDouble(2),
                    tempC = c.getDouble(3), charging = c.getInt(4) == 1, memUsed = c.getDouble(5),
                    rxBps = c.getDouble(6), txBps = c.getDouble(7),
                    clock = if (c.isNull(8)) null else c.getDouble(8),
                    thermal = c.getInt(9), screenOn = c.getInt(10) == 1,
                    fgPkg = if (c.isNull(11)) null else c.getString(11),
                )
            }
        }
        return rows
    }

    data class AppEnergy(val pkg: String, val mwh: Double, val seconds: Double)

    /** Energy per app summed over the days from [fromDay] to [toDay] (yyyy-MM-dd, inclusive). */
    fun energy(fromDay: String, toDay: String): List<AppEnergy> {
        val out = ArrayList<AppEnergy>()
        readableDatabase.rawQuery(
            "SELECT pkg, SUM(mwh), SUM(seconds) FROM app_energy WHERE day >= ? AND day <= ? GROUP BY pkg ORDER BY SUM(mwh) DESC",
            arrayOf(fromDay, toDay)
        ).use { c ->
            while (c.moveToNext()) out += AppEnergy(c.getString(0), c.getDouble(1), c.getDouble(2))
        }
        return out
    }

    /** Energy per app and day, for "unusual for this app" comparisons. */
    fun energyByDay(fromDay: String): Map<String, Map<String, Double>> {
        val out = HashMap<String, HashMap<String, Double>>()
        readableDatabase.rawQuery(
            "SELECT pkg, day, mwh FROM app_energy WHERE day >= ?", arrayOf(fromDay)
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getDouble(2)
        }
        return out
    }

    fun prune(nowMillis: Long, keepDays: Int = 30) {
        val cutoff = nowMillis - keepDays * 86_400_000L
        writableDatabase.delete("samples", "ts < ?", arrayOf(cutoff.toString()))
        val cutoffDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(cutoff))
        writableDatabase.delete("app_energy", "day < ?", arrayOf(cutoffDay))
        writableDatabase.delete("sessions", "end_ts < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("pro", "ts < ?", arrayOf(cutoff.toString()))
    }

    fun clear() {
        writableDatabase.delete("samples", null, null)
        writableDatabase.delete("app_energy", null, null)
        writableDatabase.delete("sessions", null, null)
        writableDatabase.delete("pro", null, null)
    }

    fun sizeBytes(context: Context): Long = context.getDatabasePath("history.db").length()
}
