package xyz.activityplus.android.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaDrm
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.telephony.TelephonyManager
import android.view.Display
import android.hardware.display.DisplayManager
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What the phone is made of, as far as Android tells an app without root. Read once (cameras,
 * codecs and the GPU take a moment), then cached; the live parts stay in [Snapshot].
 */
data class HardwareInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdk: Int,
    val securityPatch: String,
    val build: String,
    val kernel: String?,
    val soc: Soc,
    val gpu: Gpu,
    val display: DisplayInfo?,
    val cameras: List<Camera>,
    val sensors: List<SensorInfo>,
    val media: Media,
    val features: List<Pair<Feature, Boolean>>,
    val volumes: List<Volume>,
    val operator: String?,
    val simSlots: Int?,
) {
    data class Soc(
        val name: String?,
        val vendor: String?,
        val model: String?,
        val hardware: String,
        val abis: List<String>,
        /** Cluster as (core count, min MHz, max MHz). */
        val clusters: List<Triple<Int, Double?, Double?>>,
        val governor: String?,
    )

    data class Gpu(val renderer: String?, val vendor: String?, val glEs: String?, val vulkan: String?)

    data class DisplayInfo(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val inches: Double?,
        val refreshNow: Float,
        val refreshRates: List<Int>,
        val hdr: List<String>,
        val wideColor: Boolean,
    )

    enum class Facing { BACK, FRONT, EXTERNAL }

    data class Camera(
        val id: String,
        val facing: Facing,
        val megapixels: Double?,
        val focalMm: Float?,
        val aperture: Float?,
        val ois: Boolean,
        val flash: Boolean,
    )

    data class SensorInfo(val name: String, val vendor: String, val type: String, val powerMa: Float)

    data class Media(
        val widevine: String?,
        /** Codec name to "decoded in hardware". */
        val decoders: List<Pair<String, Boolean>>,
    )

    enum class Feature { NFC, FINGERPRINT, FACE, ESIM, USB_HOST, INFRARED, BLUETOOTH_LE, WIFI_AWARE, UWB }

    data class Volume(val name: String, val totalBytes: Long, val freeBytes: Long, val removable: Boolean)

    companion object {
        fun read(context: Context): HardwareInfo = HardwareInfo(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            build = Build.DISPLAY,
            kernel = System.getProperty("os.version"),
            soc = soc(),
            gpu = gpu(context),
            display = display(context),
            cameras = runCatching { cameras(context) }.getOrDefault(emptyList()),
            sensors = sensors(context),
            media = Media(widevine(), decoders()),
            features = features(context),
            volumes = volumes(context),
            operator = runCatching {
                context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.takeIf { it.isNotBlank() }
            }.getOrNull(),
            simSlots = runCatching {
                val tm = context.getSystemService(TelephonyManager::class.java)
                if (Build.VERSION.SDK_INT >= 30) tm.supportedModemCount else @Suppress("DEPRECATION") tm.phoneCount
            }.getOrNull(),
        )

        /** Seconds the phone spent in deep sleep since boot: elapsed time minus awake time. */
        fun deepSleepSeconds(): Long = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) / 1000

        private fun soc(): Soc {
            val vendor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN } else null
            val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } else null
            val board = Build.BOARD.lowercase()
            val hardware = Build.HARDWARE
            val cpuinfoHardware = runCatching {
                File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") }?.substringAfter(":")?.trim()
            }.getOrNull()
            val name = SocNames.lookup(model) ?: SocNames.lookup(board) ?: SocNames.lookup(hardware)
                ?: SocNames.lookup(cpuinfoHardware) ?: cpuinfoHardware
            val cores = (0 until Runtime.getRuntime().availableProcessors().coerceAtLeast(possibleCpus())).map { i ->
                val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
                readKhz("$base/cpuinfo_min_freq") to readKhz("$base/cpuinfo_max_freq")
            }
            val clusters = cores.groupBy { it.second }.toSortedMap(compareBy { it ?: 0.0 })
                .map { (max, list) -> Triple(list.size, list.first().first, max) }
            val governor = runCatching {
                File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim()
            }.getOrNull()
            return Soc(name, vendor, model, hardware, Build.SUPPORTED_ABIS.toList(), clusters, governor)
        }

        private fun possibleCpus(): Int = runCatching {
            File("/sys/devices/system/cpu/possible").readText().trim().split(",").last().split("-").last().toInt() + 1
        }.getOrDefault(0)

        private fun readKhz(path: String): Double? = runCatching {
            File(path).readText().trim().toLong().takeIf { it >= 100_000 }?.div(1000.0)
        }.getOrNull()

        /** GPU name from a throwaway offscreen GL context. */
        private fun gpu(context: Context): Gpu {
            var renderer: String? = null
            var vendor: String? = null
            runCatching {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                EGL14.eglInitialize(display, version, 0, version, 1)
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
                val ctx = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(display, surface, surface, ctx)
                renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                vendor = GLES20.glGetString(GLES20.GL_VENDOR)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
            val am = context.getSystemService(ActivityManager::class.java)
            val gles = am.deviceConfigurationInfo.reqGlEsVersion.let { "${it shr 16}.${it and 0xffff}" }
            val vulkan = context.packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
                ?.let { "${it shr 22}.${(it shr 12) and 0x3ff}" }
            return Gpu(renderer, vendor, gles, vulkan)
        }

        private fun display(context: Context): DisplayInfo? {
            val dm = context.getSystemService(DisplayManager::class.java)
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val mode = d.mode
            val metrics = context.resources.displayMetrics
            val w = maxOf(mode.physicalWidth, mode.physicalHeight)
            val h = minOf(mode.physicalWidth, mode.physicalHeight)
            val inches = if (metrics.xdpi > 0 && metrics.ydpi > 0) {
                hypot(mode.physicalWidth / metrics.xdpi.toDouble(), mode.physicalHeight / metrics.ydpi.toDouble())
                    .takeIf { it in 2.0..20.0 }
            } else null
            @Suppress("DEPRECATION")
            val hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "HDR"
                }
            }?.distinct() ?: emptyList()
            return DisplayInfo(
                widthPx = w,
                heightPx = h,
                dpi = metrics.densityDpi,
                inches = inches,
                refreshNow = d.refreshRate,
                refreshRates = d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted(),
                hdr = hdr,
                wideColor = d.isWideColorGamut,
            )
        }

        private fun cameras(context: Context): List<Camera> {
            val cm = context.getSystemService(CameraManager::class.java)
            return cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                Camera(
                    id = id,
                    facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> Facing.FRONT
                        CameraCharacteristics.LENS_FACING_BACK -> Facing.BACK
                        else -> Facing.EXTERNAL
                    },
                    megapixels = size?.let { it.width.toDouble() * it.height / 1_000_000 },
                    focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                    aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                    ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON } == true,
                    flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                )
            }
        }

        private fun sensors(context: Context): List<SensorInfo> {
            val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
            return sm.getSensorList(Sensor.TYPE_ALL).map {
                SensorInfo(it.name, it.vendor, it.stringType.substringAfterLast('.').replace('_', ' '), it.power)
            }.distinctBy { it.name + it.type }
        }

        private val WIDEVINE = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        /** Widevine security level: L1 means DRM runs in hardware (HD streaming), L3 software only. */
        private fun widevine(): String? = runCatching {
            if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE)) return null
            val drm = MediaDrm(WIDEVINE)
            val level = drm.getPropertyString("securityLevel")
            drm.close()
            level
        }.getOrNull()

        private fun decoders(): List<Pair<String, Boolean>> {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            fun hw(mime: String): Boolean? {
                val candidates = list.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                if (candidates.isEmpty()) return null
                return candidates.any { if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else !it.name.startsWith("OMX.google") && !it.name.startsWith("c2.android") }
            }
            return listOf(
                "AV1" to "video/av01", "HEVC (H.265)" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9",
                "H.264" to "video/avc", "Dolby Vision" to "video/dolby-vision",
            ).mapNotNull { (name, mime) -> hw(mime)?.let { name to it } }
        }

        private fun features(context: Context): List<Pair<Feature, Boolean>> {
            val pm = context.packageManager
            fun has(f: String) = pm.hasSystemFeature(f)
            return listOf(
                Feature.NFC to has(PackageManager.FEATURE_NFC),
                Feature.FINGERPRINT to has(PackageManager.FEATURE_FINGERPRINT),
                Feature.FACE to has(PackageManager.FEATURE_FACE),
                Feature.ESIM to has(PackageManager.FEATURE_TELEPHONY_EUICC),
                Feature.USB_HOST to has(PackageManager.FEATURE_USB_HOST),
                Feature.INFRARED to has(PackageManager.FEATURE_CONSUMER_IR),
                Feature.BLUETOOTH_LE to has(PackageManager.FEATURE_BLUETOOTH_LE),
                Feature.WIFI_AWARE to has(PackageManager.FEATURE_WIFI_AWARE),
                Feature.UWB to has("android.hardware.uwb"),
            )
        }

        private fun volumes(context: Context): List<Volume> {
            val sm = context.getSystemService(StorageManager::class.java)
            return sm.storageVolumes.mapNotNull { v ->
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else null
                if (dir == null || v.isPrimary) return@mapNotNull null
                Volume(v.getDescription(context), dir.totalSpace, dir.usableSpace, v.isRemovable)
            }
        }
    }
}

