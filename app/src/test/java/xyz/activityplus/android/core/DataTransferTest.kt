package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DataTransferTest {
    private val now = 1_792_309_265_000L // 2026-10-18 07:41:05 UTC
    private val zone = ZoneId.of("Europe/Vienna")
    private val info = DataTransfer.Info("0.6.0", 8, now, zone) { it.put("device", org.json.JSONObject().put("model", "Pixel 9")) }

    /** Rows per table, as the stores hand them over. */
    private class FakeSource(val data: Map<Table, List<List<Any?>>>) : DataTransfer.Source {
        override fun rows(table: Table, emit: (List<Any?>) -> Unit) = (data[table] ?: emptyList()).forEach(emit)
    }

    /** A database in memory with the one property that matters: a transaction that fails changes nothing. */
    private class FakeSink : DataTransfer.Sink {
        val rows = HashMap<Table, MutableList<List<Any?>>>()
        var failOn: Table? = null

        fun of(table: Table): List<List<Any?>> = rows[table].orEmpty()

        override fun existingKeys(table: Table) = of(table).map { table.keyOf(it) }.toMutableSet()

        override fun transaction(table: Table, block: () -> Unit) {
            val before = rows[table]?.toMutableList()
            try {
                block()
            } catch (e: Exception) {
                if (before == null) rows.remove(table) else rows[table] = before
                throw e
            }
        }

        override fun insert(table: Table, rows: List<List<Any?>>) {
            if (failOn === table) throw IllegalStateException("disk full")
            this.rows.getOrPut(table) { ArrayList() }.addAll(rows)
        }
    }

    private fun samples(n: Int = 3, from: Long = now - 10 * 60_000L): List<List<Any?>> = (0 until n).map { i ->
        // level 0.5712 is 57.12 % in the file; both stay within the four decimals the file keeps.
        listOf(from + i * 60_000L, 0.5712 - i * 0.01, -850.5 - i, 31.4, i % 2 == 0, 0.4321, 1200.0, 300.0, null, 0, i == 1, if (i == 2) null else "com.android.chrome")
    }

    private fun sessions() = listOf(
        listOf(0.9, 0.35, 1234.5, 5000.0, 600.0, 120.0, 38.5, now - 7_200_000L, now - 3_600_000L, false),
        listOf(0.2, 0.8, 9000.0, 20000.0, 100.0, 3500.0, 33.0, now - 3_000_000L, now - 600_000L, true),
    )

    private fun everything(): Map<Table, List<List<Any?>>> = mapOf(
        Tables.SAMPLES to samples(),
        Tables.APP_ENERGY to listOf(listOf("2026-10-17", "com.example.a", 12.5, 340.0), listOf("2026-10-17", "com.example.b", 1.25, 20.0)),
        Tables.SESSIONS to sessions(),
        Tables.PRO_REPORTS to listOf(listOf(now - 86_400_000L, "{\"t\":1,\"apps\":[{\"p\":\"a,b\"}]}")),
        Tables.CHARGER_TESTS to listOf(listOf(now - 5_000_000L, "Desk, \"fast\" one", 120, 18000.0, 21000.0, 9.0, 2000.0, 0.31, 26.5)),
        Tables.CHARGE_CAPACITY to listOf(listOf(now - 4_000_000L, 4650.5, 0.2, 0.9)),
        Tables.BACKGROUND_DAYS to listOf(listOf("2026-10-17", "com.example.a", 4.75, 12000L, 90000L)),
        Tables.WAKEUP_DAYS to listOf(listOf("2026-10-17", "com.example.a", 12000L, 90000L, 3L, 40L, 5L, 6000L, 2L, 0L)),
        Tables.NIGHT_SPANS to listOf(
            listOf(now - 30_000_000L, now - 1_000_000L, 5_000_000L, 34_000_000L, 4_000_000L, 5_000_000L, 0.8, 0.77, 3500.0, null, false, "1,D,1;9,D,0", 3, null),
        ),
        Tables.NIGHTS to listOf(listOf("2026-10-17", "good", "deep_sleep", 480.0, 93.0, 0.5, 20.0, 1, 12.0, null)),
        Tables.STORAGE_DEVICE to listOf(listOf("2026-10-17", now - 86_400_000L, 128_000_000_000L, 40_000_000_000L)),
        Tables.STORAGE_APPS to listOf(listOf("2026-10-17", "com.example.a", now - 86_400_000L, 100L, 200L, 30L)),
        Tables.SENSOR_EVENTS to listOf(listOf("com.example.a", "FINE_LOCATION", now - 900_000L, false, "SCREEN", "flags,x", 4000L)),
        Tables.APP_UPDATES to listOf(listOf("com.example.a", now - 2 * 86_400_000L, "1.0", 10L, "1.1", 11L), listOf("com.example.b", now - 86_400_000L, null, null, "2.0", 20L)),
        Tables.NET_USAGE to listOf(listOf("2026-10-16", "com.example.a", 5_000L, 700L)),
        Tables.NET_DAYS to listOf(listOf("2026-10-16")),
        Tables.THERMAL_EVENTS to listOf(listOf(now - 700_000L, 3, "com.example.game", 1.1, 41.5, null, null, null), listOf(now - 600_000L, -1, null, null, null, null, null, null)),
        Tables.POWER_RAILS to listOf(listOf(now - 900_000L, "[Rail1]:Display", 4_000_000L, 900_000L)),
    )

    private fun export(data: Map<Table, List<List<Any?>>> = everything()): ByteArray {
        val out = ByteArrayOutputStream()
        DataTransfer.write(out, FakeSource(data), info)
        return out.toByteArray()
    }

    private fun inspect(zip: ByteArray) = DataTransfer.inspect(ByteArrayInputStream(zip))

    private fun import(zip: ByteArray, sink: FakeSink, format: Int = DataTransfer.FORMAT) =
        DataTransfer.import(ByteArrayInputStream(zip), format, sink, now)

    /** Stored values the way an import hands them over: Long, Double, String, null. */
    private fun expected(table: Table, row: List<Any?>): List<Any?> = table.stored.zip(row).map { (c, v) ->
        when {
            v == null -> null
            c.type == Column.Type.FLAG -> if (flag(v) == true) 1L else 0L
            c.type == Column.Type.INT || c.type == Column.Type.TIME -> (v as Number).toLong()
            c.type == Column.Type.REAL -> (v as Number).toDouble()
            else -> v.toString()
        }
    }

    private fun assertSameRows(table: Table, sent: List<List<Any?>>, got: List<List<Any?>>) {
        assertEquals("${table.file}: rows", sent.size, got.size)
        for ((a, b) in sent.map { expected(table, it) }.zip(got)) {
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x is Double && y is Double) assertEquals("${table.file}.${table.stored[i].name}", x, y, 1e-9)
                else assertEquals("${table.file}.${table.stored[i].name}", x, y)
            }
        }
    }

    private fun entries(zip: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val z = ZipInputStream(ByteArrayInputStream(zip))
        while (true) {
            val e = z.nextEntry ?: break
            out[e.name] = String(z.readBytes(), Charsets.UTF_8)
        }
        return out
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun summary(format: Int? = DataTransfer.FORMAT, app: String = "Activity+") =
        org.json.JSONObject().put("app", app).apply { if (format != null) put("format", format) }.put("version", "0.5.0").toString()

    // ---- The description of the files ----

    @Test fun tablesAreWellFormed() {
        assertEquals("file names are unique", Tables.ALL.size, Tables.ALL.map { it.file }.toSet().size)
        for (t in Tables.ALL) {
            assertEquals("${t.file}: column names are unique", t.columns.size, t.columns.map { it.name }.toSet().size)
            assertEquals("${t.file}: database columns are unique", t.stored.size, t.stored.map { it.db }.toSet().size)
            for (k in t.key) assertTrue("${t.file}: key $k", t.stored.any { it.name == k })
            if (t.importable) assertTrue("${t.file}: an importable table needs a key", t.key.isNotEmpty() && t.db != null)
            assertTrue(t.firstFormat in 1..DataTransfer.FORMAT)
        }
    }

    @Test fun bookkeepingOfThisPhoneIsNotInAnyFile() {
        // Counter snapshots and baselines belong to the old phone's hardware and boot.
        val tables = Tables.ALL.filter { it.db != null }.map { it.db!!.file to it.dbTable }
        for (name in listOf("last", "meta", "baseline", "known")) assertTrue(name, tables.none { it.second == name })
        assertFalse(Tables.POWER_RAILS.importable)
        assertFalse(Tables.NIGHTS.importable)
    }

    @Test fun everyDatabaseColumnOfAnImportedTableIsWrittenOrDerived() {
        // The sample row used by the other tests fits each table: one value per stored column.
        for ((table, rows) in everything()) assertEquals(table.file, table.stored.size, rows.first().size)
    }

    @Test fun percentIsPercentInTheFileAndAFractionInTheDatabase() {
        val row = Tables.SAMPLES.csvRow(samples(1).first(), zone)
        assertEquals("2026-10-18T09:31:05+02:00", row[0])
        assertEquals(57.12, row[2] as Double, 1e-9)
        assertEquals(43.21, row[6] as Double, 1e-9)
    }

    // ---- The export ----

    @Test fun exportWritesOneFilePerTableAndTheSummaryLast() {
        val files = entries(export())
        assertEquals(Tables.ALL.map { it.file } + "summary.json", files.keys.toList())
        val header = files.getValue("samples.csv").lineSequence().first()
        assertEquals(Tables.SAMPLES.header.joinToString(","), header)
        assertTrue(header.startsWith("time,epoch_ms,battery_percent,"))
    }

    @Test fun summaryNamesFormatVersionDeviceAndRange() {
        val s = org.json.JSONObject(entries(export()).getValue("summary.json"))
        assertEquals("Activity+", s.getString("app"))
        assertEquals(DataTransfer.FORMAT, s.getInt("format"))
        assertEquals("0.6.0", s.getString("version"))
        assertEquals(8, s.getInt("version_code"))
        assertEquals("Pixel 9", s.getJSONObject("device").getString("model"))
        assertEquals(3, s.getJSONObject("files").getInt("samples.csv"))
        assertEquals("2026-10-18T09:31:05+02:00", s.getJSONObject("samples").getString("first"))
        assertEquals("2026-10-18T09:33:05+02:00", s.getJSONObject("samples").getString("last"))
    }

    @Test fun exportWithoutHistoryStillWritesEveryFileWithItsHeader() {
        val files = entries(export(emptyMap()))
        assertEquals(Tables.ALL.size + 1, files.size)
        assertEquals(Tables.SAMPLES.header.joinToString(",") + "\n", files.getValue("samples.csv"))
        assertNull(org.json.JSONObject(files.getValue("summary.json")).optJSONObject("samples"))
    }

    // ---- The round trip ----

    @Test fun whatIsExportedComesBackOnAnEmptyPhone() {
        val zip = export()
        val sink = FakeSink()
        val report = import(zip, sink)
        for ((table, rows) in everything()) {
            if (!table.importable) {
                assertTrue("${table.file} is not imported", sink.of(table).isEmpty())
                continue
            }
            assertSameRows(table, rows, sink.of(table))
        }
        assertEquals(0, report.duplicates)
        assertEquals(0, report.invalid)
        assertEquals(0, report.unreadable)
        assertEquals(everything().filterKeys { it.importable }.values.sumOf { it.size }, report.added)
    }

    @Test fun theSecondImportOfTheSameFileAddsNothing() {
        val zip = export()
        val sink = FakeSink()
        val first = import(zip, sink)
        val snapshot = sink.rows.mapValues { it.value.toList() }
        val second = import(zip, sink)
        assertEquals(0, second.added)
        assertEquals(first.added, second.duplicates)
        assertEquals(snapshot, sink.rows.mapValues { it.value.toList() })
    }

    @Test fun rowsThatAreAlreadyHereAreSkippedAndNeverChanged() {
        val sink = FakeSink()
        // This phone recorded the middle minute itself, with other values.
        val own = samples()[1].toMutableList().also { it[2] = 777.0 }
        sink.rows[Tables.SAMPLES] = mutableListOf(expected(Tables.SAMPLES, own))
        val report = import(export(), sink)
        val got = sink.of(Tables.SAMPLES)
        assertEquals(3, got.size)
        assertEquals(777.0, got.first { it[0] == samples()[1][0] }[2])
        val samplesReport = report.tables.first { it.table === Tables.SAMPLES }
        assertEquals(2, samplesReport.added)
        assertEquals(1, samplesReport.duplicates)
    }

    @Test fun mergingTwoPhonesKeepsBothSetsOfDays() {
        val old = export(mapOf(Tables.APP_ENERGY to listOf(listOf("2026-10-15", "com.a", 5.0, 60.0), listOf("2026-10-16", "com.a", 6.0, 70.0))))
        val sink = FakeSink()
        sink.rows[Tables.APP_ENERGY] = mutableListOf(
            expected(Tables.APP_ENERGY, listOf("2026-10-16", "com.a", 1.0, 10.0)),
            expected(Tables.APP_ENERGY, listOf("2026-10-17", "com.a", 2.0, 20.0)),
        )
        import(old, sink)
        val days = sink.of(Tables.APP_ENERGY).associate { it[0] as String to it[2] as Double }
        assertEquals(mapOf("2026-10-15" to 5.0, "2026-10-16" to 1.0, "2026-10-17" to 2.0), days)
    }

    @Test fun aRepeatedRowInsideTheFileCountsOnce() {
        val twice = samples(1) + samples(1)
        val sink = FakeSink()
        val r = import(export(mapOf(Tables.SAMPLES to twice)), sink)
        assertEquals(1, sink.of(Tables.SAMPLES).size)
        assertEquals(1, r.added)
        assertEquals(1, r.duplicates)
    }

    @Test fun aDatabaseErrorLeavesThatTableAsItWasAndIsReported() {
        val sink = FakeSink()
        sink.failOn = Tables.APP_ENERGY
        try {
            import(export(), sink)
            throw AssertionError("expected the failure to be thrown")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertTrue(sink.of(Tables.APP_ENERGY).isEmpty())
        // The tables before it are kept; a second run adds the rest.
        assertEquals(3, sink.of(Tables.SAMPLES).size)
        sink.failOn = null
        val again = import(export(), sink)
        assertEquals(2, sink.of(Tables.APP_ENERGY).size)
        assertEquals(3, again.tables.first { it.table === Tables.SAMPLES }.duplicates)
    }

    // ---- Rows that cannot be read ----

    private fun samplesCsv(vararg lines: String) =
        (listOf(Tables.SAMPLES.header.joinToString(",")) + lines).joinToString("\n") + "\n"

    private val goodSample = "2026-10-18T09:31:05+02:00,${now - 600_000},57.12,-850.5,31.4,1,43.21,1200,300,,0,0,com.chrome"

    @Test fun unreadableRowsAreCountedAndTheRestIsImported() {
        val csv = samplesCsv(
            goodSample,
            "x,notanumber,57,0,0,1,0,0,0,,0,0,",       // time is not a number
            "x,${now - 500_000},57,0,0,2,0,0,0,,0,0,",  // charging must be 0 or 1
            "x,${now - 400_000},57,0",                    // too short
            "x,0,57,0,0,1,0,0,0,,0,0,",                  // the clock was unset: 1970
            "x,${now + 10 * 86_400_000L},57,0,0,1,0,0,0,,0,0,", // far in the future, would never be pruned
            "x,${now - 300_000},NaN,0,0,1,0,0,0,,0,0,",  // not a number
            "x,${now - 200_000},,,,1,,,,,0,0,",          // gaps in nullable columns are fine
        )
        val sink = FakeSink()
        val r = import(zipOf("samples.csv" to csv, "summary.json" to summary()), sink)
        assertEquals(2, r.added)
        assertEquals(6, r.invalid)
        assertEquals(listOf(now - 600_000, now - 200_000), sink.of(Tables.SAMPLES).map { it[0] })
        val sparse = sink.of(Tables.SAMPLES).last()
        assertNull(sparse[1])
        assertEquals(1L, sparse[4])
    }

    @Test fun aFileMissingAColumnIsSkippedAndReported() {
        val csv = "epoch_ms,battery_percent\n${now - 600_000},50\n"
        val sink = FakeSink()
        val r = import(zipOf("samples.csv" to csv, "summary.json" to summary()), sink)
        assertTrue(sink.of(Tables.SAMPLES).isEmpty())
        assertEquals(1, r.unreadable)
        assertTrue(r.tables.single().missing.contains("power_mw"))
    }

    @Test fun columnsMayComeInAnyOrderWithExtrasAndTheReadableOnesMayBeMissing() {
        val csv = "foreground_app,extra,screen_on,thermal_status,cpu_clock_percent,tx_bytes_per_s,rx_bytes_per_s,memory_used_percent," +
            "charging,temperature_c,power_mw,battery_percent,epoch_ms\n" +
            "com.chrome,?,1,2,50,3,4,60,0,30,-100,80,${now - 600_000}\n"
        val sink = FakeSink()
        import(zipOf("samples.csv" to csv, "summary.json" to summary()), sink)
        val row = sink.of(Tables.SAMPLES).single()
        assertEquals(listOf<Any?>(now - 600_000, 0.8, -100.0, 30.0, 0L, 0.6, 4.0, 3.0, 0.5, 2L, 1L, "com.chrome"), row)
    }

    @Test fun aKeyWithAHoleIsNoRow() {
        val csv = "day,package,energy_mwh,seconds_on_screen\n2026-10-17,,1,2\nnot-a-day,com.a,1,2\n2026-10-17,com.a,1,2\n"
        val sink = FakeSink()
        val r = import(zipOf("app_energy.csv" to csv, "summary.json" to summary()), sink)
        assertEquals(1, r.added)
        assertEquals(2, r.invalid)
    }

    @Test fun textColumnsKeepWhatWasWrittenIncludingEmptyOnes() {
        val sink = FakeSink()
        import(export(mapOf(Tables.SENSOR_EVENTS to listOf(listOf("com.a", "CAMERA", now - 900_000L, true, "SERVICE", "", 0L)))), sink)
        val row = sink.of(Tables.SENSOR_EVENTS).single()
        assertEquals("", row[5]) // flags is NOT NULL in the database
        assertEquals(1L, row[3])
    }

    @Test fun filesInsideAFolderAreFoundToo() {
        val sink = FakeSink()
        val csv = "day,package,energy_mwh,seconds_on_screen\n2026-10-17,com.a,1,2\n"
        import(zipOf("export/app_energy.csv" to csv, "export/summary.json" to summary()), sink)
        assertEquals(1, sink.of(Tables.APP_ENERGY).size)
        assertNotNull(inspect(zipOf("export/app_energy.csv" to csv, "export/summary.json" to summary())).preview)
    }

    // ---- What the user is shown before importing ----

    @Test fun previewNamesDeviceDatesAndRowCounts() {
        val p = inspect(export()).preview!!
        assertEquals(DataTransfer.FORMAT, p.format)
        assertEquals("0.6.0", p.version)
        assertEquals("Pixel 9", p.device)
        assertEquals(now, p.exportedMillis)
        assertEquals(3, p.tables.first { it.table === Tables.SAMPLES }.rows)
        assertFalse(p.partial)
        // Only what will be imported: not the file for reading, not the derived one.
        assertTrue(p.tables.none { it.table === Tables.POWER_RAILS || it.table === Tables.NIGHTS })
        assertEquals(everything().filterKeys { it.importable }.values.sumOf { it.size }, p.totalRows)
    }

    @Test fun previewRangeIsTheSamplesRange() {
        val p = inspect(export()).preview!!
        assertEquals(now - 10 * 60_000L, p.firstMillis)
        assertEquals(now - 8 * 60_000L, p.lastMillis)
    }

    @Test fun anExportWithoutSamplesHasNoRangeButIsStillImportable() {
        val p = inspect(export(mapOf(Tables.APP_ENERGY to everything().getValue(Tables.APP_ENERGY)))).preview!!
        assertNull(p.firstMillis)
        assertNull(p.lastMillis)
        assertEquals(1, p.tables.size)
    }

    // ---- Files that cannot be imported ----

    @Test fun somethingThatIsNoZipIsNotAnExport() {
        val r = inspect("just some text, not a zip".toByteArray()).rejected!!
        assertEquals(DataTransfer.Problem.NOT_AN_EXPORT, r.problem)
        assertEquals(DataTransfer.Problem.NOT_AN_EXPORT, inspect(ByteArray(0)).rejected!!.problem)
    }

    @Test fun aZipWithoutOurSummaryIsNotAnExport() {
        val csv = "day,package,energy_mwh,seconds_on_screen\n2026-10-17,com.a,1,2\n"
        assertEquals(DataTransfer.Problem.NOT_AN_EXPORT, inspect(zipOf("app_energy.csv" to csv)).rejected!!.problem)
        assertEquals(DataTransfer.Problem.NOT_AN_EXPORT, inspect(zipOf("app_energy.csv" to csv, "summary.json" to "{not json")).rejected!!.problem)
        assertEquals(DataTransfer.Problem.NOT_AN_EXPORT, inspect(zipOf("app_energy.csv" to csv, "summary.json" to summary(app = "Other"))).rejected!!.problem)
    }

    @Test fun aNewerFormatSaysSoAndNamesIt() {
        val r = inspect(zipOf("summary.json" to summary(format = DataTransfer.FORMAT + 1))).rejected!!
        assertEquals(DataTransfer.Problem.NEWER_FORMAT, r.problem)
        assertEquals(DataTransfer.FORMAT + 1, r.format)
    }

    @Test fun aFormatThatIsNotANumberFromOneOnIsUnknown() {
        val text = org.json.JSONObject().put("app", "Activity+").put("format", "two").toString()
        assertEquals(DataTransfer.Problem.UNKNOWN_FORMAT, inspect(zipOf("summary.json" to text)).rejected!!.problem)
        assertEquals(DataTransfer.Problem.UNKNOWN_FORMAT, inspect(zipOf("summary.json" to summary(format = 0))).rejected!!.problem)
    }

    @Test fun anExportWithNoHistoryHasNothingToImport() {
        assertEquals(DataTransfer.Problem.NOTHING_TO_IMPORT, inspect(export(emptyMap())).rejected!!.problem)
        // Only the files for reading, or only the unlisted days of network data, do not count either.
        val onlyReading = export(mapOf(Tables.POWER_RAILS to everything().getValue(Tables.POWER_RAILS), Tables.NIGHTS to everything().getValue(Tables.NIGHTS)))
        assertEquals(DataTransfer.Problem.NOTHING_TO_IMPORT, inspect(onlyReading).rejected!!.problem)
        val onlyNetDays = export(mapOf(Tables.NET_DAYS to everything().getValue(Tables.NET_DAYS)))
        assertEquals(DataTransfer.Problem.NOTHING_TO_IMPORT, inspect(onlyNetDays).rejected!!.problem)
    }

    // ---- Exports of Activity+ 0.5 ----

    @Test fun anOldExportWithoutFormatImportsWhatItHasAndSaysItIsPartial() {
        val old = zipOf(
            "samples.csv" to samplesCsv(goodSample),
            "app_energy.csv" to "day,package,energy_mwh,seconds_on_screen\n2026-10-17,com.a,1,2\n",
            // 0.5 wrote the times of sessions only to the second and has no exact start: not importable.
            "sessions.csv" to "start,end,kind,start_percent,end_percent\n2026-10-17T10:00:00+02:00,2026-10-17T11:00:00+02:00,charging,20,80\n",
            "nights.csv" to "date,verdict\n2026-10-17,good\n",
            "summary.json" to summary(format = null),
        )
        val p = inspect(old).preview!!
        assertEquals(1, p.format)
        assertTrue(p.partial)
        assertEquals(listOf(Tables.SAMPLES, Tables.APP_ENERGY), p.tables.map { it.table })
        val sink = FakeSink()
        val r = import(old, sink, p.format)
        assertEquals(2, r.added)
        assertTrue(sink.of(Tables.SESSIONS).isEmpty())
        assertTrue(sink.of(Tables.NIGHT_SPANS).isEmpty())
    }

    @Test fun filesOfALaterFormatInAnOlderFileAreIgnored() {
        val csv = "day,package,mah,cpu_ms,wakelock_ms\n2026-10-17,com.a,1,2,3\n"
        val zip = zipOf("background_days.csv" to csv, "samples.csv" to samplesCsv(goodSample), "summary.json" to summary(format = null))
        assertEquals(listOf(Tables.SAMPLES), inspect(zip).preview!!.tables.map { it.table })
        val sink = FakeSink()
        import(zip, sink, 1)
        assertTrue(sink.of(Tables.BACKGROUND_DAYS).isEmpty())
    }

    // ---- Merger on its own ----

    @Test fun mergerDecidesRowByRow() {
        val existing = hashSetOf(Tables.NET_DAYS.keyOf(listOf("2026-10-16")))
        val m = DataTransfer.Merger(Tables.NET_DAYS, listOf("day"), existing, now)
        val added = ArrayList<List<Any?>>()
        assertEquals(DataTransfer.Verdict.DUPLICATE, m.accept(listOf("2026-10-16")) { added += it })
        assertEquals(DataTransfer.Verdict.ADDED, m.accept(listOf("2026-10-17")) { added += it })
        assertEquals(DataTransfer.Verdict.DUPLICATE, m.accept(listOf("2026-10-17")) { added += it })
        assertEquals(DataTransfer.Verdict.INVALID, m.accept(listOf("yesterday")) { added += it })
        assertEquals(listOf(listOf<Any?>("2026-10-17")), added)
        assertEquals(Triple(1, 2, 1), Triple(m.added, m.duplicates, m.invalid))
    }

    @Test fun everyTableRoundTripsThroughItsOwnCsv() {
        for ((table, rows) in everything()) {
            val text = Csv.line(table.header) + rows.joinToString("") { Csv.line(table.csvRow(it, zone)) }
            val got = ArrayList<List<Any?>>()
            var merger: DataTransfer.Merger? = null
            Csv.forEachRecord(StringReader(text)) { rec ->
                val m = merger
                if (m == null) merger = DataTransfer.Merger(table, rec, HashSet(), now) else m.accept(rec) { got += it }
            }
            assertTrue("${table.file}: ${merger!!.missing}", merger!!.missing.isEmpty())
            if (table.importable) assertSameRows(table, rows, got)
        }
    }
}
