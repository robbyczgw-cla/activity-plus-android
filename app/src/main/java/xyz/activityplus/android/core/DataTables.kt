package xyz.activityplus.android.core

import java.time.LocalDate
import java.time.ZoneId

/** The SQLite files that hold history; the export names them in [Table.db], the import writes into them. */
enum class Db(val file: String) {
    HISTORY("history.db"), CHARGING("charging.db"), BACKGROUND("background.db"), WAKEUPS("wakeups.db"),
    NIGHTS("nights.db"), STORAGE("storage.db"), SENSORS("sensors.db"), UPDATES("updates.db"),
    THERMAL("thermal.db"), POWER("power.db"),
}

/**
 * One column of an export file. The same description writes the file and reads it back, so the two
 * cannot drift apart. Pure.
 */
class Column(
    /** Header in the CSV file. */
    val name: String,
    val type: Type,
    /** Column of the database table; the CSV name unless the file is friendlier than the schema. */
    val db: String = name,
    /** An empty field is null. Otherwise an empty number makes the row unreadable. */
    val nullable: Boolean = false,
    /** The file holds the stored value times this (percent in the file, a fraction in the database). */
    val scale: Double = 1.0,
    /** Only for people reading the file (the time as text): worked out when writing, ignored when reading. */
    val derive: ((get: (String) -> Any?, zone: ZoneId) -> Any?)? = null,
) {
    enum class Type {
        INT, REAL, TEXT,
        /** 0 or 1. */
        FLAG,
        /** Milliseconds since 1970; a time far in the future would never be pruned, so those rows are refused. */
        TIME,
        /** yyyy-MM-dd. */
        DAY,
    }

    val stored get() = derive == null
}

/**
 * One file of the export, and the database table it comes from. [key] names the columns that make a row
 * the same row on another phone: a minute, a day and an app, the start of a session.
 */
class Table(
    val file: String,
    /** Null for a file that only reports what was worked out from other tables. */
    val db: Db?,
    val dbTable: String,
    val columns: List<Column>,
    val key: List<String>,
    /** The export format that first wrote this file. */
    val firstFormat: Int = 2,
    /** False: written for the reader's sake only (worked out, or belonging to the old phone's hardware). */
    val importable: Boolean = true,
    /** False: counted and imported, but not worth a line of its own in the dialog. */
    val listed: Boolean = true,
) {
    /** The columns that come from the database, in file order: what a [Source] hands over and a [Sink] takes. */
    val stored = columns.filter { it.stored }
    private val storedIndex = stored.withIndex().associate { (i, c) -> c.name to i }
    private val keyIndex = key.map { requireNotNull(storedIndex[it]) { "$file: key $it is not a stored column" } }

    val header get() = columns.map { it.name }

    /** The values of one row as the file shows them: scaled, with the derived columns filled in. */
    fun csvRow(values: List<Any?>, zone: ZoneId): List<Any?> {
        require(values.size == stored.size) { "$file: ${values.size} values for ${stored.size} columns" }
        val byName = { name: String -> values[requireNotNull(storedIndex[name]) { "$file: no column $name" }] }
        var next = 0
        return columns.map { c ->
            if (c.derive != null) return@map c.derive.invoke(byName, zone)
            val v = values[next++]
            when {
                v == null -> null
                c.type == Column.Type.FLAG -> flag(v)
                c.type == Column.Type.REAL || c.type == Column.Type.INT || c.type == Column.Type.TIME -> {
                    val n = (v as Number)
                    if (c.type == Column.Type.REAL) n.toDouble() * c.scale else n.toLong()
                }
                else -> v.toString()
            }
        }
    }

    /** What makes the row the same row on any phone; [values] are in [stored] order, as a [Sink] has them. */
    fun keyOf(values: List<Any?>): String = keyIndex.joinToString("\u001F") { values[it].toString() }

    /** The names of the key columns in the database, for reading what a phone already has. */
    val keyDb get() = key.map { name -> columns.first { it.name == name }.db }
}

internal fun flag(v: Any?): Boolean? = when (v) {
    null -> null
    is Boolean -> v
    else -> (v as Number).toLong() != 0L
}

/**
 * Every table that holds the user's history. Not here, on purpose: what belongs to the phone that took it,
 * namely the counter snapshots the next reading is subtracted from (`last` in background.db and wakeups.db),
 * the power monitor baselines (`baseline` in power.db), the package scan the next update is compared with
 * (`known` in updates.db), the bookkeeping `meta` tables and the speed test runs. Those describe the old
 * phone; taken over, they would make the first readings on the new one wrong (a huge "interval" against
 * counters of another device) or hide what the new phone still has to measure.
 */
object Tables {
    private fun time(source: String = "epoch_ms") =
        Column("time", Column.Type.TEXT, derive = { get, zone -> Csv.time((get(source) as Number).toLong(), zone) })

