package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {
    @Test fun chargingSessionIntegratesPowerAndEndsOnUnplug() {
        val t = SessionTracker()
        assertNull(t.onSample(0, plugged = true, level = 0.20, powerMw = 18_000.0, screenOn = false, tempC = 30.0))
        for (i in 1..60) t.onSample(i * 60_000L, true, 0.20 + i * 0.01, 18_000.0, false, 30.0 + i * 0.1)
        val done = t.onSample(3_660_000, plugged = false, level = 0.80, powerMw = -1_000.0, screenOn = true, tempC = 36.0)
        assertNotNull(done)
        done!!
        assertTrue(done.charging)
        assertEquals(18_000.0, done.energyMwh, 1.0) // 18 W for one hour
        assertEquals(18_000.0, done.peakMw, 1e-9)
        assertEquals(0.60, done.levelChange, 1e-9)
        assertEquals(60.0, done.percentPerHour!!, 1e-6)
        assertEquals(36.0, done.maxTempC, 1e-9)
        assertTrue(t.current!!.charging.not())
    }

    @Test fun dischargeSplitsScreenOnAndOff() {
        val t = SessionTracker(maxTickSeconds = 120.0)
        t.onSample(0, false, 0.9, -2_000.0, true, 30.0)
        t.onSample(60_000, false, 0.89, -2_000.0, true, 30.0)
        // The phone slept for an hour: no energy, but screen-off time.
        t.onSample(3_660_000, false, 0.86, -200.0, false, 28.0)
        val s = t.current!!
        assertEquals(60.0, s.screenOnSeconds, 1e-9)
        assertEquals(3600.0, s.screenOffSeconds, 1e-9)
        assertEquals(2_000.0 * 60 / 3600 + 200.0 * 120 / 3600, s.energyMwh, 1e-6)
    }

    @Test fun firstSampleStartsWithoutFinishing() {
        val t = SessionTracker()
        assertNull(t.onSample(0, false, 0.5, null, true, 25.0))
        assertNull(t.onSample(10_000, false, 0.5, null, true, 25.0))
        assertEquals(0.0, t.current!!.energyMwh, 1e-9)
    }

    @Test fun ratesFromCurrent() {
        assertEquals(25.0, BatteryMath.percentPerHour(1000.0, 4000.0)!!, 1e-9)
        assertEquals(3600L * 2, BatteryMath.secondsToFull(0.5, 25.0))
        assertEquals(3600L * 4, BatteryMath.secondsToEmpty(0.5, -12.5))
        assertNull(BatteryMath.secondsToEmpty(0.5, 3.0))
        assertEquals(BatteryMath.ChargeSpeed.FAST, BatteryMath.chargeSpeed(18.0))
        assertEquals(BatteryMath.ChargeSpeed.SLOW, BatteryMath.chargeSpeed(4.5))
    }
}
