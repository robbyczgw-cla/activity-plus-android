package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.pro.ProParser

class ChargeUnitTest {
    @Test fun aNormalReportStaysInMah() {
        // Computed 640 mAh for a battery that lost 600 to 700: the usual, slightly off estimate.
        assertEquals(1.0, ChargeUnit.detect(640.0, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
    }

    @Test fun microcoulombsAreRecognisedFromTheDrainedCharge() {
        // The vivo case: values in µC, so the sum is 3.6 million times the mAh drained.
        assertEquals(3_600_000.0, ChargeUnit.detect(650.0 * 3_600_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
    }

    @Test fun anAttributionThatCoversOnlyHalfStillPicksTheRightUnit() {
        assertEquals(3_600_000.0, ChargeUnit.detect(330.0 * 3_600_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
        assertEquals(1_000.0, ChargeUnit.detect(650.0 * 1_000, 600.0, 700.0, 6040.0, 6040.0)!!, 0.0)
    }

    @Test fun withoutDrainTheCapacityTellsTheUnit() {
        assertEquals(3_600_000.0, ChargeUnit.detect(1e9, 0.0, 0.0, 6040.0 * 3_600_000, 6040.0)!!, 0.0)
        // A normal capacity and no drain: nothing to go on.
        assertNull(ChargeUnit.detect(1e9, 0.0, 0.0, 6040.0, 6040.0))
    }

    @Test fun chooseFallsBackToWhatThePhoneUsedBeforeOrTheLikeliest() {
        assertEquals(1.0, ChargeUnit.choose(null, null, 120.0, 6040.0), 0.0)
        assertEquals(1_000_000.0, ChargeUnit.choose(null, 1_000_000.0, 285_078_419.0, 6040.0), 0.0)
        assertEquals(3_600_000.0, ChargeUnit.choose(null, null, 285_078_419.0, 6040.0), 0.0)
        // The report said mAh, but no app can use 285 million of them: not believed.
        assertEquals(3_600_000.0, ChargeUnit.choose(1.0, null, 285_078_419.0, 6040.0), 0.0)
    }

    @Test fun implausibleMeansMoreThanTenBatteries() {
        assertFalse(ChargeUnit.implausible(5_000.0, 6040.0))
        assertTrue(ChargeUnit.implausible(285_078_419.0, 6040.0))
        assertFalse(ChargeUnit.implausible(90_000.0, null))
    }

    @Test fun theParserReportsMahForAPhoneThatWritesMicrocoulombs() {
        val s = 3_600_000.0
        val report = ProParser.batteryCheckin(
            """
            9,0,l,bt,0,72000000,72000000,72000000,72000000,1792000000000,0,0,6040,6040000,6040000,0
            9,0,l,pws,6040,${650 * s},600,700
            9,0,l,pwi,scrn,${200 * s},1,0,0
            9,10123,l,pwi,uid,${79.2 * s},0,0,0
            9,1000,l,pwi,uid,${33.8 * s},0,0,0
            """.trimIndent(),
            capacityHint = 6040.0,
        )
        assertEquals(s, report.unitScale, 0.0)
        assertTrue(report.unitDetected)
        assertEquals(79.2, report.byUid[10123]!!.mah, 1e-6)
        assertEquals(200.0, report.system["scrn"]!!, 1e-6)
    }
}
