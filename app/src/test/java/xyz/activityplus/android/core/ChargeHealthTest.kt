package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeHealthTest {
    private fun session(
        from: Double = 0.2, to: Double = 0.8, mwh: Double = 13_000.0, charging: Boolean = true, end: Long = 1_000L,
    ) = BatterySession(charging, 0, end, from, to, mwh, 18_000.0, 0.0, 0.0, 33.0)

    @Test fun estimatesCapacityFromEnergyVoltageAndLevelChange() {
        // 13 Wh at 3.9 V is 3333 mAh for 60 % of the battery: 5556 mAh in full.
        val e = ChargeHealth.estimate(session(), 3.9, 5500.0)!!
        assertEquals(5555.6, e.mah, 0.1)
        assertEquals(0.2, e.levelFrom, 1e-9)
        assertEquals(0.8, e.levelTo, 1e-9)
        assertEquals(1_000L, e.timeMillis)
    }

    @Test fun worksWithoutKnownDesignCapacity() {
        assertNotNull(ChargeHealth.estimate(session(), 3.9, null))
    }

    @Test fun rejectsShortChargesAndMissingReadings() {
        assertNull(ChargeHealth.estimate(session(from = 0.5, to = 0.75), 3.9, 5500.0))
        assertNull(ChargeHealth.estimate(session(mwh = 0.0), 3.9, 5500.0))
        assertNull(ChargeHealth.estimate(session(), null, 5500.0))
        assertNull(ChargeHealth.estimate(session(), 0.0, 5500.0))
        assertNull(ChargeHealth.estimate(session(charging = false), 3.9, 5500.0))
    }

    @Test fun acceptsExactlyThirtyPercent() {
        assertNotNull(ChargeHealth.estimate(session(from = 0.5, to = 0.8, mwh = 6_500.0), 3.9, 5500.0))
    }

    @Test fun rejectsNonsenseAgainstTheRatedCapacity() {
        // 2000 mWh over 60 %: 855 mAh, under 40 % of 5000.
        assertNull(ChargeHealth.estimate(session(mwh = 2_000.0), 3.9, 5000.0))
        // 26 Wh over 60 %: 11 Ah, over 130 %.
        assertNull(ChargeHealth.estimate(session(mwh = 26_000.0), 3.9, 5000.0))
        // The same figures are fine when nothing is known to compare with.
        assertNotNull(ChargeHealth.estimate(session(mwh = 2_000.0), 3.9, null))
    }

    @Test fun boundsAreFortyAndOneThirtyPercent() {
        val mah = 13_000.0 / 3.9 / 0.6
        assertNotNull(ChargeHealth.estimate(session(), 3.9, mah / 0.41))
        assertNotNull(ChargeHealth.estimate(session(), 3.9, mah / 1.29))
        assertNull(ChargeHealth.estimate(session(), 3.9, mah / 0.39))
        assertNull(ChargeHealth.estimate(session(), 3.9, mah / 1.31))
    }

    private fun est(t: Long, mah: Double) = CapacityEstimate(t, mah, 0.2, 0.8)

    @Test fun trendIsHiddenBelowTwoMeasurements() {
        assertNull(ChargeHealth.trend(emptyList()))
        assertNull(ChargeHealth.trend(listOf(est(1, 5000.0))))
    }

    @Test fun trendWithTwoComparesThemDirectly() {
        val t = ChargeHealth.trend(listOf(est(2, 4850.0), est(1, 5000.0)))!!
        assertEquals(1L, t.firstMillis)
        assertEquals(-0.03, t.change, 1e-9)
        assertEquals(2, t.count)
    }

    @Test fun trendUsesMediansOfFirstAndLatestThree() {
        // First three: 5000, 5400 (odd), 5020 -> 5020. Latest three: 4800, 4300 (odd), 4780 -> 4780.
        val list = listOf(
            est(1, 5000.0), est(2, 5400.0), est(3, 5020.0), est(4, 4900.0), est(5, 4800.0), est(6, 4300.0), est(7, 4780.0),
        )
        val t = ChargeHealth.trend(list)!!
        assertEquals(5020.0, t.firstMah, 1e-9)
        assertEquals(4780.0, t.latestMah, 1e-9)
        assertEquals(-0.0478, t.change, 1e-4)
        assertEquals(7, t.count)
    }

    @Test fun fewMeasurementsNeverShareAGroup() {
        // Three: first against last. Four: mean of the first two against mean of the last two.
        assertEquals(-0.1, ChargeHealth.trend(listOf(est(1, 5000.0), est(2, 9999.0), est(3, 4500.0)))!!.change, 1e-9)
        val four = ChargeHealth.trend(listOf(est(1, 5000.0), est(2, 5200.0), est(3, 4600.0), est(4, 4800.0)))!!
        assertEquals(5100.0, four.firstMah, 1e-9)
        assertEquals(4700.0, four.latestMah, 1e-9)
    }

    @Test fun signedPercentUsesARealMinus() {
        assertEquals("−3 %", ChargeHealth.signedPercent(-0.03))
        assertEquals("+2 %", ChargeHealth.signedPercent(0.02))
        assertEquals("0 %", ChargeHealth.signedPercent(0.004))
        assertEquals("0 %", ChargeHealth.signedPercent(-0.004))
    }

    @Test fun voltageAverage() {
        val v = VoltageAverage()
        assertNull(v.mean())
        v.add(3.8); v.add(4.0); v.add(0.0)
        assertEquals(3.9, v.mean()!!, 1e-9)
        v.reset()
        assertNull(v.mean())
    }
}
