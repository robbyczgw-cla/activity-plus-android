package xyz.activityplus.android.ui

import android.content.Context
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.core.Diagnosis.Kind
import xyz.activityplus.android.core.Format

/** Turns a [Diagnosis.Finding] into a headline, an explanation and a button label. */
object FindingText {
    data class Text(val title: String, val body: String, val action: String?)

    fun of(c: Context, f: Diagnosis.Finding, fahrenheit: Boolean): Text {
        val v = f.values
        fun bytes(key: String) = Format.bytes((v[key] ?: 0.0).toLong()).toString()
        fun dur(key: String) = Format.duration((v[key] ?: 0.0).toLong())
        val app = f.app ?: ""
        val (title, body) = when (f.kind) {
            Kind.STORAGE_FULL -> c.getString(R.string.f_storage_title) to
                c.getString(R.string.f_storage_body, bytes("free"), bytes("total"))
            Kind.LOW_MEMORY -> c.getString(R.string.f_memory_title) to
                (c.getString(R.string.f_memory_body, bytes("available")) +
                    if (f.app != null) " " + c.getString(R.string.f_on_screen, app) else "")
            Kind.HOT -> c.getString(R.string.f_hot_title) to
                (c.getString(R.string.f_hot_body, Format.temperature(v["temp"] ?: 0.0, fahrenheit).toString()) +
                    if (f.app != null) " " + c.getString(R.string.f_hot_app, app) else "")
            Kind.POWER_SAVE -> c.getString(R.string.f_saver_title) to c.getString(R.string.f_saver_body)
            Kind.TOP_DRAIN_APP -> c.getString(R.string.f_drain_title, app) to
                c.getString(
                    R.string.f_drain_body, Format.energy(v["mwh"] ?: 0.0).toString(), dur("seconds"),
                    Format.percent(v["share"] ?: 0.0),
                )
            Kind.BACKGROUND_SERVICE -> c.getString(R.string.f_service_title, app) to
                c.getString(R.string.f_service_body, dur("seconds"))
            Kind.BACKGROUND_DATA -> c.getString(R.string.f_data_title, app) to
                c.getString(R.string.f_data_body, bytes("bytes"))
            Kind.SCREEN_OFF_DRAIN -> c.getString(R.string.f_off_title) to
                c.getString(R.string.f_off_body, Format.number(v["percentPerHour"] ?: 0.0, 1) + " %", dur("seconds"))
            Kind.BATTERY_WORN -> c.getString(R.string.f_worn_title) to
                (v["health"]?.let { c.getString(R.string.f_worn_body, Format.percent(it)) } ?: c.getString(R.string.f_worn_body_unknown))
            Kind.LONG_UPTIME -> c.getString(R.string.f_uptime_title, dur("seconds")) to c.getString(R.string.f_uptime_body)
            Kind.SLOW_ANIMATIONS -> c.getString(R.string.f_anim_title) to
                c.getString(R.string.f_anim_body, Format.number(v["scale"] ?: 1.0, 1))
            Kind.UNUSED_APPS -> c.resources.getQuantityString(
                R.plurals.f_unused_title, (v["count"] ?: 0.0).toInt(), (v["count"] ?: 0.0).toInt(),
            ) to c.getString(R.string.f_unused_body, bytes("bytes"), f.apps.joinToString(", "))
        }
        val action = when (f.fix) {
            Diagnosis.Fix.STORAGE_SETTINGS -> c.getString(R.string.fix_storage)
            Diagnosis.Fix.APP_INFO -> c.getString(R.string.fix_app_info, app)
            Diagnosis.Fix.BATTERY_SAVER -> c.getString(R.string.fix_saver)
            Diagnosis.Fix.BATTERY_USAGE -> c.getString(R.string.fix_battery_usage)
            Diagnosis.Fix.DEVELOPER_OPTIONS -> c.getString(R.string.fix_developer)
            Diagnosis.Fix.NONE -> null
        }
        return Text(title, body, action)
    }
}
