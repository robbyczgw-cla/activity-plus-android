package xyz.activityplus.android.core

import kotlin.math.max
import kotlin.math.roundToLong

enum class RailCategory { DISPLAY, CPU, GPU, MOBILE_RADIO, WIFI_BT, CAMERA, MEMORY, GNSS, AUDIO, OTHER }

/** One cumulative reading of a power monitor: energy since boot in microwatt-seconds, and when (elapsedRealtime). */
data class RailReading(val energyUws: Long, val timestampMs: Long)

/** Energy of one monitor that fell into the time slot starting at [slotStart] (wall clock). */
data class EnergySlice(val slotStart: Long, val uws: Long, val ms: Long)

data class SlotRow(val slotStart: Long, val key: String, val uws: Long, val ms: Long)

/** The last reading per monitor and the boot it belongs to, so the next one can be turned into a difference. */
data class RailBaseline(val reading: RailReading, val bootMillis: Long)

data class CategoryPower(val category: RailCategory, val mw: Double)

/** One monitor in the details list. [counted] is false for a measured rail whose category a modeled consumer already covers. */
data class RailDetail(val key: String, val name: String, val category: RailCategory, val mw: Double, val counted: Boolean)

data class PowerBreakdown(val categories: List<CategoryPower>, val details: List<RailDetail>) {
    val totalMw get() = categories.sumOf { it.mw }
}

/**
 * Power per component from Android's power monitors (SystemHealthManager, Android 15+). Pure, so the
 * thresholds and the handling of odd counters are tested without a phone.
 *
 * Monitors report cumulative energy. Without ACCESS_FINE_POWER_MONITORS Android refreshes it at most
 * every 20 s and lowers every reading by random noise of up to 10 J; the noise does not add up over a
 * long window (only the two end readings carry it), which is why everything here works on windows of
 * minutes and keeps small negative differences instead of clamping them.
 */
object PowerRails {
    /** Random noise Android subtracts from every coarse reading: up to 10 J. */
    const val COARSE_NOISE_UWS = 10_000_000L
    /** No phone component pair draws this much; a bigger jump is a counter that wrapped or restarted. */
    const val MAX_PLAUSIBLE_MW = 100_000L
    /** Energy is stored in slots of 15 minutes, so "last hour" is within a quarter hour of right. */
    const val SLOT_MS = 900_000L
    const val RETENTION_DAYS = 30L
    /** A longer silence between two readings is not attributed to anything: too stale to spread. */
    const val MAX_GAP_MS = 3 * 86_400_000L
    /** The clock of the boot may drift a little (time sync); more than this is another boot. */
    const val BOOT_TOLERANCE_MS = 120_000L

    /** Shortest window that is still an average: noise of 10 J is 17 mW over 10 minutes. */
    const val MIN_COARSE_WINDOW_MS = 600_000L
    const val MIN_FINE_WINDOW_MS = 60_000L

    /** "Rest of the phone" needs at least this much, and measured parts that add up to at least a tenth of the draw. */
    const val REST_MIN_MW = 50.0
    const val REST_MIN_SHARE = 0.05
    const val REST_MAX_SHARE = 0.9
    /** The window's battery draw is only compared with rails when the phone was on battery nearly all of it. */
    const val MIN_BATTERY_SAMPLES = 10
    const val MAX_OFF_BATTERY_SHARE = 0.05
    /** Live readings older than this many spans are dropped instead of averaged in. */
    private const val STALE_FACTOR = 3

    // --- Names

    private val DISPLAY = setOf("DISPLAY", "DISP", "LCD", "OLED", "PANEL")
    private val GPU = setOf("GPU", "G3D", "GFX", "MALI")
    private val RADIO = setOf("MOBILE", "MODEM", "RFFE", "CELLULAR", "RADIO")
    private val WIFI_BT = setOf("WIFI", "WLAN", "BLUETOOTH", "BT", "WCN")
    private val CAMERA = setOf("CAMERA", "CAM")
    private val GNSS = setOf("GNSS", "GPS")
    private val AUDIO = setOf("AUDIO", "SPEAKER", "CODEC")
    private val MEMORY = setOf("MIF", "DDR", "DRAM", "MEM", "MEMORY")

