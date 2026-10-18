package xyz.activityplus.android.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the charging policy setting does to the battery (BatteryManager property 9). */
enum class ChargingPolicy { STANDARD, LIMITED, ADAPTIVE, LONG_LIFE }

/** Whether the battery is the one the phone shipped with (property 12). */
enum class PartStatus { ORIGINAL, REPLACED }

/** Android's own level category, Android 16 and later. */
enum class CapacityLevel { CRITICAL, LOW, NORMAL, HIGH, FULL }

/**
 * Values as the battery reports them. Null means not read: no grant, or the phone does not report it.
 * The reader fills in what it could get; [BatteryPassport.from] decides what is plausible.
 */
data class PassportRaw(
    /** Epoch seconds (or milliseconds on some builds), property 7. */
    val manufactureDate: Long?,
    /** Epoch seconds (or milliseconds), property 8. */
    val firstUseDate: Long?,
    /** Raw charging policy, property 9. */
    val chargingPolicy: Long?,
    /** Android's state of health in percent, property 10. */
    val stateOfHealth: Long?,
    /** Raw part status, property 12. */
    val partStatus: Long?,
    val capacityLevel: CapacityLevel?,
    val cycles: Int?,
    /** Whether the BATTERY_STATS grant is there; without it the rows above are mostly empty. */
    val statsGranted: Boolean,
)

/**
 * The battery's own facts as one card: only the values that survived the plausibility checks are
 * set, so the screen shows exactly the rows that have data.
 */
data class BatteryPassport(
    val madeMillis: Long?,
    val firstUseMillis: Long?,
    /** Whole months from the first use to now; null without a valid first-use date. */
    val monthsInUse: Int?,
    val cyclesPerMonth: Double?,
    val policy: ChargingPolicy?,
    val part: PartStatus?,
    val sohPercent: Int?,
    val capacityLevel: CapacityLevel?,
    val verdict: Verdict?,
    val statsGranted: Boolean,
) {
    /** How Android's state of health compares with the full capacity Activity+ measured. */
    sealed interface Verdict {
        data class Agree(val percent: Int) : Verdict
        data class Differ(val system: Int, val measured: Int, val settling: Boolean) : Verdict
    }

    companion object {
        /** Epoch seconds stay below this until the year 5138, so larger values are already milliseconds. */
        private const val MILLIS_FROM = 100_000_000_000L

        /** Android's figure and the measurement may be this far apart (points) and still say the same. */
        const val AGREE_POINTS = 3

        /** Fewer measurements than this and the measured figure is still moving. */
        const val SETTLED_COUNT = 3

        fun from(
            raw: PassportRaw,
            designMah: Double?,
            estimates: List<CapacityEstimate>,
            nowMillis: Long,
            zone: ZoneId,
        ): BatteryPassport {
            // Dates in the future or before the manufacture cannot be right; drop them rather than show them.
            val made = known(raw.manufactureDate)?.let(::epochMillis)?.takeIf { it <= nowMillis }
            val firstUse = known(raw.firstUseDate)?.let(::epochMillis)
                ?.takeIf { it <= nowMillis && (made == null || it >= made) }
            val months = firstUse?.let { months(it, nowMillis, zone) }
            // Under a month the rate is noise, so no per-month figure then.
            val cyclesPerMonth = if (months != null && months >= 1) {
                raw.cycles?.takeIf { it >= 0 }?.let { it.toDouble() / months }
            } else null
            // Android's percent is plausible from 1 to 110; anything else is an error answer.
            val soh = known(raw.stateOfHealth)?.takeIf { it <= 110 }?.toInt()
            return BatteryPassport(
                madeMillis = made,
                firstUseMillis = firstUse,
                monthsInUse = months,
                cyclesPerMonth = cyclesPerMonth,
                policy = when (raw.chargingPolicy) {
                    1L -> ChargingPolicy.STANDARD
                    2L -> ChargingPolicy.LIMITED
                    3L -> ChargingPolicy.ADAPTIVE
                    4L -> ChargingPolicy.LONG_LIFE
                    else -> null
                },
                // 0 means the phone does not say, so there is no row for it.
                part = when (raw.partStatus) {
                    1L -> PartStatus.ORIGINAL
                    2L -> PartStatus.REPLACED
                    else -> null
                },
                sohPercent = soh,
                capacityLevel = raw.capacityLevel,
                verdict = verdictOf(soh, designMah, estimates),
                statsGranted = raw.statsGranted,
            )
        }

        /**
         * Android's percent against the measured full capacity as a share of the rated one. Null when either
         * side is missing: the rated capacity is only a power-profile number and may not exist.
         */
        internal fun verdictOf(system: Int?, designMah: Double?, estimates: List<CapacityEstimate>): Verdict? {
            // The same median the health card's trend uses, so the two never disagree about the number.
            val measuredMah = ChargeHealth.trend(estimates)?.latestMah ?: estimates.singleOrNull()?.mah
            if (system == null || designMah == null || designMah <= 0 || measuredMah == null) return null
            val measured = (measuredMah / designMah * 100).roundToInt()
            return if (abs(system - measured) <= AGREE_POINTS) {
                Verdict.Agree(((system + measured) / 2.0).roundToInt())
            } else {
                Verdict.Differ(system, measured, settling = estimates.size < SETTLED_COUNT)
            }
        }

        /** Null for the framework's error answers: zero and the negative MIN_VALUE sentinels. */
        internal fun known(v: Long?): Long? = v?.takeIf { it > 0 }

        internal fun epochMillis(v: Long): Long = if (v > MILLIS_FROM) v else v * 1000

        /** Calendar months, so 1 March to 1 October is 7 months whatever the days are. */
        internal fun months(fromMillis: Long, toMillis: Long, zone: ZoneId): Int {
            val from = YearMonth.from(Instant.ofEpochMilli(fromMillis).atZone(zone))
            val to = YearMonth.from(Instant.ofEpochMilli(toMillis).atZone(zone))
            return ChronoUnit.MONTHS.between(from, to).toInt().coerceAtLeast(0)
        }
    }
}
