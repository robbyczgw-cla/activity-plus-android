package xyz.activityplus.android.core

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Runs the three measurements of [SpeedTest] on real storage: a sequential write (with sync), a
 * sequential read from a fresh stream, and random 4 KB reads. The temp file is always deleted.
 */
object StorageSpeedTest {
    private const val FILE_NAME = "speedtest.tmp"
    private const val PROGRESS_EVERY_READ = 100

    enum class Phase { WRITE, READ, RANDOM }

    class Progress(val phase: Phase, val fraction: Float)

    sealed interface Outcome {
        data class Done(val run: SpeedTest.Run) : Outcome
        data object NoRoom : Outcome
        data object Failed : Outcome
    }

    /** Runs on IO. Cancelling the coroutine stops between chunks and still deletes the file. */
    suspend fun run(dir: File, onProgress: (Progress) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val free = StatFs(dir.path).availableBytes
        if (!SpeedTest.hasRoom(free)) return@withContext Outcome.NoRoom
        val file = File(dir, FILE_NAME)
        try {
            file.delete()
            // One random chunk, written over and over: the random data stays out of the timing.
            val chunk = Random.Default.nextBytes(SpeedTest.CHUNK_BYTES)
            val chunks = (SpeedTest.FILE_BYTES / SpeedTest.CHUNK_BYTES).toInt()
            val writeNanos = nanos {
                FileOutputStream(file).use { out ->
                    repeat(chunks) { i ->
                        ensureActive()
                        out.write(chunk)
                        onProgress(Progress(Phase.WRITE, (i + 1).toFloat() / chunks))
                    }
                    // Without the sync the test would time the page cache, not the storage.
                    out.fd.sync()
                }
            }

            // Drop the file from the page cache so the reads hit the storage; some kernels ignore the hint,
            // so the UI still says Android may serve part of it from memory.
            dropCache(file)
            var readBytes = 0L
            val readNanos = nanos {
                FileInputStream(file).use { input ->
                    val buf = ByteArray(SpeedTest.CHUNK_BYTES)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        readBytes += n
                        onProgress(Progress(Phase.READ, readBytes.toFloat() / SpeedTest.FILE_BYTES))
                    }
                }
            }
            if (readBytes != SpeedTest.FILE_BYTES) throw IOException("short read")

            val offsets = SpeedTest.randomOffsets(SpeedTest.FILE_BYTES, SpeedTest.BLOCK_BYTES, SpeedTest.RANDOM_READS, Random.Default)
            val block = ByteArray(SpeedTest.BLOCK_BYTES)
            dropCache(file)
            val randomNanos = nanos {
                RandomAccessFile(file, "r").use { raf ->
                    offsets.forEachIndexed { i, offset ->
                        ensureActive()
                        raf.seek(offset)
                        raf.readFully(block)
                        if (i % PROGRESS_EVERY_READ == 0) onProgress(Progress(Phase.RANDOM, i.toFloat() / offsets.size))
                    }
                }
            }

            Outcome.Done(
                SpeedTest.Run(
                    time = System.currentTimeMillis(),
                    writeMbps = SpeedTest.mbPerSecond(SpeedTest.FILE_BYTES, writeNanos),
                    readMbps = SpeedTest.mbPerSecond(SpeedTest.FILE_BYTES, readNanos),
                    randomMbps = SpeedTest.mbPerSecond(SpeedTest.RANDOM_READS.toLong() * SpeedTest.BLOCK_BYTES, randomNanos),
                    randomIops = SpeedTest.iops(SpeedTest.RANDOM_READS, randomNanos),
                ),
            )
        } catch (e: IOException) {
            Outcome.Failed
        } finally {
            file.delete()
        }
    }

    private inline fun nanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun dropCache(file: File) {
        runCatching {
            FileInputStream(file).use { android.system.Os.posix_fadvise(it.fd, 0, 0, android.system.OsConstants.POSIX_FADV_DONTNEED) }
        }
    }
}
