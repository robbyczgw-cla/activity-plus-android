package xyz.activityplus.android.core

import kotlin.random.Random

/**
 * The storage speed test without Android types: sizes, the MB/s and IOPS math, the verdict on the
 * write speed, and the text format of the last few runs. The I/O itself is in StorageSpeedTest.
 */
object SpeedTest {
    /** One megabyte is 2^20 bytes here; the file is 256 of them. */
    const val MEGABYTE = 1L shl 20
    const val FILE_BYTES = 256 * MEGABYTE
    const val CHUNK_BYTES = 4 shl 20
    const val BLOCK_BYTES = 4096
    const val RANDOM_READS = 2000
    /** Below this the test refuses to run: the file and the phone's own writes need room. */
    const val MIN_FREE_BYTES = 1L shl 30
    const val KEEP_RUNS = 5

    /** Write speed below this is "slow storage". */
    const val SLOW_BELOW_MBPS = 100.0
    /** Write speed above this is "fast". */
    const val FAST_ABOVE_MBPS = 500.0

    enum class Verdict { SLOW, TYPICAL, FAST }

    /** One finished run. [time] is epoch milliseconds, the rest are the measured speeds. */
    data class Run(val time: Long, val writeMbps: Double, val readMbps: Double, val randomMbps: Double, val randomIops: Double)

    fun mbPerSecond(bytes: Long, nanos: Long): Double =
        if (nanos <= 0) 0.0 else bytes.toDouble() / MEGABYTE / (nanos / 1e9)

    fun iops(reads: Int, nanos: Long): Double =
        if (nanos <= 0) 0.0 else reads / (nanos / 1e9)

    fun hasRoom(freeBytes: Long): Boolean = freeBytes >= MIN_FREE_BYTES

    /** Block-aligned offsets inside the file, so every random read is one whole 4 KB block. */
    fun randomOffsets(fileBytes: Long, blockBytes: Int, count: Int, random: Random): LongArray {
        val blocks = fileBytes / blockBytes
        return LongArray(count) { random.nextLong(blocks) * blockBytes }
    }

    fun classify(writeMbps: Double): Verdict = when {
        writeMbps < SLOW_BELOW_MBPS -> Verdict.SLOW
        writeMbps > FAST_ABOVE_MBPS -> Verdict.FAST
        else -> Verdict.TYPICAL
    }

    /** Newest first, at most [KEEP_RUNS]. */
    fun withRun(history: List<Run>, run: Run): List<Run> = (listOf(run) + history).take(KEEP_RUNS)

    /** One run per line: time,write,read,random,iops. Unreadable lines are skipped. */
    fun encode(runs: List<Run>): String = runs.joinToString("\n") {
        listOf(it.time, it.writeMbps, it.readMbps, it.randomMbps, it.randomIops).joinToString(",")
    }

    fun decode(text: String): List<Run> = text.lines().mapNotNull { line ->
        val f = line.split(",")
        if (f.size != 5) return@mapNotNull null
        Run(
            time = f[0].toLongOrNull() ?: return@mapNotNull null,
            writeMbps = f[1].toDoubleOrNull() ?: return@mapNotNull null,
            readMbps = f[2].toDoubleOrNull() ?: return@mapNotNull null,
            randomMbps = f[3].toDoubleOrNull() ?: return@mapNotNull null,
            randomIops = f[4].toDoubleOrNull() ?: return@mapNotNull null,
        )
    }.take(KEEP_RUNS)
}
