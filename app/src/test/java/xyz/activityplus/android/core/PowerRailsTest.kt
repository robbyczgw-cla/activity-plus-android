package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.PowerRails.COARSE_NOISE_UWS
import xyz.activityplus.android.core.PowerRails.SLOT_MS
import xyz.activityplus.android.core.PowerRails.Step

class PowerRailsTest {
    // --- Categories

    @Test fun modeledConsumerNames() {
        assertEquals(RailCategory.GPU, PowerRails.category("GPU"))
        assertEquals(RailCategory.MOBILE_RADIO, PowerRails.category("MOBILE_RADIO"))
        assertEquals(RailCategory.DISPLAY, PowerRails.category("DISPLAY"))
        assertEquals(RailCategory.CPU, PowerRails.category("CPU_CLUSTER"))
        assertEquals(RailCategory.WIFI_BT, PowerRails.category("WIFI"))
        assertEquals(RailCategory.WIFI_BT, PowerRails.category("BLUETOOTH"))
        assertEquals(RailCategory.GNSS, PowerRails.category("GNSS"))
        assertEquals(RailCategory.CAMERA, PowerRails.category("CAMERA"))
        assertEquals(RailCategory.OTHER, PowerRails.category("OTHER"))
    }

    @Test fun emulatorRails() {
        assertEquals(RailCategory.DISPLAY, PowerRails.category("[Rail1]:Display"))
        assertEquals(RailCategory.CPU, PowerRails.category("[Rail2]:CPU"))
        assertEquals(RailCategory.MOBILE_RADIO, PowerRails.category("[Rail3]:Modem"))
    }

    @Test fun pixelRails() {
        assertEquals(RailCategory.CPU, PowerRails.category("S4M_VDD_CPUCL0"))
        assertEquals(RailCategory.CPU, PowerRails.category("S3M_VDD_CPUCL1"))
        assertEquals(RailCategory.CPU, PowerRails.category("S2M_VDD_CPUCL2"))
        assertEquals(RailCategory.MEMORY, PowerRails.category("S1M_VDD_MIF"))
        assertEquals(RailCategory.DISPLAY, PowerRails.category("VSYS_PWR_DISPLAY"))
        assertEquals(RailCategory.MOBILE_RADIO, PowerRails.category("VSYS_PWR_MODEM"))
        assertEquals(RailCategory.MOBILE_RADIO, PowerRails.category("VSYS_PWR_RFFE"))
        assertEquals(RailCategory.GPU, PowerRails.category("S8S_VDD_G3D"))
    }

    @Test fun unknownRailsFallIntoOther() {
        for (name in listOf("S5M_VDD_INT", "S10M_VDD_TPU", "S7M_VDD_TPU", "L2S_VDD_AOC_RET", "S9S_VDD_AOC", "S6M_LLDO1", "")) {
            assertEquals(name, RailCategory.OTHER, PowerRails.category(name))
        }
    }

    @Test fun caseAndSeparatorsDoNotMatter() {
        assertEquals(RailCategory.GPU, PowerRails.category("gpu"))
        assertEquals(RailCategory.MOBILE_RADIO, PowerRails.category("mobile-radio"))
    }

    @Test fun wordsMustMatchWholeNotInsideAnotherWord() {
        // "CAMPUS" contains CAM, "BTX" contains BT: neither is a camera or Bluetooth.
        assertEquals(RailCategory.OTHER, PowerRails.category("CAMPUS"))
        assertEquals(RailCategory.OTHER, PowerRails.category("BTX"))
    }

    // --- Keys

    @Test fun keysCarryTheType() {
        assertEquals("c:GPU", PowerRails.key(true, "GPU"))
        assertEquals("r:GPU", PowerRails.key(false, "GPU"))
        assertTrue(PowerRails.isConsumer("c:GPU"))
        assertFalse(PowerRails.isConsumer("r:GPU"))
        assertEquals("GPU", PowerRails.nameOf("c:GPU"))
        assertEquals("[Rail2]:CPU", PowerRails.nameOf("r:[Rail2]:CPU"))
    }

