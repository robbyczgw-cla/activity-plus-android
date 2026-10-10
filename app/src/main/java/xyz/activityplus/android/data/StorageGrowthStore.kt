package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.AppStorageSize
import xyz.activityplus.android.core.AppStorageSnapshot
import xyz.activityplus.android.core.DeviceStorageDay
import java.time.LocalDate

/**
 * One storage snapshot per day: the device's total and free bytes, and every app's size when usage
 * access allows it. Its own file with 90 days, because growth needs a longer view than history.db's 30.
 */
class StorageGrowthStore private constructor(context: Context) : SQLiteOpenHelper(context, "storage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // Days as ISO dates (2026-10-10); a second snapshot on the same day replaces the first.
        db.execSQL("CREATE TABLE device(day TEXT PRIMARY KEY, ts INTEGER NOT NULL, total INTEGER NOT NULL, free INTEGER NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE apps(
                day TEXT NOT NULL, pkg TEXT NOT NULL, ts INTEGER NOT NULL,
                app INTEGER NOT NULL, data INTEGER NOT NULL, cache INTEGER NOT NULL,
                PRIMARY KEY(day, pkg)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** [apps] is null without usage access: then only the device row is written. */
    fun save(day: LocalDate, timeMillis: Long, totalBytes: Long, freeBytes: Long, apps: Map<String, AppStorageSize>?) {
        val db = writableDatabase
        val key = day.toString()
        db.beginTransaction()
        try {
            db.insertWithOnConflict("device", null, ContentValues().apply {
                put("day", key); put("ts", timeMillis); put("total", totalBytes); put("free", freeBytes)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (apps != null) {
                db.delete("apps", "day = ?", arrayOf(key))
                val v = ContentValues()
                for ((pkg, s) in apps) {
                    v.clear()
                    v.put("day", key); v.put("pkg", pkg); v.put("ts", timeMillis)
                    v.put("app", s.appBytes); v.put("data", s.dataBytes); v.put("cache", s.cacheBytes)
                    db.insertWithOnConflict("apps", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Drops everything before [firstKept]. */
    fun prune(firstKept: LocalDate) {
        val key = firstKept.toString()
        writableDatabase.delete("device", "day < ?", arrayOf(key))
        writableDatabase.delete("apps", "day < ?", arrayOf(key))
    }

    fun lastDay(): LocalDate? =
        readableDatabase.rawQuery("SELECT MAX(day) FROM device", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) runCatching { LocalDate.parse(c.getString(0)) }.getOrNull() else null
        }

    /** From [from] on, oldest first. */
    fun device(from: LocalDate): List<DeviceStorageDay> {
        val out = ArrayList<DeviceStorageDay>()
        readableDatabase.rawQuery("SELECT day, ts, total, free FROM device WHERE day >= ? ORDER BY day", arrayOf(from.toString())).use { c ->
            while (c.moveToNext()) {
                val day = runCatching { LocalDate.parse(c.getString(0)) }.getOrNull() ?: continue
                out += DeviceStorageDay(day, c.getLong(1), c.getLong(2), c.getLong(3))
            }
        }
        return out
    }

    /** One snapshot per day with app rows, from [from] on, oldest first. */
    fun apps(from: LocalDate): List<AppStorageSnapshot> {
        val days = LinkedHashMap<String, Pair<Long, HashMap<String, AppStorageSize>>>()
        readableDatabase.rawQuery(
            "SELECT day, ts, pkg, app, data, cache FROM apps WHERE day >= ? ORDER BY day", arrayOf(from.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val entry = days.getOrPut(c.getString(0)) { c.getLong(1) to HashMap() }
                entry.second[c.getString(2)] = AppStorageSize(c.getLong(3), c.getLong(4), c.getLong(5))
            }
        }
        return days.mapNotNull { (key, v) ->
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { AppStorageSnapshot(it, v.first, v.second) }
        }
    }

    companion object {
        @Volatile private var instance: StorageGrowthStore? = null

        fun get(context: Context): StorageGrowthStore =
            instance ?: synchronized(this) { instance ?: StorageGrowthStore(context.applicationContext).also { instance = it } }
    }
}
