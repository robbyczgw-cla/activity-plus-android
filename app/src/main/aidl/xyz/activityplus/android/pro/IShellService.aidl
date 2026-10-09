package xyz.activityplus.android.pro;

// Runs in Shizuku's shell process. Only the commands in ShellService.ALLOWED are executed.
interface IShellService {
    // Shizuku calls this transaction code to stop the service.
    void destroy() = 16777114;

    ParcelFileDescriptor run(String command) = 1;
}
