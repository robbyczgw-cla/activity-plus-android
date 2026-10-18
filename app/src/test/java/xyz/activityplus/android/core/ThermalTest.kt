package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Thermal.Episode
import xyz.activityplus.android.core.Thermal.Event
import xyz.activityplus.android.core.Thermal.Minute
import xyz.activityplus.android.core.ThermalStatus.CRITICAL
import xyz.activityplus.android.core.ThermalStatus.LIGHT
import xyz.activityplus.android.core.ThermalStatus.MODERATE
import xyz.activityplus.android.core.ThermalStatus.NONE
import xyz.activityplus.android.core.ThermalStatus.SEVERE
import java.time.ZoneOffset

class ThermalTest {
    private val min = 60_000L
    private val t0 = 1_700_000_000_000L / min * min
    private val zone = ZoneOffset.UTC

    private fun ev(minute: Long, status: ThermalStatus?, pkg: String? = null, sensor: String? = null) =
        Event(t0 + minute * min, status, pkg, sensor = sensor)

    @Test fun anEpisodeRunsFromModerateToTheNextLowerStatus() {
        val e = Thermal.episodes(listOf(ev(0, NONE), ev(10, MODERATE, "game"), ev(30, LIGHT)), t0 + 60 * min)
        assertEquals(1, e.size)
        assertEquals(t0 + 10 * min, e[0].startMillis)
        assertEquals(t0 + 30 * min, e[0].endMillis)
        assertEquals(20 * min, e[0].durationMillis)
        assertEquals("game", e[0].pkg)
        assertFalse(e[0].ongoing)
    }

    @Test fun lightIsNotThrottling() {
        assertTrue(Thermal.episodes(listOf(ev(0, LIGHT), ev(5, NONE)), t0 + 10 * min).isEmpty())
        assertTrue(Thermal.episodes(emptyList(), t0).isEmpty())
    }

    @Test fun theWorstStatusIsThePeakAndNamesTheReason() {
        val e = Thermal.episodes(
            listOf(ev(0, MODERATE, "a", sensor = "skin"), ev(5, SEVERE, "a", sensor = "cpu"), ev(8, MODERATE, "a"), ev(20, NONE)),
            t0 + 30 * min,
        ).single()
        assertEquals(SEVERE, e.peak)
        assertEquals("cpu", e.sensor)
        // The app it began under stays, whatever was on screen later.
        assertEquals("a", e.pkg)
    }

    @Test fun anOpenEpisodeRunsUntilNow() {
        val e = Thermal.episodes(listOf(ev(0, MODERATE, "a")), t0 + 15 * min).single()
        assertTrue(e.ongoing)
        assertEquals(15 * min, e.durationMillis)
    }

    @Test fun aGapEndsTheEpisodeWhereTheMonitorStopped() {
        // The service died at minute 12 and came back at minute 400 with the phone cool.
        val e = Thermal.episodes(listOf(ev(0, MODERATE), ev(12, null), ev(400, NONE)), t0 + 500 * min).single()
        assertEquals(12 * min, e.durationMillis)
        assertFalse(e.ongoing)
    }

    @Test fun aFlappingStatusIsOneEpisode() {
        val e = Thermal.episodes(
            listOf(ev(0, MODERATE), ev(10, LIGHT), ev(11, MODERATE), ev(20, NONE), ev(60, MODERATE), ev(65, NONE)),
            t0 + 100 * min,
        )
        assertEquals(2, e.size)
        assertEquals(20 * min, e[0].durationMillis)
        assertEquals(5 * min, e[1].durationMillis)
    }

    @Test fun sinceCutsTheFirstEpisodeAndDropsOlderOnes() {
        val all = listOf(
            Episode(t0, t0 + 10 * min, MODERATE, null, false),
            Episode(t0 + 50 * min, t0 + 90 * min, SEVERE, null, false),
        )
        val cut = Thermal.since(all, t0 + 60 * min)
        assertEquals(1, cut.size)
        assertEquals(30 * min, cut[0].durationMillis)
        val s = Thermal.summary(listOf(all[0], all[1]))
        assertEquals(2, s.count)
        assertEquals(50 * min, s.totalMillis)
        assertEquals(40 * min, s.longestMillis)
        assertEquals(SEVERE, s.peak)
        assertNull(Thermal.summary(emptyList()).peak)
    }

    private fun minutes(pkg: String?, from: Long, count: Int, temp: Double, powerMw: Double? = -5000.0, screenOn: Boolean = true, charging: Boolean = false) =
        (0 until count).map { Minute(t0 + (from + it) * min, pkg, screenOn, temp + it * 0.1, powerMw, charging) }