    @Test fun repeatedNamesGetOwnKeys() {
        val keys = PowerRails.uniqueKeys(listOf(true to "CPU", true to "CPU", false to "CPU", true to "CPU"))
        assertEquals(listOf("c:CPU", "c:CPU#2", "r:CPU", "c:CPU#3"), keys)
        assertEquals("CPU", PowerRails.nameOf("c:CPU#2"))
    }

    // --- Differences

    private fun r(uws: Long, ms: Long) = RailReading(uws, ms)

    @Test fun differenceOfTwoReadings() {
        val s = PowerRails.step(r(1_000_000, 10_000), r(3_000_000, 14_000), 0)
        assertEquals(Step.Energy(2_000_000, 4_000), s)
        // 2 J in 4 s is 500 mW.
        assertEquals(500.0, PowerRails.averageMw(2_000_000, 4_000)!!, 1e-9)
    }

    @Test fun sameTimestampIsTheCachedValue() {
        assertEquals(Step.Unchanged, PowerRails.step(r(1_000, 5_000), r(1_000, 5_000), COARSE_NOISE_UWS))
        // Even when the noisy value differs: no time has passed.
        assertEquals(Step.Unchanged, PowerRails.step(r(1_000, 5_000), r(900, 5_000), COARSE_NOISE_UWS))
    }

    @Test fun timestampGoingBackwardsIsAReset() {
        assertEquals(Step.Reset, PowerRails.step(r(9_000_000, 90_000), r(1_000, 10_000), 0))
    }

    @Test fun counterGoingBackwardsIsAResetWithExactReadings() {
        assertEquals(Step.Reset, PowerRails.step(r(9_000_000, 10_000), r(8_000_000, 20_000), 0))
    }

    @Test fun smallNegativeDifferenceIsNoiseWithCoarseReadings() {
        val s = PowerRails.step(r(50_000_000, 10_000), r(45_000_000, 310_000), COARSE_NOISE_UWS)
        assertEquals(Step.Energy(-5_000_000, 300_000), s)
    }

    @Test fun bigNegativeDifferenceIsAResetEvenWithCoarseReadings() {
        assertEquals(Step.Reset, PowerRails.step(r(500_000_000, 10_000), r(1_000_000, 310_000), COARSE_NOISE_UWS))
    }

    @Test fun implausibleJumpIsAReset() {
        // 1500 J in 10 s would be 150 W.
        assertEquals(Step.Reset, PowerRails.step(r(0, 0), r(1_500_000_000, 10_000), 0))
        // The same jump over a long time is a busy but possible phone.
        assertTrue(PowerRails.step(r(0, 0), r(1_000_000_000, 3_600_000), 0) is Step.Energy)
    }

    @Test fun veryOldBaselineIsAReset() {
        assertEquals(Step.Reset, PowerRails.step(r(0, 0), r(1_000, PowerRails.MAX_GAP_MS + 1), 0))
    }

    @Test fun averageNeverNegativeAndNeedsTime() {
        assertEquals(0.0, PowerRails.averageMw(-5_000_000, 300_000)!!, 0.0)
        assertNull(PowerRails.averageMw(1_000, 0))
        assertNull(PowerRails.averageMw(1_000, -5))
    }

    @Test fun bootsFarApartAreDifferent() {
        assertTrue(PowerRails.sameBoot(1_000_000, 1_030_000))
        assertFalse(PowerRails.sameBoot(1_000_000, 5_000_000))
    }

    // --- Slots

    @Test fun spreadInsideOneSlot() {
        val end = 10 * SLOT_MS + 600_000
        val s = PowerRails.spread(end, 300_000, 777)
        assertEquals(listOf(EnergySlice(10 * SLOT_MS, 777, 300_000)), s)
    }

    @Test fun spreadAcrossSlotsIsProportionalAndExact() {
        // 20 minutes ending 5 minutes into slot 11: a full slot 10 (15 min) and 5 minutes of slot 11.
        val end = 11 * SLOT_MS + 300_000
        val s = PowerRails.spread(end, 1_200_000, 1_200)
        assertEquals(2, s.size)
        assertEquals(10 * SLOT_MS, s[0].slotStart)
        assertEquals(900_000, s[0].ms)
        assertEquals(300_000, s[1].ms)
        assertEquals(900, s[0].uws)
        assertEquals(300, s[1].uws)
    }

