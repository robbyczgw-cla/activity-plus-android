package xyz.activityplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.AppCost
import xyz.activityplus.android.core.BatteryPassport
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.ChargingHabits
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.HabitSample
import xyz.activityplus.android.core.MeasuredApp
import xyz.activityplus.android.core.PlugSpan
import xyz.activityplus.android.core.UsageCost
import xyz.activityplus.android.data.BatteryPassportReader
import xyz.activityplus.android.data.ChargingStore
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.Metric
import java.time.ZoneId
import kotlin.math.roundToInt

/** What the habits card shows. [summary] is null with less than three days of samples; [ageing] always has a state. */
data class HabitsData(
    val summary: ChargingHabits.Summary?,
    val advice: List<ChargingHabits.Advice>,
    val ageing: ChargingHabits.Ageing,
)

/**
 * How the phone was charged over the last 30 days, the advice that applies, and the ageing forecast
 * from the full-capacity trend. Sits below the battery passport, since both describe the battery.
 */
@Composable
fun ChargingHabitsCard(b: BatteryState, fahrenheit: Boolean) {
    val context = LocalContext.current
    // Reloads when the user comes back from system settings, where the charging limit is changed.
    val data = rememberLoaded(rememberResumes(), b.designMah) { loadHabits(context, b.designMah) } ?: return
    Card {
        CardHeader(stringResource(R.string.habits_title), Metric.Energy, Icons.Outlined.Insights)
        Spacer(Modifier.height(8.dp))
        val h = data.summary
        if (h == null) {
            Note(stringResource(R.string.habits_need_days))
        } else {
            StatLine(stringResource(R.string.habits_full), perDay(h.fullSecondsPerDay))
            StatLine(
                stringResource(R.string.habits_at_temp, Format.temperature(ChargingHabits.WARM_C, fahrenheit).toString()),
                perDay(h.warmSecondsPerDay),
            )
            StatLine(
                stringResource(R.string.habits_at_temp, Format.temperature(ChargingHabits.HOT_C, fahrenheit).toString()),
                perDay(h.hotSecondsPerDay),
            )
            StatLine(
                stringResource(R.string.habits_deep),
                pluralStringResource(R.plurals.habits_deep_count, h.deepDischarges, h.deepDischarges, h.observedDays.roundToInt()),
            )
            if (h.typicalStartPercent != null && h.typicalEndPercent != null) {
                StatLine(
                    stringResource(R.string.habits_typical),
                    stringResource(
                        R.string.habits_range,
                        Format.percent(h.typicalStartPercent / 100.0), Format.percent(h.typicalEndPercent / 100.0),
                    ),
                )
            }
            if (data.advice.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                data.advice.forEach { advice ->
                    Text(adviceText(advice, h, fahrenheit), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                }
                // The charge limit lives in the system battery settings; only the full-charge advice points there.
                if (ChargingHabits.Advice.FULL_WHILE_PLUGGED in data.advice) {
                    FilledTonalButton(onClick = { Actions.open(context, Diagnosis.Fix.BATTERY_USAGE) }) {
                        Text(stringResource(R.string.habits_open_battery))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.habits_age_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        AgeingLines(data.ageing)
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.habits_note))
    }
}

@Composable
private fun AgeingLines(a: ChargingHabits.Ageing) {
    when (a) {
        is ChargingHabits.Ageing.NotYet -> Note(
            stringResource(
                R.string.habits_age_more, a.measurements, ChargingHabits.MIN_MEASUREMENTS,
                (ChargingHabits.MIN_SPAN_DAYS / 7).roundToInt(),
            ),
        )
        ChargingHabits.Ageing.NoClearLoss -> Text(stringResource(R.string.habits_age_noclear), style = MaterialTheme.typography.bodyMedium)
        is ChargingHabits.Ageing.Reached -> Text(
            stringResource(R.string.habits_age_reached, Format.percent(a.percentNow / 100), referencePercent()),
            style = MaterialTheme.typography.bodyMedium,
        )
        is ChargingHabits.Ageing.Forecast -> {
            val months = a.months.roundToInt()
            Text(
                stringResource(
                    R.string.habits_age_forecast, referencePercent(),
                    pluralStringResource(R.plurals.habits_months, months, months),
                ),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            )
            Note(stringResource(R.string.habits_age_range, a.monthsEarliest.roundToInt(), a.monthsLatest.roundToInt()))
            Note(
                stringResource(
                    if (a.rated) R.string.habits_age_now_rated else R.string.habits_age_now_first,
                    Format.percent(a.percentNow / 100),
                ),
            )
        }
    }
    Note(stringResource(R.string.habits_age_note))
}

@Composable
private fun adviceText(advice: ChargingHabits.Advice, h: ChargingHabits.Summary, fahrenheit: Boolean): String = when (advice) {
    ChargingHabits.Advice.HOT_CHARGING -> stringResource(
        R.string.habits_advice_hot, Format.temperature(ChargingHabits.HOT_C, fahrenheit).toString(),
        Format.duration(h.hotSecondsPerDay.toLong()),
    )
    ChargingHabits.Advice.WARM_CHARGING -> stringResource(
        R.string.habits_advice_warm, Format.temperature(ChargingHabits.WARM_C, fahrenheit).toString(),
        Format.duration(h.warmSecondsPerDay.toLong()),
    )
    ChargingHabits.Advice.FULL_WHILE_PLUGGED -> stringResource(
        R.string.habits_advice_full, Format.duration(h.fullSecondsPerDay.toLong()),
        Format.percent(ChargingHabits.FULL_LEVEL), referencePercent(),
    )
    ChargingHabits.Advice.DEEP_DISCHARGES -> stringResource(
        R.string.habits_advice_deep, Format.percent(ChargingHabits.LOW_LEVEL), h.deepDischarges,
        h.observedDays.roundToInt(), Format.percent(PLUG_IN_AT),
    )
}

/** The "80 %" the ageing forecast and the advice refer to. */
@Composable
private fun referencePercent(): String = Format.percent(ChargingHabits.REFERENCE_PERCENT / 100)

/** Where the deep-discharge advice says to plug in. */
private const val PLUG_IN_AT = 0.20

/** "6 h 00 min a day", or "None" for less than a minute. */
@Composable
private fun perDay(seconds: Double): String =
    if (seconds < 60) stringResource(R.string.habits_none)
    else stringResource(R.string.habits_per_day, Format.duration(seconds.toLong()))

/** Runs on IO. Null when there is neither a habit to show nor a capacity measurement. */
private fun loadHabits(context: Context, designMah: Double?): HabitsData? {
    val now = System.currentTimeMillis()
    val from = now - ChargingHabits.WINDOW_DAYS * DAY
    val samples = app.history.samples(from).map { HabitSample(it.ts, it.level, it.tempC, it.charging) }
    // A session that ended before the window cannot hold any sample in it.
    val spans = app.history.sessions(limit = 1000, charging = true)
        .filter { it.endMillis >= from }
        .map { PlugSpan(it.startMillis, it.endMillis, it.startLevel, it.endLevel) }
    val summary = ChargingHabits.summarize(samples, spans)
    val estimates = ChargingStore.get(context).capacity()
    if (summary == null && estimates.isEmpty()) return null
    // The policy decides which advice still applies: a phone that already limits its charge needs none.
    val policy = BatteryPassport.from(
        BatteryPassportReader.read(context), designMah, estimates, now, ZoneId.systemDefault(),
    ).policy
    return HabitsData(
        summary = summary,
        advice = summary?.let { ChargingHabits.advice(it, policy) }.orEmpty(),
        ageing = ChargingHabits.ageing(estimates, designMah),
    )
}

/**
 * Battery per hour on screen, from the energy DrainTracker measured while each app was on screen over the
 * last seven days. The battery capacity comes from the live monitor; without it only watts are shown.
 */
@Composable
fun CostPerHourCard() {
    val context = LocalContext.current
    val measured = rememberLoaded(rememberResumes()) { loadMeasuredApps() } ?: return
    val battery = app.monitor.latest.collectAsState().value?.battery
    val batteryMwh = battery?.let { b -> b.capacityMah?.let { it * b.voltageV } }
    val rows = UsageCost.rank(measured, batteryMwh)
    if (rows.isEmpty()) return
    Card {
        CardHeader(stringResource(R.string.cost_title), Metric.Energy, Icons.Outlined.Timer, caption = stringResource(R.string.cost_caption))
        Spacer(Modifier.height(6.dp))
        rows.forEach { CostRow(it) }
        Spacer(Modifier.height(6.dp))
        Note(stringResource(R.string.cost_note))
        Note(stringResource(R.string.cost_min_note, (UsageCost.MIN_SECONDS / 60).roundToInt()))
    }
}

@Composable
private fun CostRow(c: AppCost) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(c.pkg, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(appName(context, c.pkg, emptyMap()), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Note(stringResource(R.string.cost_row_note, Format.duration(c.seconds.toLong()), Format.watts(c.averageMw).toString()))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            c.percentPerHour?.let { Format.number(it, 1) + " %/h" } ?: Format.watts(c.averageMw).toString(),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Runs on IO: the energy per app for the seven days ending today. */
private fun loadMeasuredApps(): List<MeasuredApp> {
    val now = System.currentTimeMillis()
    return app.history.energy(ActivityPlusApp.dayKey(now - 6 * DAY), ActivityPlusApp.dayKey(now))
        .map { MeasuredApp(it.pkg, it.mwh, it.seconds) }
}
