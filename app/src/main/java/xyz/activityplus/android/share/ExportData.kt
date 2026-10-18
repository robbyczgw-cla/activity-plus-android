package xyz.activityplus.android.share

import android.content.Context
import android.os.Build
import org.json.JSONObject
import xyz.activityplus.android.BuildConfig
import xyz.activityplus.android.core.Csv
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.ShareReport
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.screens.loadNightCheck
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Export data": the history as spreadsheet files and one JSON summary, in a ZIP written to a
 * stream the user chose with the system file picker. Rows go from the database cursor straight into
 * the ZIP, so 30 days of minute samples never sit in memory.
 */
object ExportData {
    /** File name to rows, in the order written; the summary comes last and lists the others. */
    fun write(context: Context, out: OutputStream, nowMillis: Long = System.currentTimeMillis()): Map<String, Int> {
        val zone = ZoneId.systemDefault()
        val rows = LinkedHashMap<String, Int>()
        var firstSample: Long? = null
        var lastSample: Long? = null
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val w = OutputStreamWriter(zip, Charsets.UTF_8)

            fun csv(name: String, header: List<String>, fill: (row: (List<Any?>) -> Unit) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                w.write(Csv.line(header))
                var n = 0
                fill { w.write(Csv.line(it)); n++ }
                w.flush()
                zip.closeEntry()
                rows[name] = n
            }

            csv(
                "samples.csv",
                listOf(
                    "time", "epoch_ms", "battery_percent", "power_mw", "temperature_c", "charging", "memory_used_percent",
                    "rx_bytes_per_s", "tx_bytes_per_s", "cpu_clock_percent", "thermal_status", "screen_on", "foreground_app",
                ),
            ) { row ->
                app.history.forEachSample(0) { s ->
                    if (firstSample == null) firstSample = s.ts
                    lastSample = s.ts
                    row(
                        listOf(
                            Csv.time(s.ts, zone), s.ts, s.level * 100, s.powerMw, s.tempC, s.charging, s.memUsed * 100,
                            s.rxBps, s.txBps, s.clock?.let { it * 100 }, s.thermal, s.screenOn, s.fgPkg,
                        )
                    )
                }
            }

            csv("app_energy.csv", listOf("day", "package", "energy_mwh", "seconds_on_screen")) { row ->
                app.history.forEachEnergyRow { day, pkg, mwh, seconds -> row(listOf(day, pkg, mwh, seconds)) }
            }

            csv(
                "sessions.csv",
                listOf(
                    "start", "end", "kind", "start_percent", "end_percent", "energy_mwh", "peak_mw",
                    "screen_on_s", "screen_off_s", "max_temperature_c",
                ),
            ) { row ->
                // A few hundred at most in 30 days: the existing newest-first reader is enough.
                app.history.sessions(limit = 100_000).asReversed().forEach { s ->
                    row(
                        listOf(
                            Csv.time(s.startMillis, zone), Csv.time(s.endMillis, zone), if (s.charging) "charging" else "discharging",
                            s.startLevel * 100, s.endLevel * 100, s.energyMwh, s.peakMw, s.screenOnSeconds, s.screenOffSeconds, s.maxTempC,
                        )
                    )
                }
            }

            csv(
                "nights.csv",
                listOf(
                    "date", "verdict", "reason", "screen_off_min", "deep_sleep_percent", "drain_percent_per_h", "drain_mah_per_h",
                    "doze_interruptions", "first_deep_doze_after_min", "app",
                ),
            ) { row ->
                // The nights as the Last night card judges them (the last 14), oldest first.
                runCatching { loadNightCheck(context, nowMillis).nights.asReversed() }.getOrNull().orEmpty().forEach { e ->
                    row(night(e))
                }
            }

            zip.putNextEntry(ZipEntry("summary.json"))
            w.write(summary(context, nowMillis, zone, rows, firstSample, lastSample).toString(2))
            w.flush()
            zip.closeEntry()
            rows["summary.json"] = 1
        }
        return rows
    }

    private fun night(e: NightCheck.Entry): List<Any?> {
        val r = e.result
        val m = r.measured
        return listOf(
            e.date.toString(), r.verdict.name.lowercase(), r.reason.name.lowercase(),
            m?.let { it.asleepMs / 60_000.0 }, m?.let { it.deepShare * 100 }, m?.drainPerHour, m?.mahPerHour,
            m?.interruptions, m?.firstDeepAfterMs?.let { it / 60_000.0 }, r.suspect?.pkg,
        )
    }

    /** What the other files hold and where they came from; the same device facts the shared report names. */
    private fun summary(context: Context, now: Long, zone: ZoneId, rows: Map<String, Int>, first: Long?, last: Long?): JSONObject {
        val b = runCatching { app.monitor.batteryNow() }.getOrNull()
        val storage = app.monitor.latest.value?.storage
        return JSONObject().apply {
            put("app", "Activity+")
            put("version", BuildConfig.VERSION_NAME)
            put("exported", Csv.time(now, zone))
            put("device", JSONObject().apply {
                put("model", ShareReport.deviceName(Build.MANUFACTURER, Build.MODEL))
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
            })
            if (b != null) {
                put("battery", JSONObject().apply {
                    put("percent", Math.round(b.levelFraction * 100))
                    b.estimatedFullMah?.let { put("full_capacity_mah", Math.round(it)) }
                    b.designMah?.let { put("rated_capacity_mah", Math.round(it)) }
                    b.healthFraction?.let { put("estimated_health_percent", Math.round(it * 100)) }
                    b.cycleCount?.let { put("cycles", it) }
                })
            }
            if (storage != null) {
                put("storage", JSONObject().apply {
                    put("free_bytes", storage.freeBytes)
                    put("total_bytes", storage.totalBytes)
                })
            }
            if (first != null && last != null) {
                put("samples", JSONObject().apply { put("first", Csv.time(first, zone)); put("last", Csv.time(last, zone)) })
            }
            put("files", JSONObject().apply { rows.forEach { (name, n) -> put(name, n) } })
            put("notes", "Percent values are 0-100. Power is milliwatts, positive into the battery. Times carry their UTC offset.")
        }
    }
}