    private fun percent(name: String, db: String) = Column(name, Column.Type.REAL, db, nullable = true, scale = 100.0)
    private fun real(name: String, db: String = name, nullable: Boolean = false) = Column(name, Column.Type.REAL, db, nullable)
    private fun int(name: String, db: String = name, nullable: Boolean = false) = Column(name, Column.Type.INT, db, nullable)
    private fun text(name: String, db: String = name, nullable: Boolean = false) = Column(name, Column.Type.TEXT, db, nullable)
    private fun day(name: String = "day") = Column(name, Column.Type.DAY)
    private fun at(name: String, db: String) = Column(name, Column.Type.TIME, db)
    private fun yesNo(name: String, db: String = name) = Column(name, Column.Type.FLAG, db)

    /** Format 1 (Activity+ 0.5) wrote the first two files with these very columns. */
    val SAMPLES = Table(
        "samples.csv", Db.HISTORY, "samples",
        listOf(
            time(), at("epoch_ms", "ts"), percent("battery_percent", "level"),
            real("power_mw", nullable = true), real("temperature_c", "temp_c", nullable = true), yesNo("charging"),
            percent("memory_used_percent", "mem_used"), real("rx_bytes_per_s", "rx_bps", nullable = true),
            real("tx_bytes_per_s", "tx_bps", nullable = true), percent("cpu_clock_percent", "clock"),
            int("thermal_status", "thermal"), yesNo("screen_on"), text("foreground_app", "fg_pkg", nullable = true),
        ),
        key = listOf("epoch_ms"), firstFormat = 1,
    )

    val APP_ENERGY = Table(
        "app_energy.csv", Db.HISTORY, "app_energy",
        listOf(day(), text("package", "pkg"), real("energy_mwh", "mwh"), real("seconds_on_screen", "seconds")),
        key = listOf("day", "package"), firstFormat = 1,
    )

    val SESSIONS = Table(
        "sessions.csv", Db.HISTORY, "sessions",
        listOf(
            Column("start", Column.Type.TEXT, derive = { get, zone -> Csv.time((get("start_epoch_ms") as Number).toLong(), zone) }),
            Column("end", Column.Type.TEXT, derive = { get, zone -> Csv.time((get("end_epoch_ms") as Number).toLong(), zone) }),
            Column("kind", Column.Type.TEXT, derive = { get, _ -> if (flag(get("charging")) == true) "charging" else "discharging" }),
            percent("start_percent", "start_level"), percent("end_percent", "end_level"),
            real("energy_mwh", nullable = true), real("peak_mw", nullable = true),
            real("screen_on_s", "on_s", nullable = true), real("screen_off_s", "off_s", nullable = true),
            real("max_temperature_c", "max_temp", nullable = true),
            // The exact times and the flag: "start" and "end" above are rounded to the second.
            at("start_epoch_ms", "start_ts"), at("end_epoch_ms", "end_ts"), yesNo("charging"),
        ),
        key = listOf("start_epoch_ms"),
    )

    /** The Pro page's saved reports, one JSON text each. */
    val PRO_REPORTS = Table(
        "pro_reports.csv", Db.HISTORY, "pro",
        listOf(time(), at("epoch_ms", "ts"), text("report_json", "json")),
        key = listOf("epoch_ms"),
    )

    val CHARGER_TESTS = Table(
        "charger_tests.csv", Db.CHARGING, "charger_tests",
        listOf(
            time(), at("epoch_ms", "ts"), text("name"), int("seconds"), real("avg_mw"), real("peak_mw"),
            real("avg_voltage_v", "avg_v"), real("avg_current_ma", "avg_ma"), real("start_level"), real("start_temperature_c", "start_temp"),
        ),
        key = listOf("epoch_ms"),
    )

    val CHARGE_CAPACITY = Table(
        "charge_capacity.csv", Db.CHARGING, "capacity",
        listOf(time(), at("epoch_ms", "ts"), real("capacity_mah", "mah"), real("level_from"), real("level_to")),
        key = listOf("epoch_ms"),
    )

    val BACKGROUND_DAYS = Table(
        "background_days.csv", Db.BACKGROUND, "days",
        listOf(day(), text("package", "pkg"), real("mah"), int("cpu_ms"), int("wakelock_ms")),
        key = listOf("day", "package"),
    )

    val WAKEUP_DAYS = Table(
        "wakeup_days.csv", Db.WAKEUPS, "days",
        listOf(
            day(), text("package", "pkg"), int("cpu_ms"), int("wakelock_ms", "wl_ms"), int("wakelock_count", "wl_count"),
            int("wakeups"), int("jobs"), int("job_ms"), int("syncs"), int("gps_ms"),
        ),
        key = listOf("day", "package"),
    )

    /**
     * The screen-off spans of the night check. The four clocks are the old phone's uptime and elapsed time:
     * the check only ever uses the difference inside one span, so they stay meaningful on another phone.
     */
    val NIGHT_SPANS = Table(
        "night_spans.csv", Db.NIGHTS, "spans",
        listOf(
            Column("start", Column.Type.TEXT, derive = { get, zone -> Csv.time((get("start_epoch_ms") as Number).toLong(), zone) }),
            Column("end", Column.Type.TEXT, derive = { get, zone -> Csv.time((get("end_epoch_ms") as Number).toLong(), zone) }),
            at("start_epoch_ms", "start_ts"), at("end_epoch_ms", "end_ts"),
            int("start_elapsed_ms", "start_elapsed"), int("end_elapsed_ms", "end_elapsed"),
            int("start_uptime_ms", "start_uptime"), int("end_uptime_ms", "end_uptime"),
            Column("start_percent", Column.Type.REAL, "start_level", scale = 100.0), Column("end_percent", Column.Type.REAL, "end_level", scale = 100.0),
            real("start_mah", nullable = true), real("end_mah", nullable = true), yesNo("plugged"),
            text("doze", nullable = true), int("start_signal", nullable = true), int("end_signal", nullable = true),
        ),
        key = listOf("start_epoch_ms"),
    )

