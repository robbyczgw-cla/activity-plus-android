package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("dumpsys/$name")!!.readText()

    @Test fun emulatorReport() {
        val r = ThermalParser.parse(fixture("thermalservice.txt"))
        assertEquals(0, r.status)
        assertEquals(listOf(ThermalParser.Sensor("test temperature sensor", 3, 30.8, 0)), r.sensors)
        assertEquals(listOf(ThermalParser.Cooling("test cooling device", 0, 100.0)), r.cooling)
        assertEquals(1, r.activeCooling)
    }

    // The shape of a phone that is throttling: several sensors, cooling devices of which two limit something.
    private val hot = """
        IsStatusOverride: false
        Thermal Status: 2
        Cached temperatures:
            Temperature{mValue=99.0, mType=1, mName=stale, mStatus=0}
        Current temperatures from HAL:
            Temperature{mValue=47.5, mType=1, mName=cpu-big, mStatus=2}
            Temperature{mValue=41.2, mType=4, mName=skin, mStatus=1}
            Temperature{mValue=NaN, mType=2, mName=gpu, mStatus=0}
            Temperature{mValue=3980.0, mType=7, mName=bcl, mStatus=3}
            Temperature{mValue=38.9, mType=3, mName=battery, mStatus=0}
        Current cooling devices from HAL:
            CoolingDevice{mValue=0, mType=2, mName=cpu-cluster0}
            CoolingDevice{mValue=3, mType=2, mName=cpu-cluster1}
            CoolingDevice{mValue=1, mType=3, mName=gpu, with comma}
        HAL Ready: true
    """.trimIndent()

    @Test fun throttlingReportFindsReasonAndCooling() {
        val r = ThermalParser.parse(hot)
        assertEquals(2, r.status)
        // The HAL's current readings win over the cache; the NaN sensor is dropped.
        assertEquals(4, r.sensors.size)
        assertTrue(r.sensors.none { it.name == "stale" })
        assertEquals(2, r.activeCooling)
        assertEquals("gpu, with comma", r.cooling.last().name)
        // Rated highest by Android and in degrees: the CPU, not the bcl (volts) and not the skin.
        assertEquals("cpu-big", r.hottest?.name)
    }

    @Test fun cacheIsUsedWhenTheHalListsNothing() {
        val r = ThermalParser.parse("Thermal Status: 1\nCached temperatures:\n    Temperature{mValue=40.0, mType=4, mName=skin, mStatus=1}\n")
        assertEquals(listOf("skin"), r.sensors.map { it.name })
    }

    @Test fun garbageAndEmptyInputGiveAnEmptyReport() {
        val r = ThermalParser.parse("Can't find service: thermalservice\n\n???\n    Temperature{broken")
        assertNull(r.status)
        assertTrue(r.sensors.isEmpty() && r.cooling.isEmpty())
        assertNull(r.hottest)
        assertEquals(0, r.activeCooling)
        assertNull(ThermalParser.parse("").status)
    }
}
