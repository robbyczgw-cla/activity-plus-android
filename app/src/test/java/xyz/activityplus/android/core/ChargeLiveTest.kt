package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ChargeLiveTest {
    private val now = 10 * 3_600_000L // 10:00
    private val min = 60_000L

    @Before fun locale() {
        Format.locale = Locale.US
    }

    private fun input(
        level: Double = 0.62, status: ChargeStatus = ChargeStatus.CHARGING, limit: Int = 80, powerMw: Double? = 18_000.0,
        toFull: Long? = 3600, pph: Double? = 40.0, tempC: Double = 31.0, wasWarm: Boolean = false, f: Boolean = false,
    ) = ChargeLive.Input(level, status, limit, powerMw, toFull, pph, tempC, wasWarm, f, now)

    @Test fun chargingBelowTheLimitTargetsTheLimitAtTheCurrentRate() {
        val p = ChargeLive.plan(input())
        assertEquals(ChargeLive.Stage.CHARGING, p.stage)
        assertEquals(62, p.percent)
        assertEquals("62 %", p.levelText)
        assertEquals("62 %", p.shortText)
        assertEquals("18.0 W", p.powerText)
        assertEquals(80, p.limitPoint)
        assertEquals(ChargeLive.Target.LIMIT, p.target)
        assertEquals(80, p.targetPercent)
        // 18 points at 40 points per hour: 27 minutes.
        assertEquals(now + 27 * min, p.targetMillis)
    }

    @Test fun aboveTheLimitTheTargetIsFullAndThePointStays() {
        val p = ChargeLive.plan(input(level = 0.85, toFull = 2400))
        assertEquals(ChargeLive.Target.FULL, p.target)
        assertEquals(100, p.targetPercent)
        assertEquals(now + 40 * min, p.targetMillis)
        assertEquals(80, p.limitPoint)
    }

    @Test fun withoutALimitThereIsNoPointAndFullIsTheTarget() {
        val p = ChargeLive.plan(input(limit = 0))
        assertNull(p.limitPoint)
        assertEquals(ChargeLive.Target.FULL, p.target)
        assertEquals(now + 60 * min, p.targetMillis)
    }

    @Test fun aLimitOfHundredIsNoLimit() {
        assertNull(ChargeLive.plan(input(limit = 100)).limitPoint)
    }

    @Test fun limitTimeNeverExceedsTimeToFull() {
        // A slow reading (5 points per hour) would say 3.6 h, but the system expects full in 30 min.
        val p = ChargeLive.plan(input(pph = 5.0, toFull = 1800))
        assertEquals(now + 30 * min, p.targetMillis)
    }

    @Test fun limitTimeFallsBackToAShareOfTheFullEstimateWithoutARate() {
        // 18 of the 38 missing points to full, of 60 minutes: 28.4 min, rounded to the minute.
        val p = ChargeLive.plan(input(pph = null, toFull = 3600))
        assertEquals(ChargeLive.Target.LIMIT, p.target)
        assertEquals(now + 28 * min, p.targetMillis)
    }

    @Test fun unknownTimeLeavesTheTargetWithoutAClockTime() {
        val p = ChargeLive.plan(input(pph = null, toFull = null))
        assertEquals(ChargeLive.Target.LIMIT, p.target)
        assertNull(p.targetMillis)
        assertNull(ChargeLive.plan(input(level = 0.9, toFull = null)).targetMillis)
    }

    @Test fun estimatesOfMoreThanADayAreNotShown() {
        assertNull(ChargeLive.plan(input(level = 0.9, toFull = 30 * 3600L)).targetMillis)
        assertNull(ChargeLive.plan(input(pph = 0.5, toFull = null)).targetMillis)
    }

    @Test fun aTrickleRateGivesNoLimitTime() {
        assertNull(ChargeLive.plan(input(pph = 0.2, toFull = null)).targetMillis)
    }

    @Test fun unknownPowerHasNoPowerText() {
        assertNull(ChargeLive.plan(input(powerMw = null)).powerText)
    }

    @Test fun powerFlowingOutOfThePhoneIsNotShownAsCharging() {
        assertNull(ChargeLive.plan(input(powerMw = -500.0)).powerText)
        assertNull(ChargeLive.plan(input(powerMw = 0.0)).powerText)
    }

    @Test fun smallPowerIsShownInMilliwatts() {
        assertEquals("800 mW", ChargeLive.plan(input(powerMw = 800.0)).powerText)
    }

    @Test fun fullBatteryHasNoTargetAndSaysFull() {
        val p = ChargeLive.plan(input(level = 1.0, status = ChargeStatus.FULL, toFull = null))
        assertEquals(ChargeLive.Stage.FULL, p.stage)
        assertEquals(ChargeLive.Target.NONE, p.target)
        assertNull(p.targetMillis)
        assertEquals(100, p.percent)
        assertEquals("100 %", p.shortText)
    }

    @Test fun hundredPercentCountsAsFullEvenIfTheStatusLags() {
        assertEquals(ChargeLive.Stage.FULL, ChargeLive.plan(input(level = 0.996)).stage)
    }

    @Test fun pluggedInButNotChargingIsHoldingWithoutTarget() {
        val p = ChargeLive.plan(input(level = 0.8, status = ChargeStatus.NOT_CHARGING))
        assertEquals(ChargeLive.Stage.HOLDING, p.stage)
        assertEquals(ChargeLive.Target.NONE, p.target)
        assertNull(p.targetMillis)
    }

    @Test fun warmBatteryReplacesTheChipTextWithTheTemperature() {
        val p = ChargeLive.plan(input(tempC = 41.0))
        assertTrue(p.warm)
        assertEquals("41 °C", p.shortText)
        assertEquals("62 %", p.levelText)
    }

    @Test fun warmThresholdIsTheSameAsTheAlarms() {
        assertTrue(ChargeLive.plan(input(tempC = ChargeAlarmLogic.WARM_C)).warm)
        assertFalse(ChargeLive.plan(input(tempC = ChargeAlarmLogic.WARM_C - 0.1)).warm)
    }

    @Test fun warmStaysUntilTheBatteryCoolsTwoDegrees() {
        assertTrue(ChargeLive.plan(input(tempC = 38.5, wasWarm = true)).warm)
        assertFalse(ChargeLive.plan(input(tempC = 37.9, wasWarm = true)).warm)
        assertFalse(ChargeLive.plan(input(tempC = 38.5, wasWarm = false)).warm)
    }

    @Test fun temperatureFollowsTheFahrenheitSetting() {
        assertEquals("106 °F", ChargeLive.plan(input(tempC = 41.0, f = true)).shortText)
    }

    @Test fun clockTimeIsRoundedToTheMinute() {
        // 26 min 40 s to the limit: shown as the nearest full minute.
        val p = ChargeLive.plan(input(pph = 40.5))
        assertEquals(0L, p.targetMillis!! % min)
    }

    // --- shouldPost ---

    private fun plan(power: Double = 18_000.0, stage: ChargeStatus = ChargeStatus.CHARGING, temp: Double = 31.0) =
        ChargeLive.plan(input(powerMw = power, status = stage, tempC = temp))

    @Test fun firstPlanIsPostedAtOnce() {
        assertTrue(ChargeLive.shouldPost(null, plan(), 0, now))
    }

    @Test fun anUnchangedPlanIsNeverPostedAgain() {
        assertFalse(ChargeLive.shouldPost(plan(), plan(), 0, now + 10 * min))
    }

    @Test fun changedNumbersWaitTenSeconds() {
        assertFalse(ChargeLive.shouldPost(plan(), plan(power = 19_000.0), now, now + 9_999))
        assertTrue(ChargeLive.shouldPost(plan(), plan(power = 19_000.0), now, now + 10_000))
    }

    @Test fun aNewStageOrHeatIsPostedAtOnce() {
        assertTrue(ChargeLive.shouldPost(plan(), plan(stage = ChargeStatus.NOT_CHARGING), now, now + 1))
        assertTrue(ChargeLive.shouldPost(plan(), plan(temp = 42.0), now, now + 1))
    }

    // --- gate ---

    private val t = 60_000L

    @Test fun gateIsOnWhileCharging() {
        val g = ChargeLiveGate()
        assertTrue(g.onSample(0, plugged = true, charging = true))
        assertTrue(g.onSample(100 * t, plugged = true, charging = true))
    }

    @Test fun gateIsOffWhenUnplugged() {
        val g = ChargeLiveGate()
        assertFalse(g.onSample(0, plugged = false, charging = false))
        assertTrue(g.onSample(t, plugged = true, charging = true))
        assertFalse(g.onSample(2 * t, plugged = false, charging = false))
    }

    @Test fun gateClosesAfterTenMinutesWithoutChargingAndReopensWhenChargingResumes() {
        val g = ChargeLiveGate()
        assertTrue(g.onSample(0, plugged = true, charging = true))
        assertTrue(g.onSample(t, plugged = true, charging = false))
        assertTrue(g.onSample(10 * t, plugged = true, charging = false))
        assertFalse(g.onSample(11 * t, plugged = true, charging = false))
        assertFalse(g.onSample(300 * t, plugged = true, charging = false))
        assertTrue(g.onSample(301 * t, plugged = true, charging = true))
        // The grace starts over with the next pause.
        assertTrue(g.onSample(302 * t, plugged = true, charging = false))
    }

    @Test fun aShortPauseDoesNotRestartTheGraceClock() {
        val g = ChargeLiveGate()
        assertTrue(g.onSample(0, plugged = true, charging = false))
        assertTrue(g.onSample(9 * t, plugged = true, charging = false))
        assertFalse(g.onSample(11 * t, plugged = true, charging = false))
    }
}
