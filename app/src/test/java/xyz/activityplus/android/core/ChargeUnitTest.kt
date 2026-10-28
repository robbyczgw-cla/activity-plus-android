package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.pro.ProParser

class ChargeUnitTest {
    @Test fun aNormalReportIsMah() {
        // Computed 640 mAh for a battery that lost 600 to 700: the usual, slightly off estimate.
        assertEquals(1.0, ChargeUnit.detect(640.0, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
    }

    @Test fun microcoulombsAreRecognisedFromTheDrainedCharge() {
        assertEquals(3_600_000.0, ChargeUnit.detect(650.0 * 3_600_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
        // Apps covering only half of the drain still point clearly at µC: the next unit is 1000× away.
        assertEquals(3_600_000.0, ChargeUnit.detect(330.0 * 3_600_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
    }

    @Test fun betweenMicroAmpHoursAndMillicoulombsItOnlyAnswersWhenClear() {
        assertEquals(1_000.0, ChargeUnit.detect(650.0 * 1_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
        assertEquals(3_600.0, ChargeUnit.detect(650.0 * 3_600, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
        // A ratio of ~1900 sits between the two: no answer rather than a coin toss.
        assertNull(ChargeUnit.detect(650.0 * 1_900, 600.0, 700.0, 6040.0, 6040.0))
    }

    @Test fun tooLittleDrainedOrTooCoarseSaysNothing() {
        // Just unplugged: 0..2 mAh drained, any ratio is noise.
        assertNull(ChargeUnit.detect(5.0 * 3_600_000, 0.0, 2.0, 6040.0, 6040.0))
        // A 1 % step on a big battery: 0..60 mAh, wider than the middle itself.
        assertNull(ChargeUnit.detect(30.0 * 3_600_000, 0.0, 60.0, 6040.0, 6040.0))
    }

    @Test fun withoutDrainTheCapacityTellsTheUnit() {
        assertEquals(3_600_000.0, ChargeUnit.detect(1e9, 0.0, 0.0, 6040.0 * 3_600_000, 6040.0)!!, 0.0)
        assertNull(ChargeUnit.detect(1e9, 0.0, 0.0, 6040.0, 6040.0))
    }

    @Test fun implausibleMeansMoreThanTenBatteries() {
        assertFalse(ChargeUnit.implausible(5_000.0, 6040.0))
        assertTrue(ChargeUnit.implausible(285_078_419.0, 6040.0))
        assertFalse(ChargeUnit.implausible(90_000.0, null))
    }

    @Test fun theParserKeepsTheNumbersAndSaysWhatTheyAreIn() {
        val s = 3_600_000.0
        val raw = ProParser.batteryCheckin(
            """
            9,0,l,bt,0,72000000,72000000,72000000,72000000,1792000000000,0,0,6040,6040000,6040000,0
            9,0,l,pws,6040,${650 * s},600,700
            9,0,l,pwi,scrn,${200 * s},1,0,0
            9,10123,l,pwi,uid,${79.2 * s},0,0,0
            9,1000,l,pwi,uid,${33.8 * s},0,0,0
            """.trimIndent(),
            capacityHint = 6040.0,
        )
        assertEquals(s, raw.detectedUnit!!, 0.0)
        assertEquals(79.2 * s, raw.byUid[10123]!!.mah, 1.0)
        val mah = raw.inUnit(s)
        assertEquals(79.2, mah.byUid[10123]!!.mah, 1e-6)
        assertEquals(200.0, mah.system["scrn"]!!, 1e-6)
        // Converting again to the same unit changes nothing.
        assertEquals(mah, mah.inUnit(s))
    }
}
