package xyz.activityplus.android.pro

/**
 * Reads the three system reports of the pro mode. Pure text parsing, tested against real
 * output in src/test/resources/dumpsys (Android 15 emulator).
 */
object ProParser {
    /** Per uid since the last full charge, from `dumpsys batterystats -c`. */
    data class UidBattery(val mah: Double, val cpuMs: Long, val wakelockMs: Long)

    data class BatteryReport(
        val byUid: Map<Int, UidBattery>,
        /** uid to package names, from the report itself (it sees packages Activity+ cannot). */
        val packages: Map<Int, List<String>>,
        /** Time on battery since the last charge, in ms. */
        val onBatteryMs: Long?,
        /** Hardware not tied to an app: screen, cell radio, Wi-Fi, idle ... in mAh. */
        val system: Map<String, Double>,
    )

    fun batteryCheckin(text: String): BatteryReport {
        val mah = HashMap<Int, Double>()
        val cpu = HashMap<Int, Long>()
        val wake = HashMap<Int, Long>()
        val packages = HashMap<Int, MutableList<String>>()
        val system = HashMap<String, Double>()
        var onBattery: Long? = null
        for (line in text.lineSequence()) {
            val f = line.split(',')
            if (f.size < 5) continue
            val uid = f[1].toIntOrNull() ?: continue
            when {
                // 9,0,i,uid,<uid>,<package>
                f[2] == "i" && f[3] == "uid" && f.size >= 6 ->
                    f[4].toIntOrNull()?.let { packages.getOrPut(it) { ArrayList() } += f[5] }
                f[2] != "l" -> Unit
                // 9,<uid>,l,pwi,uid,<mAh>,<hidden>,...   and 9,0,l,pwi,<component>,<mAh>,...
                f[3] == "pwi" && f.size >= 6 -> {
                    val v = f[5].toDoubleOrNull() ?: continue
                    if (f[4] == "uid") mah[uid] = (mah[uid] ?: 0.0) + v
                    else if (v > 0) system[f[4]] = (system[f[4]] ?: 0.0) + v
                }
                // 9,<uid>,l,cpu,<user ms>,<system ms>,...
                f[3] == "cpu" && f.size >= 6 && uid != 0 ->
                    cpu[uid] = (cpu[uid] ?: 0) + (f[4].toLongOrNull() ?: 0) + (f[5].toLongOrNull() ?: 0)
                // 9,<uid>,l,wl,<name>,<full ms>,f,...,<partial ms>,p,...  The partial time keeps the CPU awake.
                f[3] == "wl" -> {
                    val p = f.indexOf("p")
                    if (p > 5) wake[uid] = (wake[uid] ?: 0) + (f[p - 1].toLongOrNull() ?: 0)
                }
                // 9,0,l,bt,<start count>,<battery realtime ms>,...
                f[3] == "bt" && f.size >= 6 -> onBattery = f[5].toLongOrNull()
            }
        }
        val uids = mah.keys + cpu.keys + wake.keys
        return BatteryReport(
            byUid = uids.associateWith { UidBattery(mah[it] ?: 0.0, cpu[it] ?: 0, wake[it] ?: 0) },
            packages = packages,
            onBatteryMs = onBattery,
            system = system,
        )
    }

    private val pssLine = Regex("""^\s*([\d,]+)K: (\S+) \(pid (\d+)""")

    /** Process name to PSS in bytes, from the "Total PSS by process" block of `dumpsys meminfo`. */
    fun meminfoPss(text: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        var inBlock = false
        for (line in text.lineSequence()) {
            if (line.startsWith("Total PSS by process")) {
                inBlock = true; continue
            }
            if (!inBlock) continue
            if (line.isBlank()) break
            val m = pssLine.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            out[m.groupValues[2]] = (out[m.groupValues[2]] ?: 0) + kb * 1024
        }
        return out
    }

    private val cpuLine = Regex("""^\s*([\d.]+)% (\d+)/([^:]+):""")

    /** Process name to CPU percent over the last ~minute and a half, from `dumpsys cpuinfo`. */
    fun cpuinfo(text: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (line in text.lineSequence()) {
            if (line.contains("TOTAL:")) break
            val m = cpuLine.find(line) ?: continue
            val pct = m.groupValues[1].toDoubleOrNull() ?: continue
            out[m.groupValues[3]] = (out[m.groupValues[3]] ?: 0.0) + pct
        }
        return out
    }

    /** "com.google.android.gms.persistent" and "com.app:remote" belong to the package before ':'. */
    fun packageOf(process: String): String = process.substringBefore(':')
}
