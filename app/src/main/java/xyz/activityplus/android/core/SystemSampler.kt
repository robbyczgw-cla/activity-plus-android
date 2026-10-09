package xyz.activityplus.android.core

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import java.io.File

/**
 * Builds one [Snapshot] per call. Keeps the previous counters for rates, so call it from one
 * coroutine only (the [Monitor] loop).
 */
class SystemSampler(private val context: Context) {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val storageStats = context.getSystemService(StorageStatsManager::class.java)
    val foreground = ForegroundTracker(context)

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastNetTime = 0L
    private var lastOwnCpuMs = Process.getElapsedCpuTime()
    private var lastOwnWall = SystemClock.elapsedRealtime()
    private val designMah: Double? by lazy { readDesignCapacity() }
    private val cpuCount: Int by lazy { readPossibleCpus() }
    private val maxFreq = HashMap<Int, Double?>()

    private var sensorCache: List<Sensor> = emptyList()
    private var sensorTick = 0
    private var storageCache: StorageState? = null
    private var storageTime = 0L

    fun sample(): Snapshot {
        val now = System.currentTimeMillis()
        val screenOn = powerManager.isInteractive
        return Snapshot(
            timeMillis = now,
            battery = battery(),
            memory = memory(),
            storage = storage(now),
            network = network(),
            cpu = cpu(),
            thermal = thermal(),
            screenOn = screenOn,
            powerSave = powerManager.isPowerSaveMode,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
            foregroundPackage = if (screenOn) foreground.current() else null,
        )
    }

    // Battery

