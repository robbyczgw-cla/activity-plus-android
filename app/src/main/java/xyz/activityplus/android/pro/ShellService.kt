package xyz.activityplus.android.pro

import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Lives in Shizuku's shell process (adb rights). Runs nothing but the three read-only reports
 * below; any other command is refused, so the pro mode can never change the phone.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): ParcelFileDescriptor {
        require(command in ALLOWED) { "not allowed: $command" }
        // A pipe instead of a String: batterystats can be larger than one binder transaction.
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "shell-report") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                p.inputStream.use { it.copyTo(out) }
                p.waitFor()
            }
        }
        return read
    }

    companion object {
        // -c, not --checkin: --checkin may hand out (and then delete) Android's saved report of the previous
        // charge cycle instead of the current one. -c only reads; it leaves out per-app CPU time.
        const val BATTERY = "dumpsys batterystats -c"
        const val MEMORY = "dumpsys meminfo"
        const val CPU = "dumpsys cpuinfo"
        val ALLOWED = setOf(BATTERY, MEMORY, CPU)
    }
}
