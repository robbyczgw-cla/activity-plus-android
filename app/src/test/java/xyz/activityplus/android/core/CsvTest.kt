package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class CsvTest {
    @Test fun plainFieldsStayAsTheyAre() {
        assertEquals("com.android.chrome", Csv.field("com.android.chrome"))
        assertEquals("", Csv.field(""))
    }

    @Test fun commaQuoteAndLineBreakAreQuoted() {
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
        assertEquals("\"cr\rhere\"", Csv.field("cr\rhere"))
    }

    @Test fun lineMixesTypesAndEndsInABreak() {
        assertEquals("2026-10-18,0.5,,1,x y\n", Csv.line(listOf("2026-10-18", 0.5, null, true, "x y")))
        assertEquals("a,\"b,c\",7\n", Csv.line(listOf("a", "b,c", 7)))
    }

    @Test fun numbersIgnoreThePhoneLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1234.5", Csv.number(1234.5))
            assertEquals("0.3333", Csv.number(1.0 / 3))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun numbersAreShortAndNeverScientific() {
        assertEquals("2", Csv.number(2.0))
        assertEquals("0", Csv.number(0.00001))
        assertEquals("0", Csv.number(-0.00001))
        assertEquals("-12.5", Csv.number(-12.5))
        assertEquals("100000000", Csv.number(1e8))
    }

    @Test fun missingNumbersAreEmpty() {
        assertEquals("", Csv.number(null))
        assertEquals("", Csv.number(Double.NaN))
        assertEquals("", Csv.number(Double.POSITIVE_INFINITY))
    }

    @Test fun timeCarriesTheOffset() {
        val zone = ZoneId.of("Europe/Vienna")
        // 2026-10-18 07:41:05 UTC; Vienna is on summer time until 25 October.
        val millis = 1_792_309_265_000L
        assertEquals("2026-10-18T09:41:05+02:00", Csv.time(millis, zone))
        assertEquals("2026-10-18T07:41:05Z", Csv.time(millis, ZoneId.of("UTC")))
    }

    @Test fun zipNameHasTheDay() {
        assertEquals("activityplus-export-2026-10-18.zip", Csv.zipName(LocalDate.of(2026, 10, 18)))
    }
}
