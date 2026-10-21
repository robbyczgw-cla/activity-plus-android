package xyz.activityplus.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryPassport
import xyz.activityplus.android.core.BatteryState
import xyz.activityplus.android.core.CapacityLevel
import xyz.activityplus.android.core.ChargingPolicy
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.PartStatus
import xyz.activityplus.android.data.BatteryPassportReader
import xyz.activityplus.android.data.ChargingStore
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.Metric
import java.time.ZoneId

/**
 * Everything Android knows about the battery itself, below the health card. A row shows only when its
 * value is known. Without the read grant the card says what more it would show and opens Pro.
 */
@Composable
fun BatteryPassportCard(b: BatteryState, onPro: () -> Unit) {
    val context = LocalContext.current
    // Reloads when the user comes back from Pro or system settings, where the grant is given.
    val passport = rememberLoaded(rememberResumes()) {
        BatteryPassport.from(
            BatteryPassportReader.read(context), b.designMah, ChargingStore.get(context).capacity(),
            System.currentTimeMillis(), ZoneId.systemDefault(),
        )
    } ?: return
    BatteryPassportContent(passport, onPro)
}

@Composable
fun BatteryPassportContent(passport: BatteryPassport, onPro: () -> Unit) {
    val context = LocalContext.current
    val rows = listOfNotNull(
        passport.madeMillis?.let {
            R.string.passport_made to DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY)
        },
        passport.monthsInUse?.let { months ->
            R.string.passport_in_use to if (months < 1) {
                stringResource(R.string.passport_under_month)
            } else {
                pluralStringResource(R.plurals.passport_months, months, months)
            }
        },
        passport.cyclesPerMonth?.let { R.string.passport_cycles_month to Format.number(it, 1) },
        passport.policy?.let { R.string.passport_policy to stringResource(policyText(it)) },
        passport.part?.let { R.string.passport_part to stringResource(partText(it)) },
        passport.sohPercent?.let { R.string.passport_soh to Format.percent(it / 100.0) },
        passport.capacityLevel?.let { R.string.passport_level to stringResource(levelText(it)) },
    )
    Card {
        CardHeader(stringResource(R.string.passport_title), Metric.Energy, Icons.Outlined.Badge)
        Spacer(Modifier.height(8.dp))
        // With the grant in place an empty card is the phone's doing; without it the hint below says what is missing.
        if (rows.isEmpty() && passport.statsGranted) Note(stringResource(R.string.es_passport_none))
        rows.forEach { (label, value) -> StatLine(stringResource(label), value) }
        passport.verdict?.let {
            Spacer(Modifier.height(6.dp))
            Text(verdictText(it), style = MaterialTheme.typography.bodyMedium)
        }
        if (!passport.statsGranted) {
            TextButton(onClick = onPro, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.passport_more) + " ›")
            }
        }
    }
}

@Composable
private fun verdictText(v: BatteryPassport.Verdict): String = when (v) {
    is BatteryPassport.Verdict.Agree -> stringResource(R.string.passport_agree, Format.percent(v.percent / 100.0))
    is BatteryPassport.Verdict.Differ -> stringResource(
        if (v.settling) R.string.passport_differ_settling else R.string.passport_differ,
        Format.percent(v.system / 100.0), Format.percent(v.measured / 100.0),
    )
}

private fun policyText(p: ChargingPolicy) = when (p) {
    ChargingPolicy.STANDARD -> R.string.passport_policy_standard
    ChargingPolicy.LIMITED -> R.string.passport_policy_limited
    ChargingPolicy.ADAPTIVE -> R.string.passport_policy_adaptive
    ChargingPolicy.LONG_LIFE -> R.string.passport_policy_long_life
}

private fun partText(p: PartStatus) = when (p) {
    PartStatus.ORIGINAL -> R.string.passport_part_original
    PartStatus.REPLACED -> R.string.passport_part_replaced
}

private fun levelText(l: CapacityLevel) = when (l) {
    CapacityLevel.CRITICAL -> R.string.passport_level_critical
    CapacityLevel.LOW -> R.string.passport_level_low
    CapacityLevel.NORMAL -> R.string.passport_level_normal
    CapacityLevel.HIGH -> R.string.passport_level_high
    CapacityLevel.FULL -> R.string.passport_level_full
}
