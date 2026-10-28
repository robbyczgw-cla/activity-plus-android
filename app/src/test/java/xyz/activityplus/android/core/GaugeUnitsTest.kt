package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeUnitsTest {
    @Test fun oneBigCurrentProvesMicroamps() {
        val u = GaugeUnits()
        assertTrue(u.observe(-450_000, null, screenOn = true, levelFraction = 0.6, designMah = 5000.0))
        assertEquals(true, u.currentMicro)
        // From then on a small reading is a small current: 4,000 µA is 4 mA, not 4 A.
        assertEquals(-4.0, BatteryMath.currentMa(-4_000, ChargeStatus.DISCHARGING, false, u.currentMicro)!!, 1e-9)
    }

    @Test fun milliampsNeedManyScreenOnReadingsThatOnlyMilliampsExplain() {
        val u = GaugeUnits()
        repeat(GaugeUnits.MILLI_EVIDENCE - 1) { assertFalse(u.observe(-420, null, screenOn = true, levelFraction = 0.6, designMah = null)) }
        assertNull(u.currentMicro)
        assertTrue(u.observe(-420, null, screenOn = true, levelFraction = 0.6, designMah = null))
        assertEquals(false, u.currentMicro)
    }

    @Test fun smallReadingsWithTheScreenOffProveNothing() {
        val u = GaugeUnits()
        repeat(100) { u.observe(-4_000, null, screenOn = false, levelFraction = 0.6, designMah = null) }
        assertNull(u.currentMicro)
    }

    @Test fun theChargeCounterUnitIsLearnedToo() {
        val u = GaugeUnits()
        assertTrue(u.observe(null, 3_100_000, screenOn = false, levelFraction = 0.6, designMah = 5000.0))
        assertEquals(true, u.counterMicro)
        // Near empty a µAh counter is small, but it stays µAh.
        assertEquals(3.0, BatteryMath.chargeMah(3_000, 5000.0, u.counterMicro)!!, 1e-9)

        val m = GaugeUnits()
        repeat(GaugeUnits.MILLI_EVIDENCE) { m.observe(null, 3_100, screenOn = false, levelFraction = 0.6, designMah = 5000.0) }
        assertEquals(false, m.counterMicro)
    }

    @Test fun aDecidedUnitNeverChanges() {
        val u = GaugeUnits(currentMicro = true, counterMicro = true)
        repeat(100) { assertFalse(u.observe(-420, 3_100, screenOn = true, levelFraction = 0.6, designMah = 5000.0)) }
        assertEquals(true, u.currentMicro)
        assertEquals(true, u.counterMicro)
    }
}
