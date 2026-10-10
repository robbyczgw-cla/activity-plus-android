package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.SpeedTest.Run
import xyz.activityplus.android.core.SpeedTest.Verdict
import kotlin.random.Random

class SpeedTestTest {
    private val mb = 1L shl 20
    private val second = 1_000_000_000L

    @Test fun mbPerSecondUsesBinaryMegabytes() {
        assertEquals(128.0, SpeedTest.mbPerSecond(256 * mb, 2 * second), 1e-9)
        assertEquals(1.0, SpeedTest.mbPerSecond(mb, second), 1e-9)
        assertEquals(0.5, SpeedTest.mbPerSecond(mb, 2 * second), 1e-9)
    }

    @Test fun mbPerSecondOfRandomReadsIsBlocksTimesSize() {
        // 2000 blocks of 4 KB in one second: 8000 KB, which is 7.8125 MB/s in binary megabytes.
        val bytes = SpeedTest.RANDOM_READS.toLong() * SpeedTest.BLOCK_BYTES
        assertEquals(7.8125, SpeedTest.mbPerSecond(bytes, second), 1e-9)
    }

    @Test fun zeroTimeGivesZeroNotInfinity() {
        assertEquals(0.0, SpeedTest.mbPerSecond(mb, 0), 0.0)
        assertEquals(0.0, SpeedTest.iops(2000, 0), 0.0)
    }

    @Test fun iopsCountsReadsPerSecond() {
        assertEquals(4000.0, SpeedTest.iops(2000, second / 2), 1e-9)
        assertEquals(2000.0, SpeedTest.iops(2000, second), 1e-9)
    }

    @Test fun chunksFitTheFileExactly() {
        assertEquals(0L, SpeedTest.FILE_BYTES % SpeedTest.CHUNK_BYTES)
        assertEquals(256 * mb, SpeedTest.FILE_BYTES)
    }

    @Test fun refusesToRunBelowOneGibibyte() {
        assertTrue(SpeedTest.hasRoom(1L shl 30))
        assertFalse(SpeedTest.hasRoom((1L shl 30) - 1))
        assertFalse(SpeedTest.hasRoom(0))
    }

    @Test fun classifiesWriteSpeed() {
        assertEquals(Verdict.SLOW, SpeedTest.classify(0.0))
        assertEquals(Verdict.SLOW, SpeedTest.classify(99.9))
        assertEquals(Verdict.TYPICAL, SpeedTest.classify(100.0))
        assertEquals(Verdict.TYPICAL, SpeedTest.classify(300.0))
        assertEquals(Verdict.TYPICAL, SpeedTest.classify(500.0))
        assertEquals(Verdict.FAST, SpeedTest.classify(500.1))
    }

    @Test fun randomOffsetsAreWholeBlocksInsideTheFile() {
        val offsets = SpeedTest.randomOffsets(SpeedTest.FILE_BYTES, SpeedTest.BLOCK_BYTES, SpeedTest.RANDOM_READS, Random(7))
        assertEquals(SpeedTest.RANDOM_READS, offsets.size)
        assertTrue(offsets.all { it % SpeedTest.BLOCK_BYTES == 0L })
        assertTrue(offsets.all { it >= 0 && it + SpeedTest.BLOCK_BYTES <= SpeedTest.FILE_BYTES })
    }

    @Test fun randomOffsetsOfASmallFileStayOnItsBlocks() {
        val offsets = SpeedTest.randomOffsets(3L * 4096, 4096, 500, Random(1))
        assertEquals(setOf(0L, 4096L, 8192L), offsets.toSet())
    }

    @Test fun randomOffsetsAreReproducibleWithTheSameSeed() {
        val a = SpeedTest.randomOffsets(SpeedTest.FILE_BYTES, 4096, 100, Random(42))
        val b = SpeedTest.randomOffsets(SpeedTest.FILE_BYTES, 4096, 100, Random(42))
        assertTrue(a.contentEquals(b))
    }

    private fun run(t: Long) = Run(t, 300.0 + t, 400.0, 20.5, 5000.0)

    @Test fun newestRunComesFirstAndOnlyFiveAreKept() {
        var history = emptyList<Run>()
        for (t in 1L..6L) history = SpeedTest.withRun(history, run(t))
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), history.map { it.time })
    }

    @Test fun encodeAndDecodeRoundTrip() {
        val runs = listOf(run(3), run(2), run(1))
        assertEquals(runs, SpeedTest.decode(SpeedTest.encode(runs)))
    }

    @Test fun decodeSkipsBrokenLines() {
        val text = listOf(
            "1700000000000,300.5,410.25,22.0,5600.0",
            "not,a,run",
            "1700000000001,x,410.25,22.0,5600.0",
            "",
            "1700000000002,300.5,410.25,22.0",
        ).joinToString("\n")
        assertEquals(listOf(Run(1700000000000L, 300.5, 410.25, 22.0, 5600.0)), SpeedTest.decode(text))
    }

    @Test fun decodeKeepsAtMostFive() {
        val text = SpeedTest.encode((1L..8L).map { run(it) })
        assertEquals(5, SpeedTest.decode(text).size)
    }

    @Test fun decodeOfNothingIsEmpty() {
        assertTrue(SpeedTest.decode("").isEmpty())
    }
}
