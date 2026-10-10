package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.StorageForecast.Kind
import xyz.activityplus.android.core.StorageGrowth.DAY_MS
import xyz.activityplus.android.core.StorageGrowth.MB
import java.time.LocalDate
import java.time.LocalDateTime

class StorageGrowthTest {
    private val start = LocalDate.of(2026, 9, 1)
    private val gb = 1000 * MB

    // --- Schedule

    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0)
    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    @Test fun firstRunIsDueAtOnce() {
        assertTrue(StorageGrowth.due(at(10, 1), null))
    }

    @Test fun oncePerDay() {
        assertFalse(StorageGrowth.due(at(10, 14), day(10)))
    }

    @Test fun waitsForThreeOClockAfterYesterday() {
        assertFalse(StorageGrowth.due(at(10, 2), day(9)))
        assertTrue(StorageGrowth.due(at(10, 3), day(9)))
        assertTrue(StorageGrowth.due(at(10, 23), day(9)))
    }

    @Test fun missedDayIsCaughtUpAtFirstStart() {
        assertTrue(StorageGrowth.due(at(10, 0), day(8)))
    }

    // --- Growth

    private fun snap(dayIndex: Int, vararg apps: Pair<String, AppStorageSize>) =
        AppStorageSnapshot(start.plusDays(dayIndex.toLong()), dayIndex * DAY_MS + 4 * 3_600_000L, apps.toMap())

    private fun size(app: Long, data: Long, cache: Long = 0) = AppStorageSize(app, data, cache)

    @Test fun growthIsAppPlusDataWithCacheSeparate() {
        val list = listOf(
            snap(0, "a" to size(100 * MB, 100 * MB, 50 * MB)),
            snap(30, "a" to size(100 * MB, 300 * MB, 1_050 * MB)),
        )
        val g = StorageGrowth.growth(list, 30).single()
        assertEquals(200 * MB, g.growthBytes)
        assertEquals(1.0, g.percent!!, 1e-9)
        assertEquals(1_000 * MB, g.cacheGrowthBytes)
        assertFalse(g.isNew)
        assertEquals(30.0, g.spanDays, 1e-9)
    }

    @Test fun cacheOnlySwingIsNoGrowth() {
        val list = listOf(snap(0, "a" to size(MB, MB, 0)), snap(7, "a" to size(MB, MB, 3 * gb)))
        assertEquals(0L, StorageGrowth.growth(list, 7).single().growthBytes)
        assertTrue(StorageGrowth.unusual(list).isEmpty())
    }

    @Test fun weekCountsFromTheSnapshotSevenDaysBack() {
        val list = (0..30).map { snap(it, "a" to size(0, it * 10 * MB)) }
        assertEquals(70 * MB, StorageGrowth.growth(list, 7).single().growthBytes)
        assertEquals(300 * MB, StorageGrowth.growth(list, 30).single().growthBytes)
    }

    @Test fun snapshotTakenEarlierInTheDayStillCountsAsSevenDaysAgo() {
        val early = AppStorageSnapshot(start, 2 * 3_600_000L, mapOf("a" to size(0, 0)))
        val late = AppStorageSnapshot(start.plusDays(7), 7 * DAY_MS + 5 * 3_600_000L, mapOf("a" to size(0, 70 * MB)))
        assertEquals(70 * MB, StorageGrowth.growth(listOf(early, late), 7).single().growthBytes)
    }

    @Test fun shortHistoryUsesTheOldestSnapshot() {
        val list = listOf(snap(0, "a" to size(0, 0)), snap(3, "a" to size(0, 30 * MB)))
        val g = StorageGrowth.growth(list, 30).single()
        assertEquals(30 * MB, g.growthBytes)
        assertEquals(3.0, g.spanDays, 1e-9)
    }

    @Test fun newAppsCountWholeAndRemovedAppsAreLeftOut() {
        val list = listOf(snap(0, "gone" to size(gb, 0)), snap(5, "new" to size(gb, gb)))
        val g = StorageGrowth.growth(list, 30).single()
        assertEquals("new", g.pkg)
        assertTrue(g.isNew)
        assertNull(g.percent)
        assertEquals(2 * gb, g.growthBytes)
    }

    @Test fun biggestFirst() {
        val list = listOf(
            snap(0, "a" to size(0, 0), "b" to size(0, 0)),
            snap(1, "a" to size(0, 10 * MB), "b" to size(0, 20 * MB)),
        )
        assertEquals(listOf("b", "a"), StorageGrowth.growth(list, 30).map { it.pkg })
    }

    @Test fun noGrowthFromASingleSnapshot() {
        assertTrue(StorageGrowth.growth(listOf(snap(0, "a" to size(1, 1))), 7).isEmpty())
        assertTrue(StorageGrowth.growth(emptyList(), 7).isEmpty())
    }

    // --- Unusual

    /** [perDay] for each day index; the last entry is the latest day. */
    private fun history(perDay: List<Long>): List<AppStorageSnapshot> {
        var total = gb
        return perDay.mapIndexed { i, d -> total += d; snap(i, "a" to size(0, total)) }
    }

    @Test fun fiveHundredMbInADayIsUnusual() {
        assertEquals(setOf("a"), StorageGrowth.unusual(history(listOf(0, 500 * MB))))
    }

    @Test fun fiveHundredMbInAWeekIsUnusual() {
        assertEquals(setOf("a"), StorageGrowth.unusual(history(List(8) { if (it == 0) 0 else 75 * MB })))
    }

    @Test fun threeTimesTheUsualDayIsUnusual() {
        // 10 MB a day for three weeks, then 150 MB.
        assertEquals(setOf("a"), StorageGrowth.unusual(history(List(21) { 10 * MB } + 150 * MB)))
    }

    @Test fun steadyGrowthIsNotUnusual() {
        // 50 MB a day, and a day with 120 MB: under three times the usual.
        assertTrue(StorageGrowth.unusual(history(List(21) { 50 * MB } + 120 * MB)).isEmpty())
        assertTrue(StorageGrowth.unusual(history(List(30) { 60 * MB })).isEmpty())
    }

    @Test fun smallJumpsAreNotUnusual() {
        // From 1 MB a day to 80 MB: a big factor, but under 100 MB.
        assertTrue(StorageGrowth.unusual(history(List(21) { MB } + 80 * MB)).isEmpty())
    }

    @Test fun relativeRuleNeedsAWeekOfHistory() {
        assertTrue(StorageGrowth.unusual(history(listOf(0L, 10 * MB, 10 * MB, 300 * MB))).isEmpty())
    }

    @Test fun aFasterWeekIsUnusual() {
        // 5 MB a day for two weeks, then 30 MB a day for a week (210 MB, more than 3 × 35 MB).
        assertEquals(setOf("a"), StorageGrowth.unusual(history(List(15) { 5 * MB } + List(7) { 30 * MB })))
    }

    @Test fun freshInstallsAreNotUnusual() {
        val list = listOf(snap(0, "a" to size(0, 0)), snap(1, "a" to size(0, 0), "game" to size(3 * gb, gb)))
        assertTrue(StorageGrowth.unusual(list).isEmpty())
    }

    // --- Forecast

    private fun device(days: Int, total: Long = 128 * gb, free: (Int) -> Long) =
        (0 until days).map { DeviceStorageDay(start.plusDays(it.toLong()), it * DAY_MS, total, free(it)) }

    @Test fun fallingTrendGivesDaysLeft() {
        // 1 GB a day less, 45 GB left at the end.
        val f = StorageGrowth.forecast(device(14) { 58 * gb - it * gb })
        assertEquals(Kind.FILLING, f.kind)
        assertEquals(45, f.daysLeft)
        assertEquals(-1.0 * gb, f.bytesPerDay, 1.0)
    }

    @Test fun flatTrendIsStable() {
        val f = StorageGrowth.forecast(device(20) { 40 * gb })
        assertEquals(Kind.STABLE, f.kind)
        assertNull(f.daysLeft)
    }

    @Test fun noiseWithoutTrendIsStable() {
        // Up and down by 2 GB around 40 GB with a tiny drift: a poor fit.
        val f = StorageGrowth.forecast(device(20) { 40 * gb + (if (it % 2 == 0) 2 * gb else -2 * gb) - it * 20 * MB })
        assertEquals(Kind.STABLE, f.kind)
    }

    @Test fun noisyButClearFallStillCounts() {
        val f = StorageGrowth.forecast(device(20) { 60 * gb - it * gb + (if (it % 2 == 0) 200 * MB else -200 * MB) })
        assertEquals(Kind.FILLING, f.kind)
    }

    @Test fun tooFewPointsGiveNoForecast() {
        assertEquals(Kind.TOO_FEW, StorageGrowth.forecast(device(6) { 58 * gb - it * gb }).kind)
        assertEquals(Kind.TOO_FEW, StorageGrowth.forecast(emptyList()).kind)
    }

    @Test fun slowFallBeyondAYearIsStable() {
        // 50 MB a day with 40 GB free: 800 days.
        assertEquals(Kind.STABLE, StorageGrowth.forecast(device(20) { 41 * gb - it * 50 * MB }).kind)
    }

    @Test fun risingFreeSpaceIsFreeing() {
        assertEquals(Kind.FREEING, StorageGrowth.forecast(device(10) { 20 * gb + it * gb }).kind)
    }

    @Test fun onlyTheLastThirtyDaysCount() {
        // Falling fast long ago, flat for the last 31 days.
        val days = device(60) { if (it < 29) 100 * gb - it * 2 * gb else 42 * gb }
        assertEquals(Kind.STABLE, StorageGrowth.forecast(days).kind)
    }

    @Test fun fitOfALineIsExact() {
        val (slope, r2) = StorageGrowth.fit(listOf(0.0, 1.0, 2.0, 3.0), listOf(10.0, 8.0, 6.0, 4.0))!!
        assertEquals(-2.0, slope, 1e-9)
        assertEquals(1.0, r2, 1e-9)
        assertNull(StorageGrowth.fit(listOf(1.0, 1.0), listOf(1.0, 2.0)))
        assertNull(StorageGrowth.fit(listOf(1.0), listOf(1.0)))
    }

    // --- Sparkline

    @Test fun usedSeriesHasGapsForMissingDays() {
        val today = start.plusDays(4)
        val days = listOf(
            DeviceStorageDay(start.plusDays(2), 0, 100, 40),
            DeviceStorageDay(today, 0, 100, 30),
        )
        assertEquals(listOf(null, null, 60.0, null, 70.0), StorageGrowth.usedSeries(days, today, 5))
    }
}