    @Test fun spreadSumsToTheTotalAlsoWhenRounding() {
        val end = 50 * SLOT_MS + 123_457
        val s = PowerRails.spread(end, 7 * SLOT_MS + 99_999, 1_000_003)
        assertEquals(1_000_003L, s.sumOf { it.uws })
        assertEquals(7 * SLOT_MS + 99_999, s.sumOf { it.ms })
        assertTrue(s.zipWithNext().all { (a, b) -> b.slotStart == a.slotStart + SLOT_MS })
    }

    @Test fun spreadEndingExactlyOnABoundaryStaysInThePreviousSlot() {
        val s = PowerRails.spread(5 * SLOT_MS, 60_000, 10)
        assertEquals(listOf(EnergySlice(4 * SLOT_MS, 10, 60_000)), s)
    }

    @Test fun spreadKeepsNegativeNoise() {
        val s = PowerRails.spread(2 * SLOT_MS, SLOT_MS, -300)
        assertEquals(-300L, s.sumOf { it.uws })
    }

    @Test fun spreadOfNothing() {
        assertTrue(PowerRails.spread(1_000_000, 0, 5).isEmpty())
    }

    @Test fun slotOfRoundsDown() {
        assertEquals(3 * SLOT_MS, PowerRails.slotOf(3 * SLOT_MS + 1))
        assertEquals(3 * SLOT_MS, PowerRails.slotOf(3 * SLOT_MS))
    }

    // --- Windows

    @Test fun ratesAreEnergyOverCoveredTime() {
        val rows = listOf(
            SlotRow(0, "c:GPU", 600_000, 600_000),
            SlotRow(SLOT_MS, "c:GPU", 1_800_000, 600_000),
            SlotRow(0, "r:CPU", 3_000_000, 1_200_000),
        )
        val rates = PowerRails.rates(rows)
        assertEquals(2.0, rates.getValue("c:GPU"), 1e-9)
        assertEquals(2.5, rates.getValue("r:CPU"), 1e-9)
    }

    @Test fun noiseThatPushedAWindowBelowZeroShowsAsZero() {
        val rates = PowerRails.rates(listOf(SlotRow(0, "c:GPU", -2_000_000, 600_000)))
        assertEquals(0.0, rates.getValue("c:GPU"), 0.0)
    }

    @Test fun noiseCancelsOverSeveralSlices() {
        // A steady 100 mW is 30 J per 300 s. Noise on the four readings: 2, 6, 1 and 5 J, subtracted from the counter.
        // Each step then carries the noise of both its readings; only the first and last readings count in the sum.
        val rows = listOf(
            SlotRow(0, "c:GPU", 30_000_000 - 6_000_000 + 2_000_000, 300_000),
            SlotRow(SLOT_MS, "c:GPU", 30_000_000 - 1_000_000 + 6_000_000, 300_000),
            SlotRow(2 * SLOT_MS, "c:GPU", 30_000_000 - 5_000_000 + 1_000_000, 300_000),
        )
        // (90 - 5 + 2) J over 900 s, against 100 mW: a 3 % error, where a single step would be off by 17 %.
        assertEquals(87_000_000.0 / 900_000, PowerRails.rates(rows).getValue("c:GPU"), 1e-6)
    }

    @Test fun coveredTimeIsTheLongestMonitor() {
        val rows = listOf(SlotRow(0, "a", 1, 100), SlotRow(SLOT_MS, "a", 1, 200), SlotRow(0, "b", 1, 50))
        assertEquals(300L, PowerRails.coveredMs(rows))
        assertEquals(0L, PowerRails.coveredMs(emptyList()))
    }

    @Test fun coarseWindowsNeedMoreDataThanExactOnes() {
        assertFalse(PowerRails.enoughData(300_000, fine = false))
        assertTrue(PowerRails.enoughData(600_000, fine = false))
        assertFalse(PowerRails.enoughData(30_000, fine = true))
        assertTrue(PowerRails.enoughData(60_000, fine = true))
    }

