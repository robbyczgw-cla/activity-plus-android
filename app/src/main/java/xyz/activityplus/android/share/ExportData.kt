package xyz.activityplus.android.share

import android.content.Context
import android.os.Build
import xyz.activityplus.android.BuildConfig
import xyz.activityplus.android.core.DataTransfer
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.ShareReport
import xyz.activityplus.android.core.Table
import xyz.activityplus.android.core.Tables
import xyz.activityplus.android.data.BackgroundStore
import xyz.activityplus.android.data.ChargingStore
import xyz.activityplus.android.data.NightStore
import xyz.activityplus.android.data.PowerRailStore
import xyz.activityplus.android.data.SensorLogStore
import xyz.activityplus.android.data.StorageGrowthStore
import xyz.activityplus.android.data.ThermalStore
import xyz.activityplus.android.data.UpdateLog
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.screens.loadNightCheck
import org.json.JSONObject
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Export data": the history as spreadsheet files and one JSON summary, in a ZIP written to a
 * stream the user chose with the system file picker. Rows go from the database cursor straight into
 * the ZIP, so 30 days of minute samples never sit in memory. The files, their columns and the ZIP
 * itself are described in [Tables] and [DataTransfer]; this reads the phone's stores into them, and
 * [ImportData] is the way back.
 */
object ExportData {
    /** File name to rows, in the order written; the summary comes last and lists the others. */
    fun write(context: Context, out: OutputStream, nowMillis: Long = System.currentTimeMillis()): Map<String, Int> {
        val zone = ZoneId.systemDefault()
        val info = DataTransfer.Info(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, nowMillis, zone) { summary(it, zone) }
        return DataTransfer.write(out, { table, emit -> read(context, table, nowMillis, emit) }, info)
    }

