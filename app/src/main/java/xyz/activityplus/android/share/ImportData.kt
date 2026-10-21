package xyz.activityplus.android.share

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import xyz.activityplus.android.core.Column
import xyz.activityplus.android.core.DataTransfer
import xyz.activityplus.android.core.Db
import xyz.activityplus.android.core.Table
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.data.ChargingStore
import xyz.activityplus.android.data.NightStore
import xyz.activityplus.android.data.SensorLogStore
import xyz.activityplus.android.data.StorageGrowthStore
import xyz.activityplus.android.data.ThermalStore
import xyz.activityplus.android.data.UpdateLog
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.ui.app

/**
 * "Import data": the way back for [ExportData], for a new phone. The ZIP is read twice, once to show what
 * it holds ([inspect]) and, after the user agrees, once to merge it ([run]). What the merge does, and does
 * not do, is described at [DataTransfer]; this only connects it to the phone's SQLite files.
 */
object ImportData {
    fun inspect(context: Context, uri: Uri): DataTransfer.Inspection =
        context.contentResolver.openInputStream(uri)!!.use { DataTransfer.inspect(it) }

    /** Throws when the file cannot be read or a database fails; tables merged before that stay merged, and a second run adds the rest. */
    fun run(context: Context, uri: Uri, format: Int, nowMillis: Long = System.currentTimeMillis()): DataTransfer.Report =
        context.contentResolver.openInputStream(uri)!!.use { DataTransfer.import(it, format, Database(context), nowMillis) }

    private class Database(private val context: Context) : DataTransfer.Sink {
        // Opening a store here creates its file when this phone never ran the feature; that is wanted, it is going to hold rows.
        private fun open(table: Table): SQLiteDatabase = when (requireNotNull(table.db)) {
            Db.HISTORY -> app.history.writableDatabase
            Db.CHARGING -> ChargingStore.get(context).writableDatabase
            Db.BACKGROUND -> BackgroundStore.get(context).writableDatabase
            Db.WAKEUPS -> WakeupStore.get(context).writableDatabase
            Db.NIGHTS -> NightStore.get(context).writableDatabase
            Db.STORAGE -> StorageGrowthStore.get(context).writableDatabase
            Db.SENSORS -> SensorLogStore.get(context).writableDatabase
            Db.UPDATES -> UpdateLog.get(context).writableDatabase
            Db.THERMAL -> ThermalStore.get(context).writableDatabase
            // Not importable (see Tables.POWER_RAILS); a table that gets here by mistake must not be written.
            Db.POWER -> error("${table.file} is not imported")
        }

        override fun existingKeys(table: Table): MutableSet<String> {
            val keys = HashSet<String>()
            val types = table.key.map { name -> table.stored.first { it.name == name }.type }
            open(table).rawQuery("SELECT ${table.keyDb.joinToString()} FROM ${table.dbTable}", null).use { c ->
                while (c.moveToNext()) {
                    keys.add(table.keyOf(types.mapIndexed { i, type ->
                        if (type == Column.Type.TEXT || type == Column.Type.DAY) c.getString(i) else c.getLong(i)
                    }))
                }
            }
            return keys
        }

        override fun transaction(table: Table, block: () -> Unit) {
            val db = open(table)
            db.beginTransaction()
            try {
                block()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun insert(table: Table, rows: List<List<Any?>>) {
            val db = open(table)
            val v = ContentValues()
            for (row in rows) {
                v.clear()
                for ((column, value) in table.stored.zip(row)) {
                    when (value) {
                        null -> v.putNull(column.db)
                        is Long -> v.put(column.db, value)
                        is Double -> v.put(column.db, value)
                        else -> v.put(column.db, value.toString())
                    }
                }
                // IGNORE on top of the key check: whatever is already stored is never replaced.
                db.insertWithOnConflict(table.dbTable, null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }
}