    // --- Breakdown

    @Test fun consumersWinInTheirCategory() {
        val b = PowerRails.breakdown(
            mapOf("c:GPU" to 300.0, "r:S8S_VDD_G3D" to 280.0, "r:VSYS_PWR_DISPLAY" to 500.0, "r:S4M_VDD_CPUCL0" to 400.0, "r:S3M_VDD_CPUCL1" to 100.0),
        )
        val byCategory = b.categories.associate { it.category to it.mw }
        // GPU: the consumer only. Display and CPU have no consumer, so their rails count (CPU: both clusters).
        assertEquals(300.0, byCategory.getValue(RailCategory.GPU), 1e-9)
        assertEquals(500.0, byCategory.getValue(RailCategory.DISPLAY), 1e-9)
        assertEquals(500.0, byCategory.getValue(RailCategory.CPU), 1e-9)
        assertEquals(1300.0, b.totalMw, 1e-9)
    }

    @Test fun severalConsumersOfOneCategoryAreSummed() {
        val b = PowerRails.breakdown(mapOf("c:CPU_CLUSTER" to 100.0, "c:CPU_CLUSTER#2" to 50.0, "r:[Rail2]:CPU" to 400.0))
        assertEquals(150.0, b.categories.single().mw, 1e-9)
    }

    @Test fun categoriesAreLargestFirst() {
        val b = PowerRails.breakdown(mapOf("c:WIFI" to 10.0, "c:DISPLAY" to 900.0, "c:CPU_CLUSTER" to 300.0))
        assertEquals(listOf(RailCategory.DISPLAY, RailCategory.CPU, RailCategory.WIFI_BT), b.categories.map { it.category })
    }

    @Test fun unknownNamesStayVisibleInTheDetails() {
        val b = PowerRails.breakdown(mapOf("r:S9S_VDD_AOC" to 40.0, "c:GPU" to 100.0, "r:S8S_VDD_G3D" to 90.0))
        assertEquals(40.0, b.categories.first { it.category == RailCategory.OTHER }.mw, 1e-9)
        val aoc = b.details.first { it.name == "S9S_VDD_AOC" }
        assertEquals(RailCategory.OTHER, aoc.category)
        assertTrue(aoc.counted)
        assertFalse(b.details.first { it.name == "S8S_VDD_G3D" }.counted)
        // Counted rows come first.
        assertEquals(listOf(true, true, false), b.details.map { it.counted })
    }

    @Test fun emptyBreakdown() {
        val b = PowerRails.breakdown(emptyMap())
        assertTrue(b.categories.isEmpty())
        assertEquals(0.0, b.totalMw, 0.0)
    }

    // --- Comparison with the battery

    private fun draw(mw: Double, n: Int, charging: Boolean = false): List<Pair<Double?, Boolean>> = List(n) { mw to charging }

    @Test fun batteryDrawIsTheAverageOutflow() {
        assertEquals(1000.0, PowerRails.batteryDrawMw(draw(-800.0, 10) + draw(-1200.0, 10))!!, 1e-9)
    }

    @Test fun batteryDrawNeedsEnoughSamples() {
        assertNull(PowerRails.batteryDrawMw(draw(-800.0, 9)))
        assertNull(PowerRails.batteryDrawMw(emptyList()))
    }

    @Test fun batteryDrawIgnoresWindowsWithCharging() {
        assertNull(PowerRails.batteryDrawMw(draw(-800.0, 80) + draw(3000.0, 20, charging = true)))
        // A single minute of charging in 100 is tolerated.
        assertEquals(800.0, PowerRails.batteryDrawMw(draw(-800.0, 99) + draw(3000.0, 1, charging = true))!!, 1e-9)
    }

    @Test fun batteryDrawIgnoresUnknownPower() {
        assertEquals(500.0, PowerRails.batteryDrawMw(draw(-500.0, 10) + List(50) { null to false })!!, 1e-9)
    }

    @Test fun plugAtFullIsNotBatteryDraw() {
        // Plugged in at 100 %: not "charging", but power is positive or zero.
        assertNull(PowerRails.batteryDrawMw(draw(50.0, 20)))
    }