    /** What the Last night card concluded, for reading in a spreadsheet; worked out from the spans, so not imported. */
    val NIGHTS = Table(
        "nights.csv", null, "",
        listOf(
            text("date"), text("verdict"), text("reason"), real("screen_off_min", nullable = true),
            real("deep_sleep_percent", nullable = true), real("drain_percent_per_h", nullable = true), real("drain_mah_per_h", nullable = true),
            int("doze_interruptions", nullable = true), real("first_deep_doze_after_min", nullable = true), text("app", nullable = true),
        ),
        key = emptyList(), firstFormat = 1, importable = false,
    )

    val STORAGE_DEVICE = Table(
        "storage_device.csv", Db.STORAGE, "device",
        listOf(day(), at("epoch_ms", "ts"), int("total_bytes", "total"), int("free_bytes", "free")),
        key = listOf("day"),
    )

    val STORAGE_APPS = Table(
        "storage_apps.csv", Db.STORAGE, "apps",
        listOf(day(), text("package", "pkg"), at("epoch_ms", "ts"), int("app_bytes", "app"), int("data_bytes", "data"), int("cache_bytes", "cache")),
        key = listOf("day", "package"),
    )

    val SENSOR_EVENTS = Table(
        "sensor_events.csv", Db.SENSORS, "events",
        listOf(
            time(), text("package", "pkg"), text("op"), at("epoch_ms", "ts"), yesNo("rejected"),
            text("state"), text("flags"), int("duration_ms"),
        ),
        key = listOf("package", "op", "epoch_ms", "rejected"),
    )

    val APP_UPDATES = Table(
        "app_updates.csv", Db.UPDATES, "updates",
        listOf(
            time(), text("package", "pkg"), at("epoch_ms", "ts"),
            text("old_version_name", "old_name", nullable = true), int("old_version_code", "old_code", nullable = true),
            text("new_version_name", "new_name", nullable = true), int("new_version_code", "new_code"),
        ),
        key = listOf("package", "epoch_ms"),
    )

    val NET_USAGE = Table(
        "net_usage.csv", Db.UPDATES, "net",
        listOf(day(), text("package", "pkg"), int("wifi_bytes", "wifi"), int("mobile_bytes", "mobile")),
        key = listOf("day", "package"),
    )

    /** The days NET_USAGE was read for, so an app without a row that day counts as "used nothing". */
    val NET_DAYS = Table(
        "net_days.csv", Db.UPDATES, "net_days",
        listOf(day()),
        key = listOf("day"), listed = false,
    )

    val THERMAL_EVENTS = Table(
        "thermal_events.csv", Db.THERMAL, "events",
        listOf(
            time(), at("epoch_ms", "ts"), int("thermal_status", "status"), text("package", "pkg", nullable = true),
            real("headroom", nullable = true), real("temperature_c", "temp_c", nullable = true),
            text("sensor", nullable = true), real("sensor_c", nullable = true), int("cooling_devices_active", "cooling", nullable = true),
        ),
        key = listOf("epoch_ms"),
    )

    /**
     * Energy per power monitor in 15-minute slots. For reading only: the rail names ("[Rail2]:CPU") are those
     * of the old phone's hardware, and rails of another phone mixed into this one's charts would give wrong totals.
     */
    val POWER_RAILS = Table(
        "power_rails.csv", Db.POWER, "energy",
        listOf(
            Column("time", Column.Type.TEXT, derive = { get, zone -> Csv.time((get("slot_epoch_ms") as Number).toLong(), zone) }),
            at("slot_epoch_ms", "slot"), text("rail", "key"), int("energy_uws", "uws"), int("ms"),
        ),
        key = listOf("slot_epoch_ms", "rail"), importable = false,
    )

    /** In the order written; the summary comes after the last. */
    val ALL = listOf(
        SAMPLES, APP_ENERGY, SESSIONS, PRO_REPORTS, CHARGER_TESTS, CHARGE_CAPACITY, BACKGROUND_DAYS, WAKEUP_DAYS,
        NIGHT_SPANS, NIGHTS, STORAGE_DEVICE, STORAGE_APPS, SENSOR_EVENTS, APP_UPDATES, NET_USAGE, NET_DAYS,
        THERMAL_EVENTS, POWER_RAILS,
    )

    fun byFile(name: String) = ALL.find { it.file == name }
}

/** Plain-date check used by the reader; kept here so [Column.Type.DAY] means one thing everywhere. */
internal fun validDay(s: String): Boolean = runCatching { LocalDate.parse(s) }.isSuccess
