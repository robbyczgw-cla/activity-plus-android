package xyz.activityplus.android.pro

import org.json.JSONArray
import org.json.JSONObject

/** One "Measure now": per-app battery since the last charge, memory and CPU right now. */
data class ProReport(
    val timeMillis: Long,
    val onBatteryMs: Long?,
    val apps: List<ProApp>,
    /** Screen, cell radio, Wi-Fi ... that Android does not charge to an app, in mAh. */
    val hardware: Map<String, Double>,
) {
    val totalMah get() = apps.sumOf { it.mah } + hardware.values.sum()

    fun forPackage(pkg: String) = apps.find { it.pkg == pkg }

    fun toJson(): String = JSONObject().apply {
        put("t", timeMillis)
        onBatteryMs?.let { put("b", it) }
        put("hw", JSONObject(hardware))
        put("apps", JSONArray(apps.map { a ->
            JSONObject().apply {
                put("p", a.pkg); put("m", a.mah); put("c", a.cpuMs); put("w", a.wakelockMs)
                a.pssBytes?.let { put("r", it) }
                a.cpuNow?.let { put("n", it) }
            }
        }))
    }.toString()

    companion object {
        fun fromJson(s: String): ProReport {
            val o = JSONObject(s)
            val hw = o.getJSONObject("hw")
            val apps = o.getJSONArray("apps")
            return ProReport(
                timeMillis = o.getLong("t"),
                onBatteryMs = if (o.has("b")) o.getLong("b") else null,
                apps = (0 until apps.length()).map { i ->
                    val a = apps.getJSONObject(i)
                    ProApp(
                        pkg = a.getString("p"), mah = a.getDouble("m"), cpuMs = a.getLong("c"), wakelockMs = a.getLong("w"),
                        pssBytes = if (a.has("r")) a.getLong("r") else null,
                        cpuNow = if (a.has("n")) a.getDouble("n") else null,
                    )
                },
                hardware = hw.keys().asSequence().associateWith { hw.getDouble(it) },
            )
        }

        /**
         * Joins the three reports per package. Battery comes per uid, memory and CPU per process;
         * processes go to the package whose name they start with ("com.google.android.gms.persistent").
         * Shared system uids and processes without a package become [SYSTEM].
         */
        fun build(
            time: Long,
            battery: ProParser.BatteryReport?,
            pss: Map<String, Long>?,
            cpu: Map<String, Double>?,
        ): ProReport {
            val rows = HashMap<String, ProApp>()
            fun row(pkg: String) = rows.getOrPut(pkg) { ProApp(pkg, 0.0, 0, 0, null, null) }

            val known = battery?.packages?.values?.flatten()?.toSet().orEmpty()
            battery?.byUid?.forEach { (uid, u) ->
                val pkgs = battery.packages[uid].orEmpty()
                val pkg = if (uid >= 10_000 && pkgs.size == 1) pkgs[0] else SYSTEM
                val r = row(pkg)
                rows[pkg] = r.copy(mah = r.mah + u.mah, cpuMs = r.cpuMs + u.cpuMs, wakelockMs = r.wakelockMs + u.wakelockMs)
            }
            fun packageFor(process: String): String {
                val base = ProParser.packageOf(process)
                if (base in known) return base
                return known.filter { base.startsWith("$it.") }.maxByOrNull { it.length }
                    ?: if (base.contains('.')) base else SYSTEM
            }
            pss?.forEach { (process, bytes) ->
                val pkg = packageFor(process)
                val r = row(pkg)
                rows[pkg] = r.copy(pssBytes = (r.pssBytes ?: 0) + bytes)
            }
            cpu?.forEach { (process, pct) ->
                val pkg = packageFor(process)
                val r = row(pkg)
                rows[pkg] = r.copy(cpuNow = (r.cpuNow ?: 0.0) + pct)
            }
            return ProReport(time, battery?.onBatteryMs, rows.values.toList(), battery?.system.orEmpty())
        }

        const val SYSTEM = "#system"
    }
}

data class ProApp(
    val pkg: String,
    /** Android's own estimate since the last full charge, foreground and background. */
    val mah: Double,
    val cpuMs: Long,
    /** Time the app held the phone awake with partial wakelocks. */
    val wakelockMs: Long,
    val pssBytes: Long?,
    /** CPU percent of one core over the last ~1.5 minutes. */
    val cpuNow: Double?,
)