    /**
     * One table's rows, in the order of its [Table.stored] columns. A store that never ran has no file
     * yet; it is not created just to be empty, the table is written with its header and no rows.
     */
    private fun read(context: Context, table: Table, nowMillis: Long, emit: (List<Any?>) -> Unit) {
        if (table.db != null && !context.getDatabasePath(table.db.file).exists()) return
        when (table) {
            Tables.SAMPLES -> app.history.forEachSample(0) { s ->
                emit(listOf(s.ts, s.level, s.powerMw, s.tempC, s.charging, s.memUsed, s.rxBps, s.txBps, s.clock, s.thermal, s.screenOn, s.fgPkg))
            }
            Tables.APP_ENERGY -> app.history.forEachEnergyRow { day, pkg, mwh, seconds -> emit(listOf(day, pkg, mwh, seconds)) }
            // A few hundred at most in 30 days: the existing newest-first reader is enough.
            Tables.SESSIONS -> app.history.sessions(limit = 100_000).asReversed().forEach { s ->
                emit(
                    listOf(
                        s.startLevel, s.endLevel, s.energyMwh, s.peakMw, s.screenOnSeconds, s.screenOffSeconds, s.maxTempC,
                        s.startMillis, s.endMillis, s.charging,
                    )
                )
            }
            Tables.PRO_REPORTS -> app.history.proReports(limit = 100_000).asReversed().forEach { emit(listOf(it.timeMillis, it.toJson())) }
            Tables.CHARGER_TESTS -> ChargingStore.get(context).tests().sortedBy { it.m.timeMillis }.forEach { t ->
                val m = t.m
                emit(listOf(m.timeMillis, t.name, m.seconds, m.avgMw, m.peakMw, m.avgVoltageV, m.avgCurrentMa, m.startLevel, m.startTempC))
            }
            Tables.CHARGE_CAPACITY -> ChargingStore.get(context).capacity().forEach { emit(listOf(it.timeMillis, it.mah, it.levelFrom, it.levelTo)) }
            Tables.BACKGROUND_DAYS -> BackgroundStore.get(context).forEachDay { day, a ->
                emit(listOf(day, a.pkg, a.mah, a.cpuMs, a.wakelockMs))
            }
            Tables.WAKEUP_DAYS -> {
                val rows = ArrayList<Triple<String, String, List<Long>>>()
                for ((pkg, days) in WakeupStore.get(context).allSeries(FIRST_DAY, LAST_DAY)) {
                    for ((day, c) in days) rows += Triple(day, pkg, c.toArray().toList())
                }
                rows.sortedWith(compareBy({ it.first }, { it.second })).forEach { (day, pkg, c) -> emit(listOf<Any?>(day, pkg, *c.toTypedArray())) }
            }
            Tables.NIGHT_SPANS -> NightStore.get(context).spans(0, Long.MAX_VALUE).forEach { s ->
                emit(
                    listOf(
                        s.startMillis, s.endMillis, s.startElapsed, s.endElapsed, s.startUptime, s.endUptime,
                        s.startLevel, s.endLevel, s.startMah, s.endMah, s.plugged,
                        NightCheck.encodeDoze(s.doze), s.startSignal, s.endSignal,
                    )
                )
            }
            // The nights as the Last night card judges them (the last 14), oldest first.
            Tables.NIGHTS -> runCatching { loadNightCheck(context, nowMillis).nights.asReversed() }.getOrNull().orEmpty().forEach { e ->
                emit(night(e))
            }
            Tables.STORAGE_DEVICE -> StorageGrowthStore.get(context).device(EPOCH_DAY).forEach { emit(listOf(it.day.toString(), it.timeMillis, it.totalBytes, it.freeBytes)) }
            Tables.STORAGE_APPS -> StorageGrowthStore.get(context).apps(EPOCH_DAY).forEach { snap ->
                for ((pkg, size) in snap.apps.toSortedMap()) {
                    emit(listOf(snap.day.toString(), pkg, snap.timeMillis, size.appBytes, size.dataBytes, size.cacheBytes))
                }
            }
            Tables.SENSOR_EVENTS -> SensorLogStore.get(context).accesses(0, Long.MAX_VALUE).forEach { a ->
                val e = a.event
                emit(listOf(a.pkg, e.op.name, e.timeMillis, e.rejected, e.where.name, e.flags, e.durationMs ?: 0L))
            }
            Tables.APP_UPDATES -> UpdateLog.get(context).events(0).asReversed().forEach { e ->
                emit(listOf(e.pkg, e.timeMillis, e.oldName, e.oldCode, e.newName, e.newCode))
            }
            Tables.NET_USAGE -> {
                val rows = ArrayList<List<Any?>>()
                for ((pkg, days) in UpdateLog.get(context).netByDay(FIRST_DAY, LAST_DAY)) {
                    for ((day, bytes) in days) rows += listOf(day, pkg, bytes.first, bytes.second)
                }
                rows.sortedWith(compareBy<List<Any?>>({ it[0] as String }, { it[1] as String })).forEach(emit)
            }
            Tables.NET_DAYS -> UpdateLog.get(context).netDays(FIRST_DAY, LAST_DAY).sorted().forEach { emit(listOf(it)) }
            Tables.THERMAL_EVENTS -> ThermalStore.get(context).events(0).forEach { e ->
                // -1 is the store's own mark for a gap (the monitor stopped there).
                emit(listOf(e.timeMillis, e.status?.ordinal ?: -1, e.pkg, e.headroom, e.batteryTempC, e.sensor, e.sensorC, e.coolingActive))
            }
            // For reading only, see Tables.POWER_RAILS.
            Tables.POWER_RAILS -> PowerRailStore.get(context).rows(0).sortedWith(compareBy({ it.slotStart }, { it.key })).forEach {
                emit(listOf(it.slotStart, it.key, it.uws, it.ms))
            }
            else -> error("No reader for ${table.file}")
        }
    }

    private const val FIRST_DAY = "0000-01-01"
    private const val LAST_DAY = "9999-12-31"
    private val EPOCH_DAY = LocalDate.of(1970, 1, 1)

    private fun night(e: NightCheck.Entry): List<Any?> {
        val r = e.result
        val m = r.measured
        return listOf(
            e.date.toString(), r.verdict.name.lowercase(), r.reason.name.lowercase(),
            m?.let { it.asleepMs / 60_000.0 }, m?.let { it.deepShare * 100 }, m?.drainPerHour, m?.mahPerHour,
            m?.interruptions, m?.firstDeepAfterMs?.let { it / 60_000.0 }, r.suspect?.pkg,
        )
    }

    /** Where the file came from; the same device facts the shared report names. */
    private fun summary(json: JSONObject, zone: ZoneId) {
        val b = runCatching { app.monitor.batteryNow() }.getOrNull()
        val storage = app.monitor.latest.value?.storage
        json.put("device", JSONObject().apply {
            put("model", ShareReport.deviceName(Build.MANUFACTURER, Build.MODEL))
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
        })
        if (b != null) {
            json.put("battery", JSONObject().apply {
                put("percent", Math.round(b.levelFraction * 100))
                b.estimatedFullMah?.let { put("full_capacity_mah", Math.round(it)) }
                b.designMah?.let { put("rated_capacity_mah", Math.round(it)) }
                b.healthFraction?.let { put("estimated_health_percent", Math.round(it * 100)) }
                b.cycleCount?.let { put("cycles", it) }
            })
        }
        if (storage != null) {
            json.put("storage", JSONObject().apply {
                put("free_bytes", storage.freeBytes)
                put("total_bytes", storage.totalBytes)
            })
        }
    }
}