    /**
     * Names are device specific: modeled consumers are called GPU, MOBILE_RADIO, CPU_CLUSTER, rails
     * "[Rail2]:CPU" in the emulator and S4M_VDD_CPUCL0, VSYS_PWR_DISPLAY, S8S_VDD_G3D on Pixels. The
     * words of the name decide; anything unknown is [RailCategory.OTHER] and stays visible in the details.
     */
    fun category(name: String): RailCategory {
        val words = name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        fun any(set: Set<String>) = words.any { it in set }
        return when {
            any(DISPLAY) -> RailCategory.DISPLAY
            any(GPU) -> RailCategory.GPU
            // CPUCL0, CPUCL1, CPU_CLUSTER: the cluster number is glued to the word on some boards.
            words.any { it.startsWith("CPU") } -> RailCategory.CPU
            any(RADIO) -> RailCategory.MOBILE_RADIO
            any(WIFI_BT) -> RailCategory.WIFI_BT
            any(CAMERA) -> RailCategory.CAMERA
            any(GNSS) -> RailCategory.GNSS
            any(AUDIO) -> RailCategory.AUDIO
            any(MEMORY) -> RailCategory.MEMORY
            else -> RailCategory.OTHER
        }
    }

    // --- Keys: a consumer and a rail may share a name, so the type is part of the key

    fun key(consumer: Boolean, name: String) = (if (consumer) "c:" else "r:") + name

    fun isConsumer(key: String) = key.startsWith("c:")

    fun nameOf(key: String) = key.drop(2).replace(Regex("#\\d+$"), "")

    /** One key per monitor, in order; a name that repeats within its type gets "#2", "#3" so no reading is lost. */
    fun uniqueKeys(monitors: List<Pair<Boolean, String>>): List<String> {
        val seen = HashMap<String, Int>()
        return monitors.map { (consumer, name) ->
            val base = key(consumer, name)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base#$n"
        }
    }

    // --- Differences

    sealed interface Step {
        /** [uws] over [ms]; slightly negative with coarse readings, because of their noise. */
        data class Energy(val uws: Long, val ms: Long) : Step
        /** Same timestamp: Android handed out the cached value again. Nothing happened, keep the old reading. */
        data object Unchanged : Step
        /** Counter went backwards, jumped, or too much time passed: start over from the new reading. */
        data object Reset : Step
    }

    /** [noiseUws] is [COARSE_NOISE_UWS] for coarse readings and 0 for exact ones. */
    fun step(prev: RailReading, cur: RailReading, noiseUws: Long): Step {
        val ms = cur.timestampMs - prev.timestampMs
        if (ms == 0L) return Step.Unchanged
        if (ms < 0 || ms > MAX_GAP_MS) return Step.Reset
        val uws = cur.energyUws - prev.energyUws
        if (uws < -noiseUws || uws > MAX_PLAUSIBLE_MW * ms + noiseUws) return Step.Reset
        return Step.Energy(uws, ms)
    }

    /** Readings of different boots do not belong together even when their numbers look alike. */
    fun sameBoot(a: Long, b: Long) = kotlin.math.abs(a - b) <= BOOT_TOLERANCE_MS

    /** µWs per ms is mW. Never negative: noise can push a quiet monitor's short window below zero. */
    fun averageMw(uws: Long, ms: Long): Double? = if (ms <= 0) null else max(0.0, uws.toDouble() / ms)

    // --- Slots

    fun slotOf(millis: Long) = Math.floorDiv(millis, SLOT_MS) * SLOT_MS

    /**
     * Spreads [uws] over the time up to [endMs] evenly across the slots it touches, so a reading after a long
     * pause fills the nights instead of dumping it into one slot. The parts add up to exactly [uws].
     */
    fun spread(endMs: Long, spanMs: Long, uws: Long): List<EnergySlice> {
        if (spanMs <= 0) return emptyList()
        val startMs = endMs - spanMs
        val out = ArrayList<EnergySlice>()
        var slot = slotOf(startMs)
        val lastSlot = slotOf(endMs - 1)
        var doneMs = 0L
        var doneUws = 0L
        while (slot <= lastSlot) {
            val to = minOf(endMs, slot + SLOT_MS)
            val from = max(startMs, slot)
            doneMs += to - from
            // Cumulative rounding, so the pieces never drift from the total.
            val cumulative = if (doneMs >= spanMs) uws else (uws.toDouble() * doneMs / spanMs).roundToLong()
            out += EnergySlice(slot, cumulative - doneUws, to - from)
            doneUws = cumulative
            slot += SLOT_MS
        }
        return out
    }

    // --- Windows

    /** Average mW per monitor over the rows (one window), by sum of energy over sum of covered time. */
    fun rates(rows: List<SlotRow>): Map<String, Double> {
        val uws = HashMap<String, Long>()
        val ms = HashMap<String, Long>()
        for (r in rows) {
            uws[r.key] = (uws[r.key] ?: 0L) + r.uws
            ms[r.key] = (ms[r.key] ?: 0L) + r.ms
        }
        val out = HashMap<String, Double>()
        for ((k, e) in uws) averageMw(e, ms.getValue(k))?.let { out[k] = it }
        return out
    }