    @Test fun heatPerAppAveragesWhatHappenedOnScreen() {
        val rows = minutes("game", 0, 30, 38.0, -6800.0) + minutes("mail", 40, 12, 31.0, -1200.0)
        val apps = Thermal.perApp(rows, emptyList(), zone)
        val game = apps.first { it.pkg == "game" }
        assertEquals(30, game.minutes)
        assertEquals(6800.0, game.avgPowerMw!!, 1e-9)
        assertEquals(38.0 + 2.9, game.maxTempC, 1e-9)
        assertEquals(38.0 + 1.45, game.avgTempC, 1e-9)
        assertEquals(0, game.throttledMinutes)
        assertNull(game.throttledAfterMinutes)
        assertFalse(game.regularly)
    }

    @Test fun screenOffUnknownAppsAndGlancesAreLeftOut() {
        val rows = minutes("a", 0, 30, 40.0, screenOn = false) + minutes(null, 40, 30, 40.0) + minutes("glance", 80, 9, 40.0)
        assertTrue(Thermal.perApp(rows, emptyList(), zone).isEmpty())
    }

    @Test fun powerCountsOnlyDischargingMinutes() {
        val rows = minutes("a", 0, 10, 35.0, -4000.0) + minutes("a", 10, 10, 35.0, 9000.0, charging = true) + minutes("a", 20, 10, 35.0, null)
        assertEquals(4000.0, Thermal.perApp(rows, emptyList(), zone).single().avgPowerMw!!, 1e-9)
        val charging = minutes("b", 0, 10, 35.0, 9000.0, charging = true)
        assertNull(Thermal.perApp(charging, emptyList(), zone).single().avgPowerMw)
    }

    @Test fun throttledAfterIsMeasuredFromTheStartOfTheRun() {
        val rows = minutes("game", 0, 45, 40.0)
        val ep = Episode(t0 + 20 * min, t0 + 35 * min, MODERATE, "game", false)
        val game = Thermal.perApp(rows, listOf(ep), zone).single()
        assertEquals(15, game.throttledMinutes)
        assertEquals(1, game.episodes)
        assertEquals(20, game.throttledAfterMinutes)
        assertEquals(20, game.runs.single().throttledAfterMinutes)
    }

    @Test fun anEpisodeBeforeTheRunCountsAsThrottledFromTheStart() {
        val rows = minutes("game", 10, 20, 40.0)
        val ep = Episode(t0, t0 + 15 * min, MODERATE, null, false)
        assertEquals(0, Thermal.perApp(rows, listOf(ep), zone).single().throttledAfterMinutes)
    }

    @Test fun aMissingMinuteCutsTheRunInTwo() {
        val rows = minutes("a", 0, 10, 36.0) + minutes("a", 30, 10, 38.0)
        val runs = Thermal.perApp(rows, emptyList(), zone).single().runs
        assertEquals(2, runs.size)
        // Newest first.
        assertEquals(t0 + 30 * min, runs[0].startMillis)
        assertEquals(10, runs[1].minutes)
    }

    @Test fun typicalTimeToThrottlingIsTheMiddleRun() {
        val a = Thermal.AppHeat("a", 0, 0.0, 0.0, null, 0, 0, 0, listOf(
            Thermal.Run(0, 10, null, 40.0, 5), Thermal.Run(0, 10, null, 41.0, null),
            Thermal.Run(0, 10, null, 42.0, 30), Thermal.Run(0, 10, null, 39.0, 12),
        ))
        // 5, 12, 30: the middle one.
        assertEquals(12, a.throttledAfterMinutes)
        assertEquals(42.0, a.hottestRun!!.maxTempC, 0.0)
    }

    @Test fun rankingPutsTheMostThrottledAppFirst() {
        val rows = minutes("cool", 0, 20, 30.0) + minutes("warm", 100, 20, 41.0) + minutes("game", 200, 20, 39.0)
        val eps = listOf(Episode(t0 + 205 * min, t0 + 215 * min, MODERATE, "game", false))
        assertEquals(listOf("game", "warm", "cool"), Thermal.perApp(rows, eps, zone).map { it.pkg })
    }

    @Test fun regularMeansSeveralEpisodesOnSeveralDaysAndEnoughTime() {
        assertTrue(Thermal.regularly(3, 2, 10))
        assertFalse("one long marathon", Thermal.regularly(5, 1, 60))
        assertFalse("two episodes", Thermal.regularly(2, 2, 60))
        assertFalse("brushes only", Thermal.regularly(3, 2, 5))
    }

    @Test fun anAppThatThrottlesOnTwoDaysIsRegular() {
        val day = 24 * 60L
        val rows = (0 until 3).flatMap { minutes("game", it * day / 2 * 1, 25, 41.0) }
        // Three sessions: the first two on day one (0 and 12 h), the third on day two (24 h).
        val eps = listOf(0L, day / 2, day).map { Episode(t0 + (it + 5) * min, t0 + (it + 15) * min, MODERATE, "game", false) }
        val game = Thermal.perApp(rows, eps, zone).single()
        assertEquals(3, game.episodes)
        assertEquals(30, game.throttledMinutes)
        assertEquals(listOf(game), Thermal.regular(listOf(game)))
    }
}
