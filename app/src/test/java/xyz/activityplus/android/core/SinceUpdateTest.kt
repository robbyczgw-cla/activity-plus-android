package xyz.activityplus.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.activityplus.android.core.Diagnosis.Kind
import xyz.activityplus.android.core.SinceUpdate.Change
import xyz.activityplus.android.core.SinceUpdate.Direction
import xyz.activityplus.android.core.SinceUpdate.Metric
import xyz.activityplus.android.core.SinceUpdate.Verdict
import java.time.LocalDate
import java.util.Locale

class SinceUpdateTest {
    private val day = 86_400_000L
    private val update = LocalDate.of(2026, 10, 10)
    private val today = LocalDate.of(2026, 10, 18)

    @Before fun locale() { Format.locale = Locale.US }
    @After fun resetLocale() { Format.locale = null }

    private val w = SinceUpdate.windows(update, today, null, null)

    /** Values on the days before and after, in order; the first of each list is the earliest day. */
    private fun series(before: List<Double>, after: List<Double>, windows: SinceUpdate.Windows = w): Map<String, Double> =
        windows.before.takeLast(before.size).zip(before).toMap() + windows.after.zip(after).toMap()

    private fun changed(v: Verdict) = (v as Verdict.Changed).change

    // ---- windows

    @Test fun sevenDaysBeforeAndTheDaysAfterUpToYesterday() {
        assertEquals((3..9).map { "2026-10-%02d".format(it) }, w.before)
        assertEquals((11..17).map { "2026-10-%02d".format(it) }, w.after)
    }

    @Test fun theUpdateDayAndTodayNeverCount() {
        assertFalse("2026-10-10" in w.before + w.after)
        assertFalse("2026-10-18" in w.after)
    }

    @Test fun theAfterWindowEndsAfterTwoWeeks() {
        val late = SinceUpdate.windows(update, update.plusDays(40), null, null)
        assertEquals(14, late.after.size)
        assertEquals("2026-10-24", late.after.last())
    }

