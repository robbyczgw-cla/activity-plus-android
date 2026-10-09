package xyz.activityplus.android.pro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
 * How the pro mode reaches the system reports: a DUMP grant from a computer, or Shizuku.
 * Nothing here runs unless the user taps "Measure now".
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

    fun computerCommand(pkg: String) = COMPUTER_GRANTS.joinToString(" && ") { "pm grant $pkg $it" }.let { "adb shell \"$it\"" }

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
        val granted = COMPUTER_GRANTS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) Access.DUMP else shizuku
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
