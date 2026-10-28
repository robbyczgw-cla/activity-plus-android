package xyz.activityplus.android.pro

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the resources of one blocking command, including when cancellation wins a startup race. */
internal class CommandResources {
    private var closed = false
    private val resources = mutableListOf<Closeable>()
    @Synchronized fun add(resource: Closeable) {
        if (closed) resource.close() else resources.add(resource)
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }
}

internal object CommandDeadline {
    const val SECONDS = 60L
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "command-deadline").apply { isDaemon = true } }

    suspend fun <T> run(block: (CommandResources) -> T): T = suspendCancellableCoroutine { cont ->
        val resources = CommandResources()
        val completed = AtomicBoolean(false)
        val timeout = timer.schedule({
            if (completed.compareAndSet(false, true)) {
                cont.resumeWithException(IllegalStateException("Command timed out after $SECONDS seconds"))
                resources.close()
            }
        }, SECONDS, TimeUnit.SECONDS)
        cont.invokeOnCancellation { completed.set(true); timeout.cancel(false); resources.close() }
        thread(isDaemon = true, name = "command-reader") {
            try {
                val result = block(resources)
                if (completed.compareAndSet(false, true)) cont.resume(result)
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) cont.resumeWithException(e)
            } finally {
                timeout.cancel(false)
                resources.close()
            }
        }
    }
}