    @Test fun restIsDrawMinusMeasured() {
        assertEquals(400.0, PowerRails.restMw(600.0, 1000.0)!!, 1e-9)
    }

    @Test fun noRestWhenMeasuredExceedsTheBattery() {
        assertNull(PowerRails.restMw(1200.0, 1000.0))
        assertNull(PowerRails.restMw(1000.0, 1000.0))
    }

    @Test fun noRestWhenItIsTinyOrNearlyEverything() {
        assertNull(PowerRails.restMw(980.0, 1000.0))
        // 40 mW is below the floor even for a draw of 100 mW.
        assertNull(PowerRails.restMw(60.0, 100.0))
        // The monitors explain 5 % of the draw: they cover too little for the rest to mean anything.
        assertNull(PowerRails.restMw(50.0, 1000.0))
    }

    @Test fun noRestWithoutBatteryDraw() {
        assertNull(PowerRails.restMw(500.0, null))
        assertNull(PowerRails.restMw(500.0, 0.0))
        assertNull(PowerRails.restMw(500.0, -100.0))
    }

    // --- Live rates

    private fun at(ms: Long, uws: Long) = mapOf("c:GPU" to r(uws, ms))

    @Test fun liveRateNeedsTwoReadings() {
        val live = PowerRails.Live()
        live.add(at(0, 0))
        assertTrue(live.rates().isEmpty())
        live.add(at(2_000, 1_000_000))
        assertEquals(500.0, live.rates().getValue("c:GPU"), 1e-9)
        assertEquals(2_000L, live.spanMs())
    }

    @Test fun liveRateAveragesOverTheSpan() {
        val live = PowerRails.Live(spanMs = 6_000)
        // 100 mW for 10 s, then 700 mW.
        for (t in 0..5) live.add(at(t * 2_000L, 200_000L * t))
        for (t in 6..10) live.add(at(t * 2_000L, 1_000_000 + 1_400_000L * (t - 5)))
        // Window is the last 6 s or a little more: only 700 mW is left in it.
        assertEquals(700.0, live.rates().getValue("c:GPU"), 1e-9)
        assertTrue(live.spanMs() in 6_000..8_000)
    }

    @Test fun liveIgnoresRepeatedReadings() {
        val live = PowerRails.Live()
        live.add(at(0, 0))
        live.add(at(2_000, 1_000_000))
        live.add(at(2_000, 1_000_000))
        assertEquals(500.0, live.rates().getValue("c:GPU"), 1e-9)
    }

    @Test fun liveStartsOverAfterAReset() {
        val live = PowerRails.Live()
        live.add(at(0, 5_000_000))
        live.add(at(2_000, 6_000_000))
        live.add(at(4_000, 100))
        assertTrue(live.rates().isEmpty())
        live.add(at(6_000, 2_000_100))
        assertEquals(1000.0, live.rates().getValue("c:GPU"), 1e-9)
    }

    @Test fun liveForgetsReadingsAfterALongPause() {
        val live = PowerRails.Live(spanMs = 10_000)
        live.add(at(0, 0))
        live.add(at(2_000, 200_000))
        // Back after ten minutes: the old readings must not make a ten-minute average of "now".
        live.add(at(602_000, 60_200_000))
        assertTrue(live.rates().isEmpty())
        live.add(at(604_000, 60_200_000 + 2_000_000))
        assertEquals(1000.0, live.rates().getValue("c:GPU"), 1e-9)
    }

    @Test fun liveTracksMonitorsSeparately() {
        val live = PowerRails.Live()
        live.add(mapOf("c:GPU" to r(0, 0), "r:CPU" to r(0, 0)))
        live.add(mapOf("c:GPU" to r(2_000_000, 2_000), "r:CPU" to r(4_000_000, 2_000)))
        assertEquals(1000.0, live.rates().getValue("c:GPU"), 1e-9)
        assertEquals(2000.0, live.rates().getValue("r:CPU"), 1e-9)
        live.clear()
        assertTrue(live.rates().isEmpty())
        assertEquals(0L, live.spanMs())
    }
}
