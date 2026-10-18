package xyz.activityplus.android.share

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.BuildConfig
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryHealth
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.ChargeStatus
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.NightCheck.Verdict
import xyz.activityplus.android.core.ShareReport
import xyz.activityplus.android.core.ShareReport.Row
import xyz.activityplus.android.core.ShareReport.Section
import xyz.activityplus.android.core.SystemSampler
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.data.WakeupStore
import xyz.activityplus.android.pro.ProShell
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.screens.appName
import xyz.activityplus.android.ui.screens.loadNightCheck

/**
 * Gathers what the "Share report" button sends. Only what the app already shows on screen: the
 * phone model, versions, battery, last night, today's top apps and free storage. No account,
 * no ids, no location. Each part that has no data (no grant, no night yet) is simply left out.
 */
object ReportBuilder {
    data class Report(val title: String, val subtitle: String, val sections: List<Section>) {
        val text: String get() = ShareReport.text(title, subtitle, sections)
    }

    /** Blocking (store reads); call off the main thread. */
    fun build(context: Context, nowMillis: Long = System.currentTimeMillis()): Report {
        val battery = runCatching { app.monitor.batteryNow() }.getOrNull()
        val storage = app.monitor.latest.value?.storage
            ?: runCatching { SystemSampler(context.applicationContext).sample().storage }.getOrNull()
        return Report(
            title = context.getString(R.string.share_report_title),
            subtitle = DateUtils.formatDateTime(context, nowMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
            sections = listOf(
                device(context),
                battery(context, battery),
                lastNight(context, nowMillis),
                topApps(context, nowMillis),
                wakeups(context, nowMillis),
                Section(
                    context.getString(R.string.card_storage),
                    listOf(
                        Row(
                            context.getString(R.string.storage_free),
                            storage?.let { Format.bytes(it.freeBytes).toString() + " " + context.getString(R.string.storage_of, Format.bytes(it.totalBytes).toString()) },
                        ),
                    ),
                ),
            ),
        )
    }

    private fun device(c: Context) = Section(
        c.getString(R.string.share_sec_device),
        listOf(
            Row(c.getString(R.string.share_row_model), ShareReport.deviceName(Build.MANUFACTURER, Build.MODEL)),
            Row(c.getString(R.string.share_row_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Row(c.getString(R.string.share_row_patch), Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }),
            Row(c.getString(R.string.share_row_version), BuildConfig.VERSION_NAME),
        ),
    )

    private fun battery(c: Context, b: BatteryState?): Section {
        if (b == null) return Section(c.getString(R.string.card_battery), emptyList())
        val na = c.getString(R.string.not_reported)
        val mah = { v: Double? -> v?.let { Format.number(it, 0) + " mAh" } }
        return Section(
            c.getString(R.string.card_battery),
            listOf(
                Row(c.getString(R.string.share_row_level), Format.percent(b.levelFraction) + ", " + c.getString(status(b.status))),
                Row(c.getString(R.string.health_system), c.getString(healthLabel(b))),
                Row(c.getString(R.string.health_measured), mah(b.estimatedFullMah)),
                Row(c.getString(R.string.health_design), mah(b.designMah)),
                Row(c.getString(R.string.health_estimate), b.healthFraction?.let { Format.percent(it) }),
                Row(c.getString(R.string.health_cycles), b.cycleCount?.toString()),
                Row(c.getString(R.string.temp_battery), Format.temperature(b.temperatureC, app.prefs.current.fahrenheit).toString()),
            ).filter { it.value != na },
        )
    }

    private fun status(s: ChargeStatus) = when (s) {
        ChargeStatus.CHARGING -> R.string.status_charging
        ChargeStatus.DISCHARGING -> R.string.status_discharging
        ChargeStatus.NOT_CHARGING -> R.string.status_not_charging
        ChargeStatus.FULL -> R.string.status_full
        ChargeStatus.UNKNOWN -> R.string.status_unknown
    }

    private fun healthLabel(b: BatteryState) = when (b.health) {
        BatteryHealth.GOOD -> R.string.health_good
        BatteryHealth.OVERHEAT -> R.string.health_overheat
        BatteryHealth.COLD -> R.string.health_cold
        BatteryHealth.DEAD,
        BatteryHealth.FAILURE,
        BatteryHealth.OVER_VOLTAGE -> R.string.health_problem
        BatteryHealth.UNKNOWN -> R.string.not_reported
    }

    /** Verdict, sleep and drain of the night the Last night card shows; nothing when there was no night to judge. */
    private fun lastNight(c: Context, now: Long): Section {
        val r = runCatching { loadNightCheck(c, now).result }.getOrNull()
        val m = r?.measured
        if (r == null || m == null || r.verdict == Verdict.NOT_ENOUGH_DATA) return Section(c.getString(R.string.bg_night_title), emptyList())
        return Section(
            c.getString(R.string.bg_night_title),
            listOf(
                Row(c.getString(R.string.share_row_verdict), verdictTitle(c, r)),
                Row(c.getString(R.string.nc_stat_asleep), Format.duration(m.asleepMs / 1000)),
                Row(c.getString(R.string.nc_stat_deep), Format.percent(m.deepShare)),
                Row(c.getString(R.string.nc_stat_drain), Format.number(m.drainPerHour, 1) + " %/h"),
            ),
        )
    }

    /** Same wording as the card's title; the card keeps its own copy private. */
    fun verdictTitle(c: Context, r: NightCheck.Result): String = when (r.verdict) {
        Verdict.SLEPT_WELL -> c.getString(R.string.nc_title_well)
        Verdict.APP_KEPT_AWAKE -> c.getString(R.string.nc_title_app, appName(c, r.suspect!!.pkg, emptyMap()))
        Verdict.SYSTEM_DID_NOT_SLEEP -> c.getString(R.string.nc_title_system)
        Verdict.HIGH_DRAIN_WHILE_ASLEEP -> c.getString(R.string.nc_title_drain)
        Verdict.NOT_ENOUGH_DATA -> c.getString(R.string.nc_title_none)
    }

    /** The five apps that used the most battery today, as the widget footer counts them. */
    private fun topApps(c: Context, now: Long): Section {
        val day = ActivityPlusApp.dayKey(now)
        val top = runCatching { app.history.energy(day, day) }.getOrNull().orEmpty()
            .filter { !it.pkg.startsWith("#") && it.pkg != c.packageName && it.mwh > 0 }
            .take(TOP_APPS)
        return Section(
            c.getString(R.string.share_sec_apps),
            top.map { Row(appName(c, it.pkg, emptyMap()), Format.energy(it.mwh).toString()) },
        )
    }

    /** The three apps that woke the phone most today; needs the read grant, like the wakeup card. */
    private fun wakeups(c: Context, now: Long): Section {
        val title = c.getString(R.string.share_sec_wakeups)
        if (!ProShell.hasBatteryStats(c) || !WakeupStore.exists(c)) return Section(title, emptyList())
        val apps = runCatching { WakeupStore.get(c).day(ActivityPlusApp.dayKey(now)) }.getOrNull().orEmpty()
            .filter { !it.pkg.startsWith("#") }
        return Section(
            title,
            Wakeups.rank(apps, night = false).take(TOP_WAKEUPS).map { a ->
                val n = a.counters.wakeups.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val parts = listOfNotNull(
                    c.resources.getQuantityString(R.plurals.wk_wakeups, n, n).takeIf { n > 0 },
                    c.getString(R.string.pro_awake, Format.duration(a.counters.wakelockMs / 1000)).takeIf { a.counters.wakelockMs >= 30_000 },
                )
                Row(appName(c, a.pkg, emptyMap()), parts.joinToString(" · ").ifEmpty { null })
            },
        )
    }

    private const val TOP_APPS = 5
    private const val TOP_WAKEUPS = 3
}