    /** Time the window really covers: the monitor with the most data (they normally all agree). */
    fun coveredMs(rows: List<SlotRow>): Long =
        rows.groupBy { it.key }.values.maxOfOrNull { list -> list.sumOf { it.ms } } ?: 0L

    fun minWindowMs(fine: Boolean) = if (fine) MIN_FINE_WINDOW_MS else MIN_COARSE_WINDOW_MS

    fun enoughData(coveredMs: Long, fine: Boolean) = coveredMs >= minWindowMs(fine)

    // --- Categories

    /**
     * One level only, so nothing is counted twice: in each category the modeled consumers win, and the
     * measured rails of that category count only when there is no consumer for it. (A phone with a CPU
     * consumer but no display consumer still shows its display rail.) Largest first.
     */
    fun breakdown(mwByKey: Map<String, Double>): PowerBreakdown {
        val details = ArrayList<RailDetail>()
        val categories = ArrayList<CategoryPower>()
        for ((category, entries) in mwByKey.entries.groupBy { category(nameOf(it.key)) }) {
            val hasConsumer = entries.any { isConsumer(it.key) }
            var sum = 0.0
            for (e in entries) {
                val counted = isConsumer(e.key) || !hasConsumer
                if (counted) sum += e.value
                details += RailDetail(e.key, nameOf(e.key), category, e.value, counted)
            }
            categories += CategoryPower(category, sum)
        }
        return PowerBreakdown(
            categories.sortedByDescending { it.mw },
            details.sortedWith(compareByDescending<RailDetail> { it.counted }.thenByDescending { it.mw }),
        )
    }

    // --- Comparison with the battery

    /**
     * What the battery gave out over a window, from history rows of (power in mW with the sign of
     * charging positive, charging flag). Null when the window is not clearly "on battery" all along,
     * because then the rails and the battery describe different things (the charger powers the phone).
     */
    fun batteryDrawMw(samples: List<Pair<Double?, Boolean>>): Double? {
        val known = samples.filter { it.first != null }
        if (known.size < MIN_BATTERY_SAMPLES) return null
        val off = known.count { it.second || it.first!! > 0 }
        if (off > known.size * MAX_OFF_BATTERY_SHARE) return null
        return known.filter { !it.second && it.first!! <= 0 }.map { -it.first!! }.average().takeIf { it > 0 }
    }

    /**
     * What the monitors do not see (charger losses, parts without a monitor), as the battery's draw minus
     * what they add up to. Only when positive and plausible: a measured sum above the draw is a mismatch of
     * the two clocks, and a rest of nearly everything means the monitors cover too little to say anything.
     */
    fun restMw(measuredMw: Double, batteryDrawMw: Double?): Double? {
        val draw = batteryDrawMw?.takeIf { it > 0 } ?: return null
        val rest = draw - measuredMw
        if (rest < max(REST_MIN_MW, draw * REST_MIN_SHARE)) return null
        if (rest > draw * REST_MAX_SHARE) return null
        return rest
    }

    // --- Live rates

    /**
     * The last few seconds of readings per monitor, for the "Now" figure with exact readings: the
     * average between the oldest reading that is still within [spanMs] and the newest one.
     */
    class Live(private val spanMs: Long = 10_000L, private val noiseUws: Long = 0L) {
        private val readings = HashMap<String, ArrayDeque<RailReading>>()

        fun add(now: Map<String, RailReading>) {
            for ((key, reading) in now) {
                val list = readings.getOrPut(key) { ArrayDeque() }
                val last = list.lastOrNull()
                if (last != null) {
                    // After a long pause (screen was off) the old readings say nothing about "now".
                    if (reading.timestampMs - last.timestampMs > STALE_FACTOR * spanMs) list.clear()
                    else when (step(last, reading, noiseUws)) {
                        Step.Unchanged -> continue
                        Step.Reset -> list.clear()
                        is Step.Energy -> Unit
                    }
                }
                list += reading
                // Keep one reading at least spanMs old as the start of the average.
                while (list.size >= 3 && reading.timestampMs - list[1].timestampMs >= spanMs) list.removeFirst()
            }
        }

        /** mW per monitor; monitors with fewer than two readings are missing. */
        fun rates(): Map<String, Double> {
            val out = HashMap<String, Double>()
            for ((key, list) in readings) {
                if (list.size < 2) continue
                val s = step(list.first(), list.last(), noiseUws)
                if (s is Step.Energy) averageMw(s.uws, s.ms)?.let { out[key] = it }
            }
            return out
        }

        /** Seconds the figure covers: the longest span among the monitors. */
        fun spanMs(): Long = readings.values.maxOfOrNull { if (it.size < 2) 0L else it.last().timestampMs - it.first().timestampMs } ?: 0L

        fun clear() = readings.clear()
    }
}
