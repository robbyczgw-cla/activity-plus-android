package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.UpdateDetect.Event
import xyz.activityplus.android.core.UpdateDetect.Installed

/**
 * App updates, in their own file: Android keeps only the latest version of an app, so every
 * version change is written down when it is seen. Also the last scan of PackageManager (to see
 * what changed next time) and mobile and Wi-Fi bytes per app per day, which NetworkStats only
 * hands out per range. 90 days for the updates, 30 for the bytes, like the stores they join.
 */
class UpdateLog private constructor(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE updates(
                pkg TEXT NOT NULL, ts INTEGER NOT NULL,
                old_name TEXT, old_code INTEGER, new_name TEXT, new_code INTEGER NOT NULL,
                PRIMARY KEY(pkg, ts)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE known(
                pkg TEXT PRIMARY KEY, version_name TEXT, version_code INTEGER NOT NULL,
                first_install INTEGER NOT NULL, last_update INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // A day with a row here was read, so an app without a row in net that day used nothing.
        db.execSQL("CREATE TABLE net_days(day TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE net(day TEXT NOT NULL, pkg TEXT NOT NULL, wifi INTEGER NOT NULL, mobile INTEGER NOT NULL, PRIMARY KEY(day, pkg))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** What the last scan saw, by package. Empty before the first scan. */
    fun known(): Map<String, Installed> {
        val out = HashMap<String, Installed>()
        readableDatabase.rawQuery("SELECT pkg, version_name, version_code, first_install, last_update FROM known", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Installed(
                    c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4),
                )
            }
        }
        return out
    }

    /** Writes [events] and makes [now] the new base, in one transaction; packages that are gone are forgotten. */
    fun save(events: List<Event>, now: List<Installed>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in events) {
                // The same update seen twice (a scan racing the receiver) is one row.
                db.insertWithOnConflict("updates", null, ContentValues().apply {
                    put("pkg", e.pkg); put("ts", e.timeMillis)
                    put("old_name", e.oldName); put("old_code", e.oldCode)
                    put("new_name", e.newName); put("new_code", e.newCode)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.delete("known", null, null)
            for (n in now) {
                db.insert("known", null, ContentValues().apply {
                    put("pkg", n.pkg); put("version_name", n.versionName); put("version_code", n.versionCode)
                    put("first_install", n.firstInstallMillis); put("last_update", n.lastUpdateMillis)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Updates from [sinceMillis] on, newest first. */
    fun events(sinceMillis: Long): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT pkg, old_name, old_code, new_name, new_code, ts FROM updates WHERE ts >= ? ORDER BY ts DESC",
            arrayOf(sinceMillis.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Event(
                    c.getString(0), if (c.isNull(1)) null else c.getString(1), if (c.isNull(2)) null else c.getLong(2),
                    if (c.isNull(3)) null else c.getString(3), c.getLong(4), c.getLong(5),
                )
            }
        }
        return out
    }

    /** Wi-Fi and mobile bytes of one finished day per package; apps that used nothing get no row. */
    fun saveNetDay(day: String, bytes: Map<String, Pair<Long, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("net", "day = ?", arrayOf(day))
            for ((pkg, b) in bytes) {
                if (b.first + b.second <= 0) continue
                db.insert("net", null, ContentValues().apply { put("day", day); put("pkg", pkg); put("wifi", b.first); put("mobile", b.second) })
            }
            db.insertWithOnConflict("net_days", null, ContentValues().apply { put("day", day) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The days already read, in [fromDay]..[toDay] (yyyy-MM-dd). */
    fun netDays(fromDay: String, toDay: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT day FROM net_days WHERE day >= ? AND day <= ?", arrayOf(fromDay, toDay))
            .use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** Per package and day over [fromDay]..[toDay]: Wi-Fi to mobile bytes. Days without a row are missing. */
    fun netByDay(fromDay: String, toDay: String): Map<String, Map<String, Pair<Long, Long>>> {
        val out = HashMap<String, HashMap<String, Pair<Long, Long>>>()
        readableDatabase.rawQuery(
            "SELECT pkg, day, wifi, mobile FROM net WHERE day >= ? AND day <= ?", arrayOf(fromDay, toDay),
        ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getLong(2) to c.getLong(3) }
        return out
    }

    fun prune(nowMillis: Long, firstNetDay: String, keepDays: Int = 90) {
        val db = writableDatabase
        db.delete("updates", "ts < ?", arrayOf((nowMillis - keepDays * 86_400_000L).toString()))
        db.delete("net", "day < ?", arrayOf(firstNetDay))
        db.delete("net_days", "day < ?", arrayOf(firstNetDay))
    }

    fun clear() {
        val db = writableDatabase
        for (t in listOf("updates", "known", "net", "net_days")) db.delete(t, null, null)
    }

    companion object {
        const val FILE = "updates.db"

        @Volatile private var instance: UpdateLog? = null

        fun get(context: Context): UpdateLog =
            instance ?: synchronized(this) { instance ?: UpdateLog(context.applicationContext).also { instance = it } }

        /** Without opening (and so creating) the file when no scan ever ran. */
        fun exists(context: Context) = instance != null || context.getDatabasePath(FILE).exists()
    }
}
