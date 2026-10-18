package xyz.activityplus.android.pro

/**
 * Reads `dumpsys thermalservice` (ShellService.THERMAL): the status Android has settled on, the
 * temperature sensors of the thermal HAL and its cooling devices. Pure text parsing, tolerant by
 * design: vendors differ, so unknown lines and sections are ignored and a missing one stays empty.
 */
object ThermalParser {
    data class Sensor(val name: String, val type: Int, val celsius: Double, val status: Int)

    /** [value] is the device's state: 0 means it is not limiting anything right now. */
    data class Cooling(val name: String, val type: Int, val value: Double)

    data class Report(val status: Int?, val sensors: List<Sensor>, val cooling: List<Cooling>) {
        /** Cooling devices that act right now (CPU/GPU limits, a fan, charge current). */
        val activeCooling get() = cooling.count { it.value > 0 }

        /**
         * The sensor to name as the reason: the one Android rates highest, then the hottest.
         * The battery-current-limit types carry volts, amps or percent, not degrees.
         */
        val hottest: Sensor?
            get() = sensors.filter { it.type !in NOT_DEGREES }
                .maxWithOrNull(compareBy<Sensor> { it.status }.thenBy { it.celsius })
    }

    // android.os.Temperature types that hold something other than degrees (BCL voltage, current, percentage).
    private val NOT_DEGREES = setOf(7, 8, 9)

    private val STATUS = Regex("""^Thermal Status:\s*(-?\d+)""")
    private val TEMPERATURE = Regex("""Temperature\{mValue=([^,]+), mType=(-?\d+), mName=(.*), mStatus=(-?\d+)\}""")
    private val COOLING = Regex("""CoolingDevice\{mValue=([^,]+), mType=(-?\d+), mName=(.*)\}""")

    fun parse(text: String): Report {
        var status: Int? = null
        var section = ""
        val cached = ArrayList<Sensor>()
        val current = ArrayList<Sensor>()
        val cooling = ArrayList<Cooling>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            // Sections start at column 0 and end with a colon; their items are indented.
            if (!line[0].isWhitespace()) {
                STATUS.find(line)?.let { status = it.groupValues[1].toIntOrNull() }
                section = line.trim()
                continue
            }
            when {
                section.startsWith("Cached temperatures") -> sensor(line)?.let { cached += it }
                section.startsWith("Current temperatures") -> sensor(line)?.let { current += it }
                section.startsWith("Current cooling devices") -> COOLING.find(line)?.let { m ->
                    val v = m.groupValues[1].toDoubleOrNull()?.takeIf { !it.isNaN() } ?: return@let
                    cooling += Cooling(m.groupValues[3].trim(), m.groupValues[2].toIntOrNull() ?: -1, v)
                }
            }
        }
        // The HAL's current readings are fresher than the service's cache; the cache is what older Android versions print.
        return Report(status, current.ifEmpty { cached }, cooling)
    }

    private fun sensor(line: String): Sensor? {
        val m = TEMPERATURE.find(line) ?: return null
        val v = m.groupValues[1].toDoubleOrNull()?.takeIf { !it.isNaN() } ?: return null
        return Sensor(m.groupValues[3].trim(), m.groupValues[2].toIntOrNull() ?: -1, v, m.groupValues[4].toIntOrNull() ?: 0)
    }
}
