package xyz.activityplus.android.core

import kotlin.math.roundToInt

/**
 * What the charging Live Update (Android 16) shows, decided without Android types: the progress bar
 * with a point at the charge limit, the power, when the limit or full is reached, and the short text
 * for the status bar chip. The service words the [Plan] with string resources and posts it.
 */
object ChargeLive {
    enum class Stage { CHARGING, HOLDING, FULL }

    /** What the time estimate is for: the user's charge limit, or 100 %. */
    enum class Target { NONE, LIMIT, FULL }

    /** Everything the notification needs; equal plans look the same, so an equal plan is not posted again. */
    data class Plan(
        val stage: Stage,
        /** Battery too warm; replaces the headline and the chip text. */
        val warm: Boolean,
        val percent: Int,
        /** "62 %" */
        val levelText: String,
        /** "41 °C" */
        val temperatureText: String,
        /** Charging power like "18.2 W"; null when unknown or when nothing flows into the battery. */
        val powerText: String?,
        /** Where the bar's limit point sits (1..99); null without a limit. */
        val limitPoint: Int?,
        val target: Target,
        val targetPercent: Int,
        /** When the target is reached, rounded to the minute; null when the phone cannot tell. */
        val targetMillis: Long?,
        /** Status bar chip: the level, or the temperature while warm. Kept short, the chip has room for about seven characters. */
        val shortText: String,
    )

    class Input(
        val level: Double,
        val status: ChargeStatus,
        /** The charge limit alarm in percent, 0 for off. */
        val limitPercent: Int,
        val powerMw: Double?,
        /** Seconds to 100 % as the system or the current rate gives them. */
        val secondsToFull: Long?,
        val percentPerHour: Double?,
        val tempC: Double,
        /** Was the previous plan warm? The text then stays "warm" a little longer, so it does not flap around the threshold. */
        val wasWarm: Boolean,
        val fahrenheit: Boolean,
        val now: Long,
    )

    /** Below this the estimate is a guess from a trickle; above a day it is not an estimate. */
    private const val MIN_PERCENT_PER_HOUR = 0.5
    private const val MAX_ETA_SECONDS = 20L * 3600
    private const val WARM_HYSTERESIS_C = 2.0

    fun plan(i: Input): Plan {
        val percent = (i.level * 100).roundToInt().coerceIn(0, 100)
        val full = i.status == ChargeStatus.FULL || percent >= 100
        val stage = when {
            full -> Stage.FULL
            i.status == ChargeStatus.CHARGING -> Stage.CHARGING
            else -> Stage.HOLDING
        }
        val warm = i.tempC >= ChargeAlarmLogic.WARM_C || (i.wasWarm && i.tempC >= ChargeAlarmLogic.WARM_C - WARM_HYSTERESIS_C)

        val limit = i.limitPercent.takeIf { it in 1..99 }
        val target = when {
            stage != Stage.CHARGING -> Target.NONE
            limit != null && percent < limit -> Target.LIMIT
            else -> Target.FULL
        }
        val targetPercent = if (target == Target.LIMIT) limit!! else 100
        val seconds = if (target == Target.NONE) null else secondsTo(i, targetPercent)

        val levelText = Format.percent(percent / 100.0)
        val temperatureText = Format.temperature(i.tempC, i.fahrenheit).toString()
        return Plan(
            stage = stage,
            warm = warm,
            percent = percent,
            levelText = levelText,
            temperatureText = temperatureText,
            // Power flowing out of a plugged-in phone is a load, not charging; do not label it so.
            powerText = i.powerMw?.takeIf { it > 0 }?.let { Format.watts(it).toString() },
            limitPoint = limit,
            target = target,
            targetPercent = targetPercent,
            targetMillis = seconds?.let { roundToMinute(i.now + it * 1000) },
            shortText = if (warm) temperatureText else levelText,
        )
    }

    /**
     * Seconds until [targetPercent]. Full uses the estimate for 100 %. A limit below it uses the
     * current rate, which is accurate below ~80 % where phones still charge flat out; the taper
     * above only slows the end, so the share of the full-charge estimate would overshoot.
     */
    internal fun secondsTo(i: Input, targetPercent: Int): Long? {
        val toFull = i.secondsToFull?.takeIf { it in 1..MAX_ETA_SECONDS }
        if (targetPercent >= 100) return toFull
        val missing = targetPercent - i.level * 100
        if (missing <= 0) return 0
        val rate = i.percentPerHour?.takeIf { it >= MIN_PERCENT_PER_HOUR }
        val byRate = rate?.let { (missing / it * 3600).toLong() }
        val byShare = if (toFull != null && i.level < 1.0) (toFull * missing / ((1.0 - i.level) * 100)).toLong() else null
        // Reaching the limit never takes longer than reaching full.
        return (byRate ?: byShare)?.let { if (toFull != null) minOf(it, toFull) else it }?.takeIf { it <= MAX_ETA_SECONDS }
    }

    private fun roundToMinute(millis: Long): Long = (millis + 30_000) / 60_000 * 60_000

    /**
     * A new post is worth it when the plan changed on screen. A new stage goes out at once; the
     * numbers (power and time change with every sample) at most every [MIN_POST_GAP_MILLIS].
     */
    fun shouldPost(last: Plan?, next: Plan, lastPostMillis: Long, now: Long): Boolean = when {
        last == null -> true
        next == last -> false
        next.stage != last.stage || next.warm != last.warm -> true
        else -> now - lastPostMillis >= MIN_POST_GAP_MILLIS
    }

    const val MIN_POST_GAP_MILLIS = 10_000L

    /** A plugged-in phone that stopped charging (full, or held by a limit) keeps the card this long, then it goes. */
    const val IDLE_GRACE_MILLIS = 10 * 60_000L
}

/**
 * Whether the Live Update is up. It follows the charging: gone when unplugged, and gone after
 * [ChargeLive.IDLE_GRACE_MILLIS] without charging, because a phone parked at 100 % on a desk should
 * not keep a promoted notification all night. It returns when charging resumes.
 */
class ChargeLiveGate {
    private var idleSince = NONE

    @Synchronized
    fun onSample(now: Long, plugged: Boolean, charging: Boolean): Boolean {
        if (!plugged || charging) {
            idleSince = NONE
            return plugged
        }
        if (idleSince == NONE) idleSince = now
        return now - idleSince < ChargeLive.IDLE_GRACE_MILLIS
    }

    private companion object {
        const val NONE = -1L
    }
}
