package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingHabitsTest {
    private val minute = 60_000L

    /** One stored sample per minute, starting at minute [startMin]; [levels] gives the level of each one. */
    private fun track(levels: List<Double>, startMin: Long = 0L, temp: Double = 25.0, charging: Boolean = false) =
        levels.mapIndexed { i, level -> HabitSample((startMin + i) * minute, level, temp, charging) }

    private fun flat(count: Int, level: Double, startMin: Long = 0L, temp: Double = 25.0, charging: Boolean = false) =
        track(List(count) { level }, startMin, temp, charging)

    /** Three days of unplugged samples at 50 %, the minimum for a summary. */
    private fun threeDays() = flat(4321, 0.5)

    private fun habits(full: Double = 0.0, warm: Double = 0.0, hot: Double = 0.0, deep: Int = 0) = ChargingHabits.Summary(
        observedDays = 7.0, fullSecondsPerDay = full, warmSecondsPerDay = warm, hotSecondsPerDay = hot,
        deepDischarges = deep, chargeCount = 0, typicalStartPercent = null, typicalEndPercent = null,
    )

    @Test fun summaryNeedsThreeObservedDays() {
        assertNull(ChargingHabits.summarize(emptyList(), emptyList()))
        assertNull(ChargingHabits.summarize(flat(2880, 0.5), emptyList()))
        assertNull(ChargingHabits.summarize(flat(4300, 0.5), emptyList()))
        assertNotNull(ChargingHabits.summarize(threeDays(), emptyList()))
    }

    @Test fun pluggedTimeAtNinetyFivePercentIsCountedPerDay() {
        // Six hours on the charger at 100 % (charging status off, the usual full battery), the rest unplugged.
        val samples = flat(1440, 0.5) + flat(361, 1.0, startMin = 1440) + flat(2520, 0.5, startMin = 1801)
        val spans = listOf(PlugSpan(1440 * minute, 1800 * minute, 0.5, 1.0))
        val s = ChargingHabits.summarize(samples, spans)!!
        assertEquals(3.0, s.observedDays, 1e-9)
        assertEquals(7200.0, s.fullSecondsPerDay, 1e-6) // 6 h over 3 days
    }

    @Test fun fullTimeOutsideTheChargerIsNotCounted() {
        val s = ChargingHabits.summarize(flat(4321, 1.0), emptyList())!!
        assertEquals(0.0, s.fullSecondsPerDay, 1e-9)
    }

    @Test fun warmAndHotTimeCountOnlyWhileCharging() {
        // 60 min charging at 36 °C, 30 min charging at 41 °C, 30 min at 41 °C with the charging status off.
        val samples = flat(1440, 0.5) +
            track(List(60) { 0.6 }, startMin = 1440, temp = 36.0, charging = true) +
            track(List(30) { 0.6 }, startMin = 1500, temp = 41.0, charging = true) +
            track(List(30) { 1.0 }, startMin = 1530, temp = 41.0, charging = false) +
            flat(2761, 0.5, startMin = 1560)
        val spans = listOf(PlugSpan(1440 * minute, 1560 * minute, 0.6, 1.0))
        val s = ChargingHabits.summarize(samples, spans)!!
        // Warm: 90 min in total, hot: the last 30 min; per day over three days.
        assertEquals(1800.0, s.warmSecondsPerDay, 1e-6)
        assertEquals(600.0, s.hotSecondsPerDay, 1e-6)
    }

    @Test fun deepDischargesAreCountedWhenTheLevelFallsBelowTenPercentOnBattery() {
        val levels = List(4321) { i -> if (i in 1000..1010 || i in 2000..2010) 0.09 else 0.5 }
        val s = ChargingHabits.summarize(track(levels), emptyList())!!
        assertEquals(2, s.deepDischarges)
    }

    @Test fun deepDischargeOnTheChargerIsNotCounted() {
        val levels = List(4321) { i -> if (i in 1000..1010) 0.09 else 0.5 }
        // The phone was plugged in when it dipped under 10 %, so it is not a discharge on battery.
        val spans = listOf(PlugSpan(999 * minute, 1011 * minute, 0.5, 0.09))
        val s = ChargingHabits.summarize(track(levels), spans)!!
        assertEquals(0, s.deepDischarges)
    }

    @Test fun typicalRangeIsTheMedianOfRealChargesOnly() {
        val spans = listOf(
            PlugSpan(0, 60 * minute, 0.2, 1.0),
            PlugSpan(1000 * minute, 1060 * minute, 0.2, 1.0),
            PlugSpan(2000 * minute, 2060 * minute, 0.3, 1.0),
            // A top-up of three points and a two-minute blip are not charges.
            PlugSpan(3000 * minute, 3060 * minute, 0.97, 1.0),
            PlugSpan(4000 * minute, 4002 * minute, 0.2, 1.0),
        )
        val s = ChargingHabits.summarize(threeDays(), spans)!!
        assertEquals(3, s.chargeCount)
        assertEquals(20, s.typicalStartPercent)
        assertEquals(100, s.typicalEndPercent)
    }

    @Test fun typicalRangeNeedsThreeCharges() {
        val spans = listOf(PlugSpan(0, 60 * minute, 0.2, 1.0), PlugSpan(1000 * minute, 1060 * minute, 0.2, 1.0))
        val s = ChargingHabits.summarize(threeDays(), spans)!!
        assertEquals(2, s.chargeCount)
        assertNull(s.typicalStartPercent)
        assertNull(s.typicalEndPercent)
    }

    @Test fun hotChargingGivesHotAdviceOnly() {
        val a = ChargingHabits.advice(habits(hot = 600.0, warm = 1800.0), null)
        assertEquals(listOf(ChargingHabits.Advice.HOT_CHARGING), a)
    }

    @Test fun warmChargingGivesWarmAdviceBelowTheHotThreshold() {
        assertEquals(listOf(ChargingHabits.Advice.WARM_CHARGING), ChargingHabits.advice(habits(hot = 599.0, warm = 1800.0), null))
        assertEquals(emptyList<ChargingHabits.Advice>(), ChargingHabits.advice(habits(warm = 1799.0), null))
    }

    @Test fun fullChargeAdviceNeedsTwoHoursAndNoChargingPolicy() {
        assertEquals(listOf(ChargingHabits.Advice.FULL_WHILE_PLUGGED), ChargingHabits.advice(habits(full = 7200.0), null))
        assertEquals(emptyList<ChargingHabits.Advice>(), ChargingHabits.advice(habits(full = 7199.0), null))
        assertEquals(
            listOf(ChargingHabits.Advice.FULL_WHILE_PLUGGED),
            ChargingHabits.advice(habits(full = 7200.0), ChargingPolicy.STANDARD),
        )
    }

    @Test fun fullChargeAdviceIsDroppedWhenThePhoneAlreadyLimitsTheCharge() {
        for (policy in listOf(ChargingPolicy.LIMITED, ChargingPolicy.ADAPTIVE, ChargingPolicy.LONG_LIFE)) {
            assertEquals(emptyList<ChargingHabits.Advice>(), ChargingHabits.advice(habits(full = 9000.0), policy))
        }
    }

    @Test fun deepDischargeAdviceNeedsTwo() {
        assertEquals(emptyList<ChargingHabits.Advice>(), ChargingHabits.advice(habits(deep = 1), null))
        assertEquals(listOf(ChargingHabits.Advice.DEEP_DISCHARGES), ChargingHabits.advice(habits(deep = 2), null))
    }

    @Test fun adviceComesHottestFirst() {
        val a = ChargingHabits.advice(habits(full = 8000.0, hot = 900.0, deep = 3), ChargingPolicy.STANDARD)
        assertEquals(
            listOf(
                ChargingHabits.Advice.HOT_CHARGING,
                ChargingHabits.Advice.FULL_WHILE_PLUGGED,
                ChargingHabits.Advice.DEEP_DISCHARGES,
            ),
            a,
        )
    }

    /** Full capacity in percent of 5000 mAh, measured on the given days (every two weeks by default). */
    private fun capacity(percentOf: (Double) -> Double, days: List<Int> = listOf(0, 14, 28, 42, 56, 70)): List<CapacityEstimate> =
        days.map { d -> CapacityEstimate(d * 86_400_000L, 5000.0 * percentOf(d.toDouble()) / 100, 0.2, 0.8) }

    @Test fun ageingWaitsForFiveMeasurementsOverEightWeeks() {
        val four = capacity({ 100.0 }, listOf(0, 14, 28, 56))
        assertEquals(ChargingHabits.Ageing.NotYet(4, 56.0), ChargingHabits.ageing(four, 5000.0))
        val short = capacity({ 100.0 - it / 30.44 }, listOf(0, 7, 14, 21, 28, 35))
        assertEquals(ChargingHabits.Ageing.NotYet(6, 35.0), ChargingHabits.ageing(short, 5000.0))
        assertEquals(ChargingHabits.Ageing.NotYet(0, 0.0), ChargingHabits.ageing(emptyList(), 5000.0))
    }

    @Test fun flatMeasurementsWithinNoiseSayNoClearLoss() {
        // Alternating ±30 mAh around 5000: the scatter is bigger than any slope the points show.
        val noisy = capacity({ 100.0 }, listOf(0, 14, 28, 42, 56, 70))
            .mapIndexed { i, e -> e.copy(mah = 5000.0 + if (i % 2 == 0) 30 else -30) }
        assertEquals(ChargingHabits.Ageing.NoClearLoss, ChargingHabits.ageing(noisy, 5000.0))
    }

    @Test fun linearLossGivesTheMonthsToEightyPercent() {
        // One percent of the rated capacity per month, 97.7 % after 70 days: 17.7 months to 80 %.
        val f = ChargingHabits.ageing(capacity({ 100.0 - it / 30.44 }), 5000.0) as ChargingHabits.Ageing.Forecast
        assertEquals(97.70, f.percentNow, 0.01)
        assertEquals(17.70, f.months, 0.01)
        assertEquals(17.70, f.monthsEarliest, 0.01)
        assertEquals(17.70, f.monthsLatest, 0.01)
        assertTrue(f.rated)
    }

    @Test fun withoutRatedCapacityTheFirstMeasurementIsTheReference() {
        val f = ChargingHabits.ageing(capacity({ 100.0 - it / 30.44 }), null) as ChargingHabits.Ageing.Forecast
        assertEquals(17.70, f.months, 0.01)
        assertTrue(!f.rated)
    }

    @Test fun noisyLossGivesARangeAroundTheForecast() {
        val noisy = capacity({ 100.0 - it / 30.44 }).mapIndexed { i, e -> e.copy(mah = e.mah + if (i % 2 == 0) 5 else -5) }
        val f = ChargingHabits.ageing(noisy, 5000.0) as ChargingHabits.Ageing.Forecast
        assertTrue(f.monthsEarliest < f.months)
        assertTrue(f.months < f.monthsLatest)
        assertEquals(17.7, f.months, 1.0)
    }

    @Test fun batteryAlreadyBelowEightyPercentIsReached() {
        // 84 % now, losing 3 points a month: 77.1 % after 70 days.
        val a = ChargingHabits.ageing(capacity({ 84.0 - 3 * it / 30.44 }), 5000.0)
        assertEquals(77.10, (a as ChargingHabits.Ageing.Reached).percentNow, 0.01)
    }
}