/** Marketing names for common chip model numbers; the raw number is shown when unknown. */
object SocNames {
    private val names = mapOf(
        // Qualcomm
        "sm8750" to "Snapdragon 8 Elite", "sm8650" to "Snapdragon 8 Gen 3", "sm8635" to "Snapdragon 8s Gen 3",
        "sm8550" to "Snapdragon 8 Gen 2", "sm8475" to "Snapdragon 8+ Gen 1", "sm8450" to "Snapdragon 8 Gen 1",
        "sm8350" to "Snapdragon 888", "sm8250" to "Snapdragon 865", "kona" to "Snapdragon 865",
        "sm8150" to "Snapdragon 855", "msmnile" to "Snapdragon 855", "sdm845" to "Snapdragon 845",
        "sm7675" to "Snapdragon 7+ Gen 3", "sm7550" to "Snapdragon 7 Gen 3", "sm7475" to "Snapdragon 7+ Gen 2",
        "sm7450" to "Snapdragon 7 Gen 1", "sm7325" to "Snapdragon 778G", "sm7250" to "Snapdragon 765G",
        "sm7150" to "Snapdragon 730", "sm6450" to "Snapdragon 6 Gen 1", "sm6375" to "Snapdragon 695",
        "sm6225" to "Snapdragon 680", "sm6115" to "Snapdragon 662", "sm4350" to "Snapdragon 480",
        "sm4450" to "Snapdragon 4 Gen 2", "lahaina" to "Snapdragon 888", "taro" to "Snapdragon 8 Gen 1",
        "kalama" to "Snapdragon 8 Gen 2", "pineapple" to "Snapdragon 8 Gen 3", "sun" to "Snapdragon 8 Elite",
        // Google Tensor
        "gs101" to "Google Tensor", "gs201" to "Google Tensor G2", "zuma" to "Google Tensor G3",
        "zumapro" to "Google Tensor G4", "laguna" to "Google Tensor G5",
        // MediaTek
        "mt6991" to "Dimensity 9400", "mt6989" to "Dimensity 9300", "mt6985" to "Dimensity 9200",
        "mt6983" to "Dimensity 9000", "mt6897" to "Dimensity 8300", "mt6896" to "Dimensity 8200",
        "mt6895" to "Dimensity 8100", "mt6893" to "Dimensity 1200", "mt6877" to "Dimensity 900",
        "mt6878" to "Dimensity 7300", "mt6886" to "Dimensity 7200", "mt6833" to "Dimensity 700",
        "mt6789" to "Helio G99", "mt6785" to "Helio G90T", "mt6769" to "Helio G85",
        // Samsung Exynos
        "s5e9945" to "Exynos 2400", "s5e9925" to "Exynos 2200", "exynos2100" to "Exynos 2100",
        "exynos990" to "Exynos 990", "s5e8835" to "Exynos 1380", "s5e8825" to "Exynos 1280",
        "s5e8845" to "Exynos 1480",
    )

    fun lookup(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val key = raw.lowercase().trim()
        return names[key] ?: names.entries.firstOrNull { key.startsWith(it.key) && it.key.length >= 5 }?.value
    }
}
