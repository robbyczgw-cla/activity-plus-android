package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainTrackerTest {
    @Test fun screenOnPowerGoesToTheAppOnScreen() {
        val t = DrainTracker()
        t.onTick(0, screenOn = true, discharging = true, powerMw = -3600.0, foreground = "com.chrome")
        t.onTick(10_000, screenOn = true, discharging = true, powerMw = -3600.0, foreground = "com.chrome")
        t.onTick(20_000, screenOn = true, discharging = true, powerMw = -1800.0, foreground = "com.maps")
        val d = t.drain()
        assertEquals(10.0, d["com.chrome"]!!.first, 1e-9) // 3.6 W for 10 s = 10 mWh
        assertEquals(5.0, d["com.maps"]!!.first, 1e-9)
        assertEquals(10.0, d["com.maps"]!!.second, 1e-9)
        assertTrue(t.drain().isEmpty())
    }

    @Test fun nothingIsCountedWhileCharging() {
        val t = DrainTracker()
        t.onTick(0, true, false, 2000.0, "a")
        t.onTick(5_000, true, false, 2000.0, "a")
        assertTrue(t.drain().isEmpty())
    }

    @Test fun sleepGapsAreCapped() {
        val t = DrainTracker(maxTickSeconds = 30.0)
        t.onTick(0, true, true, -3600.0, "a")
        t.onTick(600_000, true, true, -3600.0, "a")
        assertEquals(30.0, t.drain()["a"]!!.second, 1e-9)
    }

    @Test fun unknownForegroundIsKeptSeparately() {
        val t = DrainTracker()
        t.onTick(0, true, true, -1000.0, null)
        t.onTick(3_600_000, true, true, -1000.0, null)
        assertEquals(1000.0 * 30 / 3600, t.drain()[DrainTracker.SCREEN_ON_OTHER]!!.first, 1e-9)
    }

    @Test fun screenOffUsesTheFuelGauge() {
        val t = DrainTracker()
        t.onScreenOff(0, chargeMah = 3000.0, level = 0.75, voltage = 3.9, discharging = true)
        t.onScreenOn(3_600_000, chargeMah = 2900.0, level = 0.72, voltage = 3.86, discharging = true, capacityMah = 4000.0)
        val off = t.drain()[DrainTracker.SCREEN_OFF]!!
        assertEquals(100 * 3.88, off.first, 1e-9)
        assertEquals(3600.0, off.second, 1e-9)
    }

    @Test fun screenOffFallsBackToLevelTimesCapacity() {
        val t = DrainTracker()
        t.onScreenOff(0, null, 0.80, 4.0, true)
        t.onScreenOn(7_200_000, null, 0.78, 4.0, true, capacityMah = 4000.0)
        assertEquals(0.02 * 4000 * 4.0, t.drain()[DrainTracker.SCREEN_OFF]!!.first, 1e-6)
    }

    @Test fun pluggingInDuringScreenOffDropsTheMeasurement() {
        val t = DrainTracker()
        t.onScreenOff(0, 3000.0, 0.75, 3.9, true)
        t.onPowerChanged()
        t.onScreenOn(3_600_000, 2900.0, 0.72, 3.9, true, 4000.0)
        assertNull(t.drain()[DrainTracker.SCREEN_OFF])
    }
}