    fun battery(): BatteryState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            else -> ChargeStatus.UNKNOWN
        }
        val plug = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            8 /* BATTERY_PLUGGED_DOCK, API 33 */ -> PlugType.DOCK
            else -> PlugType.NONE
        }
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            else -> BatteryHealth.UNKNOWN
        }
        val levelFraction = if (level >= 0 && scale > 0) level.toDouble() / scale else 0.0
        val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).let { mv ->
            // A few vendors report volts or µV instead of mV.
            when {
                mv in 1..10 -> mv.toDouble()
                mv > 100_000 -> mv / 1_000_000.0
                else -> mv / 1000.0
            }
        }
        val rawCurrent = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val rawCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // A gauge whose charge implies a battery under 500 mAh reports a placeholder (emulators do).
        val chargeMah = BatteryMath.chargeMah(rawCounter, designMah)?.takeIf { it / maxOf(levelFraction, 0.05) >= 500 }
        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        val maxCurrentUa = intent?.getIntExtra("max_charging_current", 0) ?: 0
        val maxVoltageUv = intent?.getIntExtra("max_charging_voltage", 0) ?: 0
        val adapterWatts = if (maxCurrentUa > 0 && maxVoltageUv > 0) {
            maxCurrentUa / 1_000_000.0 * (maxVoltageUv / 1_000_000.0)
        } else null
        val remaining = batteryManager.computeChargeTimeRemaining().takeIf { it > 0 }?.div(1000)
        return BatteryState(
            levelFraction = levelFraction,
            status = status,
            plug = plug,
            health = health,
            temperatureC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            voltageV = voltage,
            currentMa = BatteryMath.currentMa(rawCurrent, status, plug != PlugType.NONE),
            chargeMah = chargeMah,
            estimatedFullMah = BatteryMath.estimatedFullMah(chargeMah, levelFraction, designMah),
            designMah = designMah,
            cycleCount = cycles,
            adapterMaxWatts = adapterWatts?.takeIf { it in 1.0..300.0 },
            chargeTimeRemainingSeconds = remaining,
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        )
    }

    /** Rated capacity from the hidden PowerProfile class; many monitors read it the same way. */
    private fun readDesignCapacity(): Double? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(context)
        (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).takeIf { it > 100 }
    } catch (_: Throwable) {
        null
    }

    // Memory

    fun memory(): MemoryState {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val meminfo = readMeminfo()
        return MemoryState(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            thresholdBytes = info.threshold,
            lowMemory = info.lowMemory,
            swapTotalBytes = (meminfo["SwapTotal"] ?: 0) * 1024,
            swapFreeBytes = (meminfo["SwapFree"] ?: 0) * 1024,
            cachedBytes = (meminfo["Cached"] ?: 0) * 1024,
            freeBytes = (meminfo["MemFree"] ?: 0) * 1024,
            buffersBytes = (meminfo["Buffers"] ?: 0) * 1024,
            zramOriginalBytes = zram?.first,
            zramCompressedBytes = zram?.second,
        )
    }

    /** /sys/block/zram0/mm_stat: original and compressed size; often closed to apps. */
    private val zram: Pair<Long, Long>?
        get() = runCatching {
            val f = File("/sys/block/zram0/mm_stat").readText().trim().split(Regex("\\s+"))
            f[0].toLong() to f[1].toLong()
        }.getOrNull()

    private fun readMeminfo(): Map<String, Long> = try {
        File("/proc/meminfo").readLines().mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) parts[0].trimEnd(':') to (parts[1].toLongOrNull() ?: return@mapNotNull null) else null
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    // Storage

    private fun storage(now: Long): StorageState {
        storageCache?.let { if (now - storageTime < 30_000) return it }
        val state = try {
            StorageState(
                totalBytes = storageStats.getTotalBytes(StorageManager.UUID_DEFAULT),
                freeBytes = storageStats.getFreeBytes(StorageManager.UUID_DEFAULT),
            )
        } catch (_: Exception) {
            val fs = StatFs(Environment.getDataDirectory().path)
            StorageState(fs.totalBytes, fs.availableBytes)
        }
        storageCache = state
        storageTime = now
        return state
    }

    // Network

    private fun network(): NetworkState {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        var rxRate = 0.0
        var txRate = 0.0
        if (lastRx >= 0 && rx >= lastRx && tx >= lastTx && now > lastNetTime) {
            val seconds = (now - lastNetTime) / 1000.0
            rxRate = (rx - lastRx) / seconds
            txRate = (tx - lastTx) / seconds
        }
        lastRx = rx; lastTx = tx; lastNetTime = now

        val active = connectivity.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val transport = when {
            caps == null -> Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            vpn -> Transport.VPN
            else -> Transport.NONE
        }
        val wifi = caps?.transportInfo as? WifiInfo
        val addresses = active?.let { connectivity.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address.hostAddress ?: "" }?.filter { it.isNotEmpty() } ?: emptyList()
        return NetworkState(
            rxBytesPerSecond = rxRate,
            txBytesPerSecond = txRate,
            transport = transport,
            vpn = vpn,
            wifiRssi = wifi?.rssi?.takeIf { it in -127..0 },
            wifiLinkMbps = wifi?.linkSpeed?.takeIf { it > 0 },
            wifiFrequencyMhz = wifi?.frequency?.takeIf { it > 0 },
            wifiGeneration = if (Build.VERSION.SDK_INT >= 30) {
                when (wifi?.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11N -> 4
                    ScanResult.WIFI_STANDARD_11AC -> 5
                    ScanResult.WIFI_STANDARD_11AX -> 6
                    8 /* WIFI_STANDARD_11BE, API 33 */ -> 7
                    else -> null
                }
            } else null,
            wifiTxMbps = wifi?.txLinkSpeedMbps?.takeIf { it > 0 },
            wifiRxMbps = wifi?.rxLinkSpeedMbps?.takeIf { it > 0 },
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            addresses = addresses,
        )
    }

    // CPU

    private fun cpu(): CpuState {
        val cores = (0 until cpuCount).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i"
            val online = if (i == 0) true else readLong("$base/online")?.let { it == 1L } ?: true
            val max = maxFreq.getOrPut(i) { readLong("$base/cpufreq/cpuinfo_max_freq")?.takeIf { it >= MIN_KHZ }?.div(1000.0) }
            val cur = if (online) readLong("$base/cpufreq/scaling_cur_freq")?.takeIf { it >= MIN_KHZ }?.div(1000.0) else null
            CoreClock(i, cur, max, online)
        }
        val cpuMs = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        val own = if (wall > lastOwnWall) (cpuMs - lastOwnCpuMs).toDouble() / (wall - lastOwnWall) else 0.0
        lastOwnCpuMs = cpuMs; lastOwnWall = wall
        return CpuState(cores, own.coerceAtLeast(0.0))
    }

    private fun readPossibleCpus(): Int = try {
        val text = File("/sys/devices/system/cpu/possible").readText().trim()
        val last = text.split(",").last().split("-").last().toInt()
        last + 1
    } catch (_: Exception) {
        Runtime.getRuntime().availableProcessors()
    }

    // Thermal

    private fun thermal(): ThermalState {
        val status = ThermalStatus.entries.getOrElse(powerManager.currentThermalStatus) { ThermalStatus.NONE }
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            powerManager.getThermalHeadroom(10).toDouble().takeIf { !it.isNaN() && it > 0 }
        } else null
        if (sensorTick++ % 15 == 0) sensorCache = readThermalZones()
        return ThermalState(status, headroom, sensorCache)
    }

    /** Most phones block these for apps; whatever is readable is shown. */
    private fun readThermalZones(): List<Sensor> {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return emptyList()
        return zones.mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val raw = readLong("${zone.path}/temp") ?: return@mapNotNull null
            val celsius = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (celsius <= 0 || celsius > 130) null else Sensor(type, celsius)
        }.sortedByDescending { it.celsius }
    }

    private companion object {
        /** Real CPU clocks are at least 100 MHz; emulators report placeholders of 1 or 2 kHz. */
        const val MIN_KHZ = 100_000L
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (_: Exception) {
        null
    }
}
