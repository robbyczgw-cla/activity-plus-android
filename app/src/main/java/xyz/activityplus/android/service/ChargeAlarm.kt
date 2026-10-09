package xyz.activityplus.android.service

import android.content.Context
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ChargeAlarmKind
import xyz.activityplus.android.core.ChargeAlarmLogic
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Settings

/**
 * Charging alarms through the alerts channel: charged to the chosen limit, fully charged, and
 * warm while charging. When they fire is decided by [ChargeAlarmLogic]; this class only words them.
 */
class ChargeAlarm(private val context: Context, private val alerts: AlertEngine) {
    private val logic = ChargeAlarmLogic()

    /** Always fed, so the plug-in state stays right; it only notifies while the Alerts setting is on. */
    fun check(s: Snapshot, settings: Settings) {
        val b = s.battery
        val fired = logic.onSample(
            s.timeMillis, b.plugged, b.status, b.levelFraction, b.temperatureC,
            settings.chargeLimit, settings.chargeFullAlarm, settings.chargeWarmAlarm,
        )
        if (!settings.alerts) return
        // The cooldowns only guard against plug glitches; the logic already fires once per plug-in.
        fired.forEach { kind ->
            when (kind) {
                ChargeAlarmKind.LIMIT -> alerts.notify(
                    "chargelimit", s.timeMillis, 15 * MINUTE,
                    context.getString(R.string.chg_limit_title, Format.number(settings.chargeLimit.toDouble(), 0) + " %"),
                    context.getString(R.string.chg_limit_body),
                )
                ChargeAlarmKind.FULL -> alerts.notify(
                    "chargefull", s.timeMillis, 15 * MINUTE,
                    context.getString(R.string.chg_full_title), context.getString(R.string.chg_full_body),
                )
                ChargeAlarmKind.WARM -> alerts.notify(
                    "chargewarm", s.timeMillis, HOUR,
                    context.getString(R.string.chg_warm_title, Format.temperature(b.temperatureC, settings.fahrenheit).toString()),
                    context.getString(R.string.chg_warm_body),
                )
            }
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
