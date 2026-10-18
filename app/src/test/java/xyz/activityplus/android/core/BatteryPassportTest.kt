package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BatteryPassportTest {
    private val now = day(2026, 10, 18) * 1000

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    private fun raw(
        made: Long? = null, first: Long? = null, policy: Long? = null, soh: Long? = null, part: Long? = null,
        level: CapacityLevel? = null, cycles: Int? = null, granted: Boolean = true,
    ) = PassportRaw(made, first, policy, soh, part, level, cycles, granted)

    private fun est(mah: Double, t: Long = 1_000L) = CapacityEstimate(t, mah, 0.2, 0.8)

    private fun passport(
        r: PassportRaw, design: Double? = 5500.0, estimates: List<CapacityEstimate> = emptyList(),
    ) = BatteryPassport.from(r, design, estimates, now, ZoneOffset.UTC)

    @Test fun epochSecondsAndMillisecondsGiveTheSameDate() {
        val seconds = day(2025, 3, 1)
        assertEquals(seconds * 1000, passport(raw(made = seconds)).madeMillis)
        assertEquals(seconds * 1000, passport(raw(made = seconds * 1000)).madeMillis)
    }

    @Test fun zeroNegativeAndMinValueSentinelsAreUnknown() {
        val p = passport(raw(made = 0, first = -1, soh = Long.MIN_VALUE, cycles = -1))
        assertNull(p.madeMillis)
        assertNull(p.firstUseMillis)
        assertNull(p.sohPercent)
        assertNull(p.cyclesPerMonth)
        assertNull(passport(raw(soh = Int.MIN_VALUE.toLong())).sohPercent)
    }

    @Test fun firstUseBeforeManufactureIsIgnored() {
        val p = passport(raw(made = day(2025, 3, 1), first = day(2025, 1, 1)))
        assertEquals(day(2025, 3, 1) * 1000, p.madeMillis)
        assertNull(p.firstUseMillis)
        assertNull(p.monthsInUse)
    }

    @Test fun datesInTheFutureAreIgnored() {
        val p = passport(raw(made = day(2027, 1, 1), first = day(2027, 2, 1)))
        assertNull(p.madeMillis)
        assertNull(p.firstUseMillis)
    }

    @Test fun monthsAreCountedAsCalendarMonths() {
        // 31 March to 18 October is seven calendar months, not 6 full months and a week.
        assertEquals(7, passport(raw(first = day(2026, 3, 31))).monthsInUse)
        assertEquals(0, passport(raw(first = day(2026, 10, 1))).monthsInUse)
    }

    @Test fun cyclesPerMonthNeedAtLeastOneMonth() {
        assertEquals(12.0, passport(raw(first = day(2026, 3, 1), cycles = 84)).cyclesPerMonth!!, 1e-9)
        assertNull(passport(raw(first = day(2026, 10, 1), cycles = 3)).cyclesPerMonth)
        assertNull(passport(raw(cycles = 84)).cyclesPerMonth)
    }

    @Test fun zeroCyclesIsAKnownValue() {
        assertEquals(0.0, passport(raw(first = day(2026, 3, 1), cycles = 0)).cyclesPerMonth!!, 1e-9)
    }

    @Test fun chargingPolicyNumbersMapToPolicies() {
        assertEquals(ChargingPolicy.STANDARD, passport(raw(policy = 1)).policy)
        assertEquals(ChargingPolicy.LIMITED, passport(raw(policy = 2)).policy)
        assertEquals(ChargingPolicy.ADAPTIVE, passport(raw(policy = 3)).policy)
        assertEquals(ChargingPolicy.LONG_LIFE, passport(raw(policy = 4)).policy)
        assertNull(passport(raw(policy = 0)).policy)
        assertNull(passport(raw(policy = 5)).policy)
    }

    @Test fun partStatusKnowsOnlyOriginalAndReplaced() {
        assertEquals(PartStatus.ORIGINAL, passport(raw(part = 1)).part)
        assertEquals(PartStatus.REPLACED, passport(raw(part = 2)).part)
        // 0 means the phone does not report it, so there is nothing to show.
        assertNull(passport(raw(part = 0)).part)
        assertNull(passport(raw(part = 3)).part)
    }

    @Test fun stateOfHealthIsPlausibleBetweenOneAndOneHundredTen() {
        assertEquals(1, passport(raw(soh = 1)).sohPercent)
        assertEquals(98, passport(raw(soh = 98)).sohPercent)
        assertEquals(110, passport(raw(soh = 110)).sohPercent)
        assertNull(passport(raw(soh = 111)).sohPercent)
        assertNull(passport(raw(soh = 0)).sohPercent)
    }

    @Test fun capacityLevelAndGrantPassThrough() {
        val p = passport(raw(level = CapacityLevel.HIGH, granted = false))
        assertEquals(CapacityLevel.HIGH, p.capacityLevel)
        assertEquals(false, p.statsGranted)
    }

    @Test fun verdictAgreesWithinThreePoints() {
        // 5170 mAh of 5500 is 94 %, the same as Android's 94.
        val estimates = List(3) { est(5170.0, it.toLong()) }
        assertEquals(BatteryPassport.Verdict.Agree(94), passport(raw(soh = 94), estimates = estimates).verdict)
        // 96 against 94 is still "about the same".
        assertEquals(BatteryPassport.Verdict.Agree(95), passport(raw(soh = 96), estimates = estimates).verdict)
    }

    @Test fun verdictDiffersWhenTheMeasurementSaysSomethingElse() {
        // Android 98 %, measured 5005 of 5500 is 91 %; three measurements are enough to say it.
        val estimates = List(3) { est(5005.0, it.toLong()) }
        assertEquals(
            BatteryPassport.Verdict.Differ(98, 91, settling = false),
            passport(raw(soh = 98), estimates = estimates).verdict,
        )
    }

    @Test fun fewMeasurementsMakeTheDifferenceSettling() {
        val estimates = listOf(est(5005.0, 1), est(5005.0, 2))
        assertEquals(
            BatteryPassport.Verdict.Differ(98, 91, settling = true),
            passport(raw(soh = 98), estimates = estimates).verdict,
        )
    }

    @Test fun oneMeasurementIsEnoughForAVerdict() {
        assertEquals(
            BatteryPassport.Verdict.Agree(94),
            passport(raw(soh = 94), estimates = listOf(est(5170.0))).verdict,
        )
    }

    @Test fun noVerdictWithoutBothSides() {
        val measured = List(3) { est(5170.0, it.toLong()) }
        assertNull(passport(raw(soh = 94), design = null, estimates = measured).verdict)
        assertNull(passport(raw(soh = 94), design = 0.0, estimates = measured).verdict)
        assertNull(passport(raw(soh = 94), estimates = emptyList()).verdict)
        assertNull(passport(raw(soh = null), estimates = measured).verdict)
        assertNull(passport(raw(soh = 111), estimates = measured).verdict)
    }
}
