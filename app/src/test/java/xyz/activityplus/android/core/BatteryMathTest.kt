package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryMathTest {
    @Test fun microampsAreConvertedAndSignFollowsStatus() {
        // Retroid Pocket Mini V2: -1059081 µA while discharging.
        assertEquals(-1059.081, BatteryMath.currentMa(-1_059_081, ChargeStatus.DISCHARGING, false)!!, 0.001)
        // Some vendors report a positive value while discharging.
        assertEquals(-850.0, BatteryMath.currentMa(850_000, ChargeStatus.DISCHARGING, false)!!, 0.001)
        assertEquals(1500.0, BatteryMath.currentMa(-1_500_000, ChargeStatus.CHARGING, true)!!, 0.001)
    }

    @Test fun milliampReadingsAreDetected() {
        assertEquals(-420.0, BatteryMath.currentMa(420, ChargeStatus.DISCHARGING, false)!!, 0.001)
    }

    @Test fun missingOrNonsenseCurrentIsNull() {
        assertNull(BatteryMath.currentMa(null, ChargeStatus.DISCHARGING, false))
        assertNull(BatteryMath.currentMa(0, ChargeStatus.DISCHARGING, false))
        assertNull(BatteryMath.currentMa(Int.MIN_VALUE.toLong(), ChargeStatus.DISCHARGING, false))
        assertNull(BatteryMath.currentMa(90_000_000, ChargeStatus.DISCHARGING, false))
    }

    @Test fun chargeCounterInMicroOrMilliampHours() {
        assertEquals(2666.959, BatteryMath.chargeMah(2_666_959, 4000.0)!!, 0.001)
        assertEquals(2666.0, BatteryMath.chargeMah(2_666, 4000.0)!!, 0.001)
        assertNull(BatteryMath.chargeMah(0, 4000.0))
    }

    @Test fun fullCapacityNeedsEnoughCharge() {
        assertEquals(3809.94, BatteryMath.estimatedFullMah(2666.959, 0.70)!!, 0.01)
        assertNull(BatteryMath.estimatedFullMah(300.0, 0.08))
    }

    @Test fun placeholderGaugesAreNotAMeasurement() {
        // Android emulator: charge counter 10000 µAh at 100 %.
        assertNull(BatteryMath.estimatedFullMah(10.0, 1.0, designMah = 1000.0))
        assertNull(BatteryMath.estimatedFullMah(10.0, 1.0))
        assertNull(BatteryMath.estimatedFullMah(700.0, 0.5, designMah = 4000.0)) // 35 % of the rating
        assertEquals(3600.0, BatteryMath.estimatedFullMah(1800.0, 0.5, designMah = 4000.0)!!, 1e-9)
    }

    @Test fun percentPerHour() {
        assertEquals(30.0, BatteryMath.percentPerHour(0.50, 0, 0.60, 1_200_000)!!, 0.001)
        assertNull(BatteryMath.percentPerHour(0.5, 0, 0.5, 10_000))
    }
}
