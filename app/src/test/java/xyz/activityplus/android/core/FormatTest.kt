package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatTest {
    @Before fun setUp() { Format.locale = Locale.US }

    @Test fun bytesUseThreeSignificantDigits() {
        assertEquals("27.7 GB", Format.bytes(27_700_000_000).toString())
        assertEquals("494 GB", Format.bytes(494_000_000_000).toString())
        assertEquals("9.73 kB", Format.bytes(9_730).toString())
        assertEquals("512 B", Format.bytes(512).toString())
    }

    @Test fun ratesInBytesAndBits() {
        assertEquals("9.73 kB/s", Format.rate(9_730.0, bits = false).toString())
        assertEquals("77.8 kbit/s", Format.rate(9_730.0, bits = true).toString())
        assertEquals("0 B/s", Format.rate(0.0, bits = false).toString())
    }

    @Test fun wattsSwitchUnitAtOneWatt() {
        assertEquals("850 mW", Format.watts(850.0).toString())
        assertEquals("4.24 W", Format.watts(4_240.0).toString())
        assertEquals("-2.10 W", Format.watts(-2_100.0).toString())
    }

    @Test fun temperatureInBothUnits() {
        assertEquals("29 °C", Format.temperature(29.2, false).toString())
        assertEquals("85 °F", Format.temperature(29.2, true).toString())
    }

    @Test fun durations() {
        assertEquals("45 s", Format.duration(45))
        assertEquals("12 min", Format.duration(12 * 60 + 5))
        assertEquals("2 h 05 min", Format.duration(2 * 3600 + 5 * 60))
        assertEquals("3 d 4 h", Format.duration(3 * 86_400 + 4 * 3600))
    }
}

class SocNamesTest {
    @Test fun knownChipsByModelBoardOrPlatform() {
        assertEquals("Snapdragon 865", SocNames.lookup("SM8250"))
        assertEquals("Snapdragon 865", SocNames.lookup("kona"))
        assertEquals("Google Tensor G3", SocNames.lookup("zuma"))
        assertEquals("Snapdragon 8 Gen 2", SocNames.lookup("sm8550-ab"))
    }

    @Test fun unknownStaysNull() {
        assertEquals(null, SocNames.lookup("ranchu"))
        assertEquals(null, SocNames.lookup(""))
        // Short platform names only match exactly, so "sunny" is not "sun".
        assertEquals(null, SocNames.lookup("sunny"))
    }
}
