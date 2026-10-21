package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class CsvParseTest {
    private fun parse(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        Csv.forEachRecord(StringReader(text)) { out += it }
        return out
    }

    @Test fun plainRows() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), parse("a,b,c\n1,2,3\n"))
    }

    @Test fun lastRowNeedsNoLineBreak() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), parse("a,b\n1,2"))
    }

    @Test fun emptyFieldsStayInTheirPlace() {
        assertEquals(listOf(listOf("1", "", "3", "")), parse("1,,3,\n"))
        assertEquals(listOf(listOf("", "x")), parse(",x\n"))
    }

    @Test fun quotedFieldsHoldCommasQuotesAndLineBreaks() {
        assertEquals(listOf(listOf("a,b", "say \"hi\"", "two\nlines", "end")), parse("\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",end\n"))
    }

    @Test fun everyKindOfLineBreakEndsARecord() {
        val expected = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(expected, parse("a\nb\nc\n"))
        assertEquals(expected, parse("a\r\nb\r\nc\r\n"))
        assertEquals(expected, parse("a\rb\rc\r"))
    }

    @Test fun aLineBreakInsideQuotesIsKeptAsItWas() {
        assertEquals(listOf(listOf("x\r\ny", "z")), parse("\"x\r\ny\",z\r\n"))
    }

    @Test fun byteOrderMarkIsIgnored() {
        assertEquals(listOf(listOf("time", "epoch_ms")), parse("﻿time,epoch_ms\n"))
    }

    @Test fun emptyLinesAreSkippedButAQuotedEmptyFieldIsAValue() {
        assertEquals(listOf(listOf("a"), listOf("b")), parse("a\n\n\nb\n"))
        assertEquals(listOf(listOf("")), parse("\"\"\n"))
        assertEquals(emptyList<List<String>>(), parse(""))
        assertEquals(emptyList<List<String>>(), parse("\n\r\n"))
    }

    @Test fun oddFilesDoNotThrow() {
        // A quote in the middle of a plain field is just a character.
        assertEquals(listOf(listOf("5\" pipe", "x")), parse("5\" pipe,x\n"))
        // A quote left open takes the rest of the file as the field.
        assertEquals(listOf(listOf("a", "b,c\n")), parse("a,\"b,c\n"))
        // Text after the closing quote is kept.
        assertEquals(listOf(listOf("abc", "d")), parse("\"a\"bc,d\n"))
    }

    @Test fun whatLineWritesComesBackUnchanged() {
        val rows = listOf(
            listOf<Any?>("com.example.app", "plain", 12, 0.5, null, true),
            listOf<Any?>("name, with comma", "say \"hi\"", "line\nbreak", "cr\rhere", "", " spaced "),
            listOf<Any?>("{\"t\":1,\"a\":[1,2]}", "ü ß 漢字", "", null, "x", "y"),
        )
        val text = rows.joinToString("") { Csv.line(it) }
        val back = parse(text)
        assertEquals(rows.size, back.size)
        for ((written, read) in rows.zip(back)) {
            assertEquals(written.map { if (it is Boolean) (if (it) "1" else "0") else it?.toString() ?: "" }, read)
        }
    }
}
