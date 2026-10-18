package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostTest {
    /** A 5000 mAh battery at 3.85 V. */
    private val battery = 5000.0 * 3.85

    @Test fun ranksTheMostExpensivePerHourFirst() {
        val apps = listOf(
            MeasuredApp("com.kindle", 577.5, 3600.0),
            MeasuredApp("com.zhiliao.tiktok", 2695.0, 3600.0),
        )
        val rows = UsageCost.rank(apps, battery)
        assertEquals(listOf("com.zhiliao.tiktok", "com.kindle"), rows.map { it.pkg })
        assertEquals(14.0, rows[0].percentPerHour!!, 0.01)
        assertEquals(3.0, rows[1].percentPerHour!!, 0.01)
    }

    @Test fun averagePowerIsEnergyOverTheMeasuredHours() {
        // 900 mWh over 30 minutes is 1.8 W.
        val row = UsageCost.rank(listOf(MeasuredApp("a.b", 900.0, 1800.0)), battery).single()
        assertEquals(1800.0, row.averageMw, 1e-9)
    }

    @Test fun appsWithFewMinutesMeasuredAreLeftOut() {
        val apps = listOf(
            MeasuredApp("short", 500.0, UsageCost.MIN_SECONDS - 1),
            MeasuredApp("enough", 500.0, UsageCost.MIN_SECONDS),
        )
        assertEquals(listOf("enough"), UsageCost.rank(apps, battery).map { it.pkg })
    }

    @Test fun systemRowsAndAppsWithoutEnergyAreLeftOut() {
        val apps = listOf(
            MeasuredApp("#screen_off", 900.0, 3600.0),
            MeasuredApp("#system", 900.0, 3600.0),
            MeasuredApp("idle.app", 0.0, 3600.0),
            MeasuredApp("real.app", 900.0, 3600.0),
        )
        assertEquals(listOf("real.app"), UsageCost.rank(apps, battery).map { it.pkg })
    }

    @Test fun withoutCapacityThereIsNoPercentAndTheRankingIsByPower() {
        val apps = listOf(
            MeasuredApp("low", 900.0, 3600.0),
            MeasuredApp("high", 2000.0, 3600.0),
        )
        val rows = UsageCost.rank(apps, batteryMwh = null)
        assertEquals(listOf("high", "low"), rows.map { it.pkg })
        assertTrue(rows.all { it.percentPerHour == null })
        assertNull(UsageCost.rank(apps, batteryMwh = 0.0)[0].percentPerHour)
    }

    @Test fun limitKeepsTheTopRows() {
        val apps = List(8) { MeasuredApp("app$it", 100.0 * (it + 1), 3600.0) }
        val rows = UsageCost.rank(apps, battery)
        assertEquals(5, rows.size)
        assertEquals("app7", rows.first().pkg)
        assertEquals(1, UsageCost.rank(apps, battery, limit = 1).size)
    }
}
