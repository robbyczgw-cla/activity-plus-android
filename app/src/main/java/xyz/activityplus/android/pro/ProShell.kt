package xyz.activityplus.android.pro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import xyz.activityplus.android.BuildConfig
import kotlin.coroutines.resume

/**
 * How the pro mode reaches the system reports: a DUMP grant from a computer, or Shizuku. Both can
 * add [EXTRA_GRANTS] for the per-app counters, the battery data and exact power readings.
 * Nothing here runs unless the user taps "Measure now", or, with the computer grant, the
 * automatic background measurement reads the battery report (BackgroundSampler).
 */
object ProShell {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4712

    enum class Access { DUMP, SHIZUKU, SHIZUKU_PERMISSION, SHIZUKU_NOT_RUNNING, SHIZUKU_NOT_INSTALLED, SHIZUKU_TOO_OLD }

    val Access.ready get() = this == Access.DUMP || this == Access.SHIZUKU

    /**
     * What a normal app may read with grants from a computer, tested on Android 15: batterystats
     * needs all three; meminfo and cpuinfo stay closed to apps (SELinux), so that route gives
     * battery per app only.
     */
    val COMPUTER_GRANTS = listOf(
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.INTERACT_ACROSS_USERS",
    )

    /**
     * Read-only extras since 0.4.0, also grantable from a shell: BATTERY_STATS opens Android's per-app
     * counters (wakeups, wakelocks, CPU, jobs) and the battery's own data (state of health, dates);
     * ACCESS_FINE_POWER_MONITORS gives exact power per component instead of coarse, noisy values.
     * The latter sits behind a feature flag (off in the Android 16 release emulator): pm grant fails
     * with "Unknown permission" where the phone does not define it, so only [extraGrants] are offered.
     */
    val EXTRA_GRANTS = listOf("android.permission.BATTERY_STATS", FINE_POWER_MONITORS)
    private const val FINE_POWER_MONITORS = "android.permission.ACCESS_FINE_POWER_MONITORS"

    /** The [EXTRA_GRANTS] this phone knows. */
    fun extraGrants(context: Context): List<String> = EXTRA_GRANTS.filter { isDefined(context, it) }

    /** Whether the phone can grant exact power readings at all. */
    fun finePowerMonitorsExist(context: Context) = Build.VERSION.SDK_INT >= 36 && isDefined(context, FINE_POWER_MONITORS)

    private fun isDefined(context: Context, permission: String) =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess

    fun computerCommand(context: Context) = (COMPUTER_GRANTS + extraGrants(context))
        .joinToString(" && ") { "pm grant ${context.packageName} $it" }.let { "adb shell \"$it\"" }

    /** Per-app counters and battery data (SystemHealthManager, BatteryManager); see [EXTRA_GRANTS]. */
    fun hasBatteryStats(context: Context): Boolean =
        context.checkSelfPermission("android.permission.BATTERY_STATS") == PackageManager.PERMISSION_GRANTED

    /** Exact power monitor readings (Android 16+). Without it the readings are coarse but still there. */
    fun hasFinePowerMonitors(context: Context): Boolean = Build.VERSION.SDK_INT >= 36 &&
        context.checkSelfPermission(FINE_POWER_MONITORS) == PackageManager.PERMISSION_GRANTED

    fun missingExtraGrants(context: Context): List<String> =
        extraGrants(context).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /**
     * Grants [extraGrants] to Activity+ itself through Shizuku, once; like the computer grant they stay
     * after restarts. Only these fixed pm grant lines are allowed in [ShellService].
     */
    suspend fun grantExtrasWithShizuku(context: Context): Boolean {
        if (shizukuState(context) != Access.SHIZUKU) return false
        val service = service(context)
        withContext(Dispatchers.IO) {
            for (permission in missingExtraGrants(context)) {
                val command = ShellService.grantCommand(permission)
                ParcelFileDescriptor.AutoCloseInputStream(service.run(command)).bufferedReader().use { it.readText() }
            }
        }
        return missingExtraGrants(context).isEmpty()
    }

    /** State of the Shizuku route alone, whatever the computer grant says. */
    fun shizukuState(context: Context): Access {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        return when {
            !running -> if (installed) Access.SHIZUKU_NOT_RUNNING else Access.SHIZUKU_NOT_INSTALLED
            Shizuku.isPreV11() -> Access.SHIZUKU_TOO_OLD
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Access.SHIZUKU
            else -> Access.SHIZUKU_PERMISSION
        }
    }

    /** Shizuku first: it reads all three reports; the computer grant only the battery one. */
    fun access(context: Context): Access {
        val shizuku = shizukuState(context)
        if (shizuku == Access.SHIZUKU) return shizuku
        return if (hasComputerGrant(context)) Access.DUMP else shizuku
    }

    /** The computer grant alone, whatever Shizuku says. */
    fun hasComputerGrant(context: Context): Boolean =
        COMPUTER_GRANTS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The battery report through the computer grant only; never starts or binds Shizuku. */
    suspend fun batteryWithGrant(context: Context): String {
        check(hasComputerGrant(context)) { "no computer grant" }
        return withContext(Dispatchers.IO) {
            val p = ProcessBuilder(ShellService.BATTERY.split(' ')).redirectErrorStream(true).start()
            // Unattended, so a hanging dumpsys must not block the next readings.
            kotlin.concurrent.thread(isDaemon = true, name = "battery-report-timeout") {
                if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            }
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        }
    }

    /** Shows Shizuku's own dialog; [onResult] gets true when the user allows Activity+. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Runs one of [ShellService.ALLOWED] and returns its output. */
    suspend fun run(context: Context, command: String): String {
        require(command in ShellService.ALLOWED)
        return when (access(context)) {
            // With the DUMP grant the app itself may read the reports.
            Access.DUMP -> withContext(Dispatchers.IO) {
                require(command == ShellService.BATTERY) { "only batterystats is open to apps" }
                val p = ProcessBuilder(command.split(' ')).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
            }
            Access.SHIZUKU -> {
                val service = service(context)
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(service.run(command)).bufferedReader().use { it.readText() }
                }
            }
            else -> error("pro mode not available")
        }
    }

    private var bound: IShellService? = null

    private suspend fun service(context: Context): IShellService {
        bound?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("report")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        return withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val s = IShellService.Stub.asInterface(binder)
                        bound = s
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        bound = null
                    }
                }
                Shizuku.bindUserService(args, connection)
            }
        }
    }
}