    @Test fun anotherUpdateEndsTheWindows() {
        val bounded = SinceUpdate.windows(update, today, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 14))
        // The earlier update's own day is mixed and left out too.
        assertEquals(listOf("2026-10-08", "2026-10-09"), bounded.before)
        assertEquals(listOf("2026-10-11", "2026-10-12", "2026-10-13"), bounded.after)
    }

    @Test fun anUpdateTodayHasNothingAfterIt() {
        assertTrue(SinceUpdate.windows(today, today, null, null).after.isEmpty())
    }

    // ---- medians

    @Test fun medianOfOddEvenAndNothing() {
        assertEquals(3.0, SinceUpdate.median(listOf(9.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, SinceUpdate.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(0.0, SinceUpdate.median(emptyList()), 0.0)
    }

    // ---- daily metrics

    @Test fun aClearIncreaseIsFlagged() {
        val s = series(listOf(10.0, 12.0, 10.0, 11.0, 9.0, 10.0, 12.0), listOf(50.0, 55.0, 48.0, 60.0, 52.0))
        val c = changed(SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
        assertEquals(Direction.UP, c.direction)
        assertEquals(10.0, c.before, 0.0)
        assertEquals(52.0, c.after, 0.0)
        assertEquals(5.2, c.ratio!!, 1e-9)
    }

    @Test fun tinyNumbersDoNotTrigger() {
        // Five times as many, but 4 wakeups a day is nothing.
        val s = series(List(7) { 1.0 }, List(5) { 5.0 })
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
    }

    @Test fun lessThanTwiceIsNoChange() {
        val s = series(List(7) { 100.0 }, List(5) { 150.0 })
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
    }

    @Test fun tooFewDaysOnEitherSideIsNotEnough() {
        assertEquals(Verdict.NotEnough, SinceUpdate.compareDaily(Metric.WAKEUPS, series(List(7) { 10.0 }, listOf(90.0, 90.0)), w))
        assertEquals(Verdict.NotEnough, SinceUpdate.compareDaily(Metric.WAKEUPS, series(listOf(10.0, 10.0), List(5) { 90.0 }), w))
        assertEquals(Verdict.NotEnough, SinceUpdate.compareDaily(Metric.WAKEUPS, emptyMap(), w))
    }

    @Test fun daysWithoutAReadingAreSkippedNotCountedAsZero() {
        // Four measured days before; the other three have no reading at all.
        val s = series(listOf(10.0, 10.0, 10.0, 10.0), List(5) { 90.0 })
        assertEquals(4, s.keys.count { it < "2026-10-10" })
        assertEquals(10.0, changed(SinceUpdate.compareDaily(Metric.WAKEUPS, s, w)).before, 0.0)
    }

    @Test fun oneBusyDayAfterDoesNotMoveTheMedian() {
        val s = series(List(7) { 10.0 }, listOf(10.0, 10.0, 10.0, 10.0, 500.0))
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
    }

    @Test fun anAppThatWasAlreadyThatHighBeforeIsNotFlagged() {
        // Median 10 before, 60 after is six times, but one day before already had 300: it is just jumpy.
        val s = series(listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 300.0), listOf(60.0, 60.0, 60.0))
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
    }

    @Test fun mostDaysAfterMustAgree() {
        // The median (52.5) is far up, but only half of the days are.
        val s = series(List(7) { 10.0 }, listOf(5.0, 5.0, 100.0, 100.0))
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
    }

    @Test fun twoThirdsOfTheDaysAreEnough() {
        val s = series(List(7) { 10.0 }, listOf(5.0, 100.0, 100.0))
        assertEquals(Direction.UP, changed(SinceUpdate.compareDaily(Metric.WAKEUPS, s, w)).direction)
    }

    @Test fun aClearDecreaseIsFlaggedToo() {
        val s = series(listOf(100.0, 95.0, 110.0, 100.0, 105.0, 98.0, 102.0), listOf(20.0, 25.0, 15.0, 22.0))
        val c = changed(SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
        assertEquals(Direction.DOWN, c.direction)
        assertEquals(0.2, c.ratio!!, 0.02)
        assertFalse(c.strong)
    }

    @Test fun anAppThatWasSilentBeforeHasNoRatio() {
        val s = series(List(7) { 0.0 }, listOf(60.0, 70.0, 80.0))
        val c = changed(SinceUpdate.compareDaily(Metric.WAKEUPS, s, w))
        assertNull(c.ratio)
        assertTrue(c.strong)
    }

    // A real case from a phone: Android reported crumbs before the update, and the finding said
    // "uses 8066270× as much battery in the background".
    @Test fun crumbsBeforeAnUpdateCountAsNothingNotAsAFactor() {
        val s = series(List(7) { 0.0000025 }, listOf(18.0, 20.0, 22.0, 21.0))
        val c = changed(SinceUpdate.compareDaily(Metric.BACKGROUND, s, w))
        assertEquals(Direction.UP, c.direction)
        assertNull(c.ratio)
        assertTrue(c.strong)
    }

    @Test fun aHugeIncreaseIsToldAsTheNewAmount() {
        // 1 mAh before is measurable, but 40× is no number anyone can picture.
        assertNull(SinceUpdate.Change(Metric.BACKGROUND, Direction.UP, 1.0, 40.0).ratio)
        assertEquals(8.0, SinceUpdate.Change(Metric.BACKGROUND, Direction.UP, 1.0, 8.0).ratio!!, 1e-9)
        // A drop keeps its ratio: "uses 95 % less" stays meaningful.
        assertEquals(0.025, SinceUpdate.Change(Metric.BACKGROUND, Direction.DOWN, 40.0, 1.0).ratio!!, 1e-9)
    }

    @Test fun backgroundBatteryNeedsFiveMilliampHoursADay() {
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.BACKGROUND, series(List(7) { 1.0 }, List(4) { 4.0 }), w))
        assertEquals(Direction.UP, changed(SinceUpdate.compareDaily(Metric.BACKGROUND, series(List(7) { 1.0 }, List(4) { 8.0 }), w)).direction)
    }

    @Test fun dataIsJudgedInBytesPerDay() {
        val mb = 1_000_000.0
        // 10 -> 60 MB a day: 350 MB more a week.
        val c = changed(SinceUpdate.compareDaily(Metric.MOBILE, series(List(7) { 10 * mb }, List(5) { 60 * mb }), w))
        assertEquals(350 * mb, (c.after - c.before) * 7, 1.0)
        // 10 -> 20 MB is double but below the 15 MB minimum.
        assertEquals(Verdict.NoChange, SinceUpdate.compareDaily(Metric.MOBILE, series(List(7) { 10 * mb }, List(5) { 20 * mb }), w))
    }

    // ---- energy on screen

    @Test fun energyIsPooledPerHourOnScreen() {
        // 1000 mWh in 1 h before, 6000 mWh in 2 h after: 1000 -> 3000 mWh per hour.
        val c = changed(SinceUpdate.compareEnergy(SinceUpdate.Energy(1000.0, 3600.0, 6000.0, 7200.0)))
        assertEquals(Metric.SCREEN_ENERGY, c.metric)
        assertEquals(1000.0, c.before, 1e-9)
        assertEquals(3000.0, c.after, 1e-9)
    }

    @Test fun energyNeedsTwentyMinutesOnScreenOnBothSides() {
        assertEquals(Verdict.NotEnough, SinceUpdate.compareEnergy(SinceUpdate.Energy(1000.0, 1000.0, 6000.0, 7200.0)))
        assertEquals(Verdict.NotEnough, SinceUpdate.compareEnergy(SinceUpdate.Energy(1000.0, 3600.0, 600.0, 600.0)))
    }

    @Test fun energyBelowTheMinimumOrFactorIsNoChange() {
        assertEquals(Verdict.NoChange, SinceUpdate.compareEnergy(SinceUpdate.Energy(100.0, 3600.0, 400.0, 3600.0)))
        assertEquals(Verdict.NoChange, SinceUpdate.compareEnergy(SinceUpdate.Energy(2000.0, 3600.0, 3000.0, 3600.0)))
    }

    @Test fun energyDecreaseIsReported() {
        val c = changed(SinceUpdate.compareEnergy(SinceUpdate.Energy(3000.0, 3600.0, 1000.0, 3600.0)))
        assertEquals(Direction.DOWN, c.direction)
    }

    // ---- storage

    private val gb = 1_000_000_000L

    @Test fun storageUsesTheLastSnapshotBeforeAndTheLastOneSettledAfter() {
        val t = 1_800_000_000_000L
        val snaps = listOf(
            SinceUpdate.StorageAt(t - 12 * day, 1), // too old
            SinceUpdate.StorageAt(t - 3 * day, 100),
            SinceUpdate.StorageAt(t - day, 200), // the one before
            SinceUpdate.StorageAt(t + day, 250), // still too close to the update
            SinceUpdate.StorageAt(t + 3 * day, 300),
            SinceUpdate.StorageAt(t + 6 * day, 400), // the one after
            SinceUpdate.StorageAt(t + 20 * day, 900), // beyond two weeks
        )
        assertEquals(200L to 400L, SinceUpdate.pickStorage(snaps, t, null))
    }

    @Test fun storageAfterStopsAtTheNextUpdate() {
        val t = 1_800_000_000_000L
        val snaps = listOf(SinceUpdate.StorageAt(t - day, 200), SinceUpdate.StorageAt(t + 3 * day, 300), SinceUpdate.StorageAt(t + 8 * day, 900))
        assertEquals(200L to 300L, SinceUpdate.pickStorage(snaps, t, t + 5 * day))
    }

    @Test fun storageNeedsASnapshotOnBothSides() {
        val t = 1_800_000_000_000L
        assertNull(SinceUpdate.pickStorage(listOf(SinceUpdate.StorageAt(t + 3 * day, 300)), t, null))
        assertNull(SinceUpdate.pickStorage(listOf(SinceUpdate.StorageAt(t - day, 300)), t, null))
        assertNull(SinceUpdate.pickStorage(emptyList(), t, null))
    }

    @Test fun storageGrowthOfOnePointTwoGigabytesIsFlagged() {
        val c = changed(SinceUpdate.compareStorage(gb, 2_200_000_000L))
        assertEquals(Metric.STORAGE, c.metric)
        assertEquals(1_200_000_000.0, c.after - c.before, 0.0)
    }

    @Test fun storageNeedsBothSizeAndShare() {
        assertEquals(Verdict.NoChange, SinceUpdate.compareStorage(gb, 1_200_000_000L)) // 200 MB: too little
        assertEquals(Verdict.NoChange, SinceUpdate.compareStorage(5 * gb, 5_400_000_000L)) // 8 %: not clear
        assertEquals(Verdict.NoChange, SinceUpdate.compareStorage(3 * gb, gb)) // smaller is not news
    }

    // ---- result

    private val event = UpdateDetect.Event("com.chat", "2.24.19", 223, "2.24.20", 224, 1_800_000_000_000L)

    @Test fun evaluateCollectsChangesAndCountsJudgedMetrics() {
        val daily = mapOf(
            Metric.WAKEUPS to series(List(7) { 10.0 }, List(5) { 50.0 }),
            Metric.BACKGROUND to series(List(7) { 20.0 }, List(5) { 21.0 }),
            Metric.WIFI to series(List(7) { 1e6 }, listOf(1e6)), // one day after: not enough
        )
        val r = SinceUpdate.evaluate(event, w, daily, SinceUpdate.Energy(0.0, 0.0, 0.0, 0.0), 1L to 5 * gb)
        // Judged: wakeups, background, storage. Not judged: wifi (too few days), energy (no time on screen).
        assertEquals(3, r.evaluated)
        assertEquals(listOf(Metric.STORAGE, Metric.WAKEUPS).toSet(), r.changes.map { it.metric }.toSet())
        assertEquals(7, r.afterDays)
        assertTrue(r.enough)
        assertTrue(r.clear)
    }

    @Test fun nothingMeasuredIsNotTheSameAsNoChange() {
        val none = SinceUpdate.evaluate(event, w, emptyMap(), null, null)
        assertFalse(none.enough)
        assertFalse(none.clear)
        val quiet = SinceUpdate.evaluate(event, w, mapOf(Metric.WAKEUPS to series(List(7) { 10.0 }, List(5) { 10.0 })), null, null)
        assertTrue(quiet.enough)
        assertFalse(quiet.clear)
    }

    @Test fun increasesComeBeforeDecreasesAndTheFurthestFirst() {
        val daily = mapOf(
            Metric.WAKEUPS to series(List(7) { 100.0 }, List(5) { 20.0 }), // down to a fifth
            Metric.BACKGROUND to series(List(7) { 10.0 }, List(5) { 30.0 }), // 3x
            Metric.WAKELOCK to series(List(7) { 600_000.0 }, List(5) { 7_200_000.0 }), // 12x
        )
        val r = SinceUpdate.evaluate(event, w, daily, null, null)
        assertEquals(listOf(Metric.WAKELOCK, Metric.BACKGROUND, Metric.WAKEUPS), r.changes.map { it.metric })
    }

    // ---- strong changes

    @Test fun strongMeansThreeTimesOrMoreAndUp() {
        fun c(b: Double, a: Double, d: Direction = Direction.UP) = Change(Metric.WAKEUPS, d, b, a)
        assertTrue(c(10.0, 30.0).strong)
        assertFalse(c(10.0, 25.0).strong)
        assertTrue(c(0.0, 100.0).strong)
        assertFalse(c(30.0, 5.0, Direction.DOWN).strong)
    }

    @Test fun strongestPicksTheFurthestStrongIncrease() {
        val r = SinceUpdate.Result(
            event,
            listOf(Change(Metric.WAKEUPS, Direction.UP, 10.0, 25.0), Change(Metric.WAKELOCK, Direction.UP, 10.0, 50.0), Change(Metric.BACKGROUND, Direction.UP, 10.0, 40.0)),
            3, 7,
        )
        assertEquals(Metric.WAKELOCK, r.strongest!!.metric)
        assertNull(SinceUpdate.Result(event, listOf(Change(Metric.WAKEUPS, Direction.UP, 10.0, 25.0)), 1, 7).strongest)
    }

    // ---- helpers

    @Test fun factorsReadLikePeopleSayThem() {
        assertEquals("4×", SinceUpdate.times(4.2))
        assertEquals("2.5×", SinceUpdate.times(2.5))
        assertEquals("2×", SinceUpdate.times(2.04))
        assertEquals("37×", SinceUpdate.times(37.0))
    }

    @Test fun neighboursAreTheClosestOtherUpdates() {
        val times = listOf(100L, 300L, 500L)
        assertEquals(100L to 500L, SinceUpdate.neighbours(times, 300L))
        assertEquals(null to 300L, SinceUpdate.neighbours(times, 100L))
        assertEquals(300L to null, SinceUpdate.neighbours(times, 500L))
        assertEquals(null to null, SinceUpdate.neighbours(emptyList(), 100L))
    }

    // ---- diagnosis

    private fun input(sinceUpdate: List<Pair<SinceUpdate.Result, String>>) = Diagnosis.Input(
        60_000_000_000L, 128_000_000_000L, 3_000_000_000L, 250_000_000L, false, ThermalStatus.NONE, 30.0, false, 3600,
        0.95, BatteryHealth.GOOD, 1f, "Chrome", emptyList(), emptyMap(), 4000.0, 3.9, 1_800_000_000_000L, sinceUpdate = sinceUpdate,
    )

    private fun result(pkg: String, vararg changes: Change) =
        SinceUpdate.Result(event.copy(pkg = pkg), changes.toList(), changes.size, 7)

    @Test fun aStrongIncreaseBecomesAFindingWithTheVersionAndAnAppInfoFix() {
        val r = result("com.chat", Change(Metric.WAKEUPS, Direction.UP, 10.0, 42.0))
        val f = Diagnosis.run(input(listOf(r to "Chat"))).single { it.kind == Kind.SINCE_UPDATE }
        assertEquals("Chat", f.app)
        assertEquals("com.chat", f.pkg)
        assertEquals(Diagnosis.Fix.APP_INFO, f.fix)
        assertEquals(Diagnosis.Severity.WARN, f.severity)
        assertEquals(listOf("2.24.20"), f.apps)
        assertEquals(Metric.WAKEUPS.ordinal.toDouble(), f.values["metric"]!!, 0.0)
        assertEquals(10.0, f.values["before"]!!, 0.0)
        assertEquals(42.0, f.values["after"]!!, 0.0)
        assertEquals(event.timeMillis.toDouble(), f.values["time"]!!, 0.0)
    }

    @Test fun dataAndStorageGrowthIsOnlyANote() {
        val r = result("com.chat", Change(Metric.MOBILE, Direction.UP, 1e6, 1e8))
        assertEquals(Diagnosis.Severity.INFO, Diagnosis.run(input(listOf(r to "Chat"))).single { it.kind == Kind.SINCE_UPDATE }.severity)
    }

    @Test fun weakChangesAndImprovementsAreNoFinding() {
        val weak = result("a", Change(Metric.WAKEUPS, Direction.UP, 10.0, 25.0))
        val better = result("b", Change(Metric.WAKEUPS, Direction.DOWN, 100.0, 10.0))
        assertTrue(Diagnosis.run(input(listOf(weak to "A", better to "B"))).none { it.kind == Kind.SINCE_UPDATE })
    }

    @Test fun atMostTwoAppsAndTheFurthestFirst() {
        val list = listOf(
            result("a", Change(Metric.WAKEUPS, Direction.UP, 10.0, 40.0)) to "A",
            result("b", Change(Metric.WAKEUPS, Direction.UP, 10.0, 100.0)) to "B",
            result("c", Change(Metric.WAKEUPS, Direction.UP, 10.0, 70.0)) to "C",
        )
        assertEquals(listOf("B", "C"), Diagnosis.run(input(list)).filter { it.kind == Kind.SINCE_UPDATE }.map { it.app })
    }

    @Test fun twoUpdatesOfOneAppGiveOneFinding() {
        val list = listOf(
            result("a", Change(Metric.WAKEUPS, Direction.UP, 10.0, 40.0)) to "A",
            result("a", Change(Metric.WAKEUPS, Direction.UP, 10.0, 90.0)) to "A",
        )
        assertEquals(1, Diagnosis.run(input(list)).count { it.kind == Kind.SINCE_UPDATE })
    }
}
