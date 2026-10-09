package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeAlarmLogicTest {
    private val charging = ChargeStatus.CHARGING
    private val min = 60_000L

    private fun ChargeAlarmLogic.at(
        minute: Int, percent: Int, plugged: Boolean = true, status: ChargeStatus = charging, temp: Double = 30.0,
        limit: Int = 80, full: Boolean = true, warm: Boolean = true,
    ) = onSample(minute * min, plugged, status, percent / 100.0, temp, limit, full, warm)

    @Test fun limitFiresOnceWhenTheLevelRisesThroughIt() {
        val l = ChargeAlarmLogic()
        assertTrue(l.at(0, 70).isEmpty())
        assertTrue(l.at(1, 79).isEmpty())
        assertEquals(listOf(ChargeAlarmKind.LIMIT), l.at(2, 80))
        assertTrue(l.at(3, 81).isEmpty())
        // Dips under the limit under load and climbs back: still the same plug-in.
        assertTrue(l.at(4, 79).isEmpty())
        assertTrue(l.at(5, 80).isEmpty())
    }

    @Test fun limitFiresAgainAfterAnotherPlugIn() {
        val l = ChargeAlarmLogic()
        l.at(0, 70); l.at(1, 80)
        l.at(2, 78, plugged = false)
        l.at(3, 75)
        assertEquals(listOf(ChargeAlarmKind.LIMIT), l.at(4, 82))
    }

    @Test fun pluggingInAboveTheLimitIsSilent() {
        val l = ChargeAlarmLogic()
        assertTrue(l.at(0, 85).isEmpty())
        assertTrue(l.at(1, 86).isEmpty())
    }

    @Test fun limitOffNeverFires() {
        val l = ChargeAlarmLogic()
        l.at(0, 70, limit = 0)
        assertTrue(l.at(1, 90, limit = 0).isEmpty())
    }

    @Test fun limitCanJumpPastInOneStep() {
        val l = ChargeAlarmLogic()
        l.at(0, 78)
        assertEquals(listOf(ChargeAlarmKind.LIMIT), l.at(1, 83))
    }

    @Test fun fullFiresOncePerPlugIn() {
        val l = ChargeAlarmLogic()
        l.at(0, 95, limit = 0)
        assertEquals(listOf(ChargeAlarmKind.FULL), l.at(1, 100, status = ChargeStatus.FULL, limit = 0))
        // Wireless chargers flip between charging and full at the top.
        assertTrue(l.at(2, 99, limit = 0).isEmpty())
        assertTrue(l.at(3, 100, status = ChargeStatus.FULL, limit = 0).isEmpty())
    }

    @Test fun fullIsSilentWhenSwitchedOffOrWhenNothingWasCharged() {
        val off = ChargeAlarmLogic()
        off.at(0, 95, limit = 0)
        assertTrue(off.at(1, 100, status = ChargeStatus.FULL, limit = 0, full = false).isEmpty())
        // Switching it on afterwards does not report the old event.
        assertTrue(off.at(2, 100, status = ChargeStatus.FULL, limit = 0, full = true).isEmpty())

        val topped = ChargeAlarmLogic()
        assertTrue(topped.at(0, 100, status = ChargeStatus.FULL, limit = 0).isEmpty())
    }

    @Test fun warmNeedsTwoMinutes() {
        val l = ChargeAlarmLogic()
        assertTrue(l.at(0, 50, temp = 41.0).isEmpty())
        assertTrue(l.at(1, 50, temp = 41.0).isEmpty())
        assertEquals(listOf(ChargeAlarmKind.WARM), l.at(2, 50, temp = 41.0))
        assertTrue(l.at(3, 50, temp = 42.0).isEmpty())
    }

    @Test fun coolingDownBreaksTheStreak() {
        val l = ChargeAlarmLogic()
        l.at(0, 50, temp = 41.0)
        l.at(1, 50, temp = 39.0)
        assertTrue(l.at(2, 50, temp = 41.0).isEmpty())
        assertTrue(l.at(3, 50, temp = 41.0).isEmpty())
        assertEquals(listOf(ChargeAlarmKind.WARM), l.at(4, 50, temp = 41.0))
    }

    @Test fun warmAgainOnlyAfterItCooledProperly() {
        val l = ChargeAlarmLogic()
        l.at(0, 50, temp = 41.0); l.at(2, 50, temp = 41.0)
        // 39 is not cool enough to count as a new episode.
        l.at(3, 50, temp = 39.0)
        l.at(4, 50, temp = 41.0)
        assertTrue(l.at(6, 50, temp = 41.0).isEmpty())
        l.at(7, 50, temp = 35.0)
        l.at(8, 50, temp = 41.0)
        assertEquals(listOf(ChargeAlarmKind.WARM), l.at(10, 50, temp = 41.0))
    }

    @Test fun warmNeedsThePlugAndIgnoresSleepGaps() {
        val l = ChargeAlarmLogic()
        assertTrue(l.at(0, 50, plugged = false, temp = 45.0).isEmpty())
        assertTrue(l.at(5, 50, plugged = false, temp = 45.0).isEmpty())
        // The phone slept for twenty minutes between two warm readings.
        val s = ChargeAlarmLogic()
        s.at(0, 50, temp = 41.0)
        assertTrue(s.at(20, 50, temp = 41.0).isEmpty())
        assertEquals(listOf(ChargeAlarmKind.WARM), s.at(22, 50, temp = 41.0))
    }

    @Test fun warmOffIsSilent() {
        val l = ChargeAlarmLogic()
        l.at(0, 50, temp = 41.0, warm = false)
        assertTrue(l.at(3, 50, temp = 41.0, warm = false).isEmpty())
    }
}
