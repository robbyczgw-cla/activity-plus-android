package xyz.activityplus.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.activityplus.android.core.EnergySlice
import xyz.activityplus.android.core.RailBaseline
import xyz.activityplus.android.core.RailReading
import xyz.activityplus.android.core.SlotRow

/**
 * Energy per power monitor in 15-minute slots for 30 days, in its own file. The last reading of each monitor
 * is kept too, so the energy between the last sample before the app was killed and the first one after is not lost.
 */
class PowerRailStore private constructor(context: Context) : SQLiteOpenHelper(context, "power.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // uws may be slightly negative: coarse readings carry noise that only cancels out in sums.
        db.execSQL("CREATE TABLE energy(slot INTEGER NOT NULL, key TEXT NOT NULL, uws INTEGER NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(slot, key))")
        db.execSQL("CREATE TABLE baseline(key TEXT PRIMARY KEY, uws INTEGER NOT NULL, ts INTEGER NOT NULL, boot INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Adds to the slots (several writes into one slot sum up) and replaces the baselines, in one transaction. */
    fun record(slices: Map<String, List<EnergySlice>>, baselines: Map<String, RailBaseline>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((key, list) in slices) {
                for (s in list) {
                    // UPDATE first: the upsert syntax needs a newer SQLite than Android 10 has.
                    db.execSQL("UPDATE energy SET uws = uws + ?, ms = ms + ? WHERE slot = ? AND key = ?", arrayOf(s.uws, s.ms, s.slotStart, key))
                    db.execSQL("INSERT OR IGNORE INTO energy(slot, key, uws, ms) VALUES(?, ?, ?, ?)", arrayOf(s.slotStart, key, s.uws, s.ms))
                }
            }
            db.delete("baseline", null, null)
            val v = ContentValues()
            for ((key, b) in baselines) {
                v.clear()
                v.put("key", key); v.put("uws", b.reading.energyUws); v.put("ts", b.reading.timestampMs); v.put("boot", b.bootMillis)
                db.insert("baseline", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun baselines(): Map<String, RailBaseline> {
        val out = HashMap<String, RailBaseline>()
        readableDatabase.rawQuery("SELECT key, uws, ts, boot FROM baseline", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = RailBaseline(RailReading(c.getLong(1), c.getLong(2)), c.getLong(3))
        }
        return out
    }

    /** Slots from [fromSlot] on. */
    fun rows(fromSlot: Long): List<SlotRow> {
        val out = ArrayList<SlotRow>()
        readableDatabase.rawQuery("SELECT slot, key, uws, ms FROM energy WHERE slot >= ?", arrayOf(fromSlot.toString())).use { c ->
            while (c.moveToNext()) out += SlotRow(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))
        }
        return out
    }

    /** Drops every slot that starts before [firstKept]. */
    fun prune(firstKept: Long) {
        writableDatabase.delete("energy", "slot < ?", arrayOf(firstKept.toString()))
    }

    companion object {
        @Volatile private var instance: PowerRailStore? = null

        fun get(context: Context): PowerRailStore =
            instance ?: synchronized(this) { instance ?: PowerRailStore(context.applicationContext).also { instance = it } }
    }
}
