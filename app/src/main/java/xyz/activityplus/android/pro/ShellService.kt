package xyz.activityplus.android.pro

import android.os.ParcelFileDescriptor
import xyz.activityplus.android.BuildConfig
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Lives in Shizuku's shell process (adb rights). Runs nothing but the read-only reports
 * below and the grants of Activity+'s own read permissions; any other command is refused, so the
 * pro mode can never change anything else on the phone.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): ParcelFileDescriptor {
        require(command in ALLOWED) { "not allowed: $command" }
        // A pipe instead of a String: batterystats can be larger than one binder transaction.
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "shell-report") {
            val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            var process: Process? = null
            try {
                val p = ProcessBuilder(command.split(' ')).redirectErrorStream(true).start()
                process = p
                val timeout = watchdog.schedule({
                    runCatching { write.closeWithError("Command timed out after 60 seconds") }
                    p.destroyForcibly()
                }, CommandDeadline.SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
                    p.inputStream.use { it.copyTo(out) }
                    p.waitFor()
                    out.close()
                } finally { timeout.cancel(false) }
            } catch (e: Exception) {
                runCatching { write.closeWithError(e.message ?: "Command failed") }
            } finally {
                watchdog.shutdownNow()
                process?.destroyForcibly()
                runCatching { write.close() }
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
        /** Which app used location, camera, microphone … and when (Android's app-op history). */
        const val APP_OPS = "dumpsys appops"
        /** Alarms each app has set, wakeups included. */
        const val ALARMS = "dumpsys alarm"
        /** Background jobs each app has scheduled, with their constraints and history. */
        const val JOBS = "dumpsys jobscheduler"
        /** Thermal status, throttling and the temperature sensors. */
        const val THERMAL = "dumpsys thermalservice"
        /** Doze state and its whitelist. */
        const val DEVICE_IDLE = "dumpsys deviceidle"
        /**
         * Reports an app with the computer grant (DUMP) may read itself, tested on Android 15;
         * meminfo and cpuinfo stay closed to apps (SELinux).
         */
        val OPEN_WITH_DUMP = setOf(BATTERY, APP_OPS, ALARMS, JOBS, THERMAL, DEVICE_IDLE)
        fun grantCommand(permission: String) = "pm grant ${BuildConfig.APPLICATION_ID} $permission"
        val ALLOWED = OPEN_WITH_DUMP + setOf(MEMORY, CPU) + ProShell.EXTRA_GRANTS.map(::grantCommand)
    }
}
