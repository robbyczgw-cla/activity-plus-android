# PowerProfile is read by reflection for the battery's design capacity.
-dontwarn com.android.internal.os.PowerProfile

# Shizuku creates the shell service by class name in its own process.
-keep class xyz.activityplus.android.pro.ShellService { <init>(...); }
