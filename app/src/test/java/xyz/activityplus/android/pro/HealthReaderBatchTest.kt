package xyz.activityplus.android.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthReaderBatchTest {
    private val uids = IntArray(313) { 10_000 + it }

    @Test
    fun `a phone with hundreds of apps gets every app, in portions`() {
        val sizes = ArrayList<Int>()
        val out = HealthReader.readInBatches(uids) { part -> sizes += part.size; Array(part.size) { "s${part[it]}" } }
        assertEquals(313, out.size)
        assertEquals("s10312", out[10312])
        assertEquals(50, sizes.max())
    }

    @Test
    fun `a portion too big for one reply is halved until it fits`() {
        val out = HealthReader.readInBatches(uids) { part ->
            if (part.size > 10) throw RuntimeException("too large")
            Array(part.size) { "x" }
        }
        assertEquals(313, out.size)
    }

    @Test
    fun `one app that always fails only loses that app`() {
        val out = HealthReader.readInBatches(uids) { part ->
            if (10_100 in part) throw RuntimeException("this one")
            Array(part.size) { "x" }
        }
        assertEquals(312, out.size)
        assertEquals(null, out[10_100])
    }

    @Test
    fun `when everything fails the error goes up after few calls`() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            HealthReader.readInBatches<String>(uids) { calls++; throw IllegalStateException("broken") }
        }
        assertEquals(true, calls < 20)
    }

    @Test
    fun `a missing permission is not retried`() {
        var calls = 0
        assertThrows(SecurityException::class.java) {
            HealthReader.readInBatches<String>(uids) { calls++; throw SecurityException("no") }
        }
        assertEquals(1, calls)
    }
}
