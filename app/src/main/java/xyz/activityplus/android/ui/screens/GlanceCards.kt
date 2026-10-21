package xyz.activityplus.android.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.Tab
import xyz.activityplus.android.core.Diagnosis
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.Glance
import xyz.activityplus.android.core.Glance.CardId
import xyz.activityplus.android.core.Glance.Group
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.ui.AppIcon
import xyz.activityplus.android.ui.FindingText
import xyz.activityplus.android.ui.app
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.SectionTitle
import xyz.activityplus.android.ui.rememberLoaded
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric
import java.time.LocalTime

/*
 * The structure of the Battery screen and the Overview: "Today at a glance", the groups with their
 * titles, the one-line rows for cards that have nothing to say, and the "Hidden: X · Show" rows.
 * What is picked and in which order is core/Glance; this file only draws it. It works on plain
 * Screen { item { } } items, so it lays out the same in one column and in two.
 */

/**
 * Set by a screen whose list can scroll to an item index; null where it cannot (then a tap on a
 * glance line only unfolds the card). Index 0 is the screen's own title item.
 */
val LocalScreenScroll = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** Items the screen puts before the first title: the screen's own header. */
const val SCREEN_HEADER_ITEMS = 1

// ---- Texts ----

@Composable
fun CardId.title(): String = stringResource(
    when (this) {
        CardId.LIVE -> R.string.card_battery
        CardId.FORECAST -> R.string.fc_title
        CardId.SESSION -> R.string.glance_card_session
        CardId.NOW_DRAWING -> R.string.glance_card_now_drawing
        CardId.POWER_RAIL -> R.string.power_title
        CardId.NIGHT -> R.string.bg_night_title
        CardId.WAKEUP -> R.string.wk_title
        CardId.DRAIN -> R.string.drain_today
        CardId.CHARGER_TEST -> R.string.chg_test_title
        CardId.HEALTH -> R.string.health_title
        CardId.PASSPORT -> R.string.passport_title
        CardId.HABITS -> R.string.habits_title
        CardId.SESSIONS -> R.string.sessions_title
        CardId.SCHEDULE -> R.string.sc_title
        CardId.BACKGROUND -> R.string.bg_today_title
        CardId.PRO -> R.string.pro_entry_title
    }
)

@Composable
fun Group.title(): String = stringResource(
    when (this) {
        Group.NOW -> R.string.glance_group_now
        Group.LAST_NIGHT -> R.string.glance_group_night
        Group.APPS -> R.string.glance_group_apps
        Group.CHARGING_HEALTH -> R.string.glance_group_health
        Group.PLANNED -> R.string.glance_group_planned
    }
)

@Composable
private fun Glance.Quiet.text(): String = stringResource(
    when (this) {
        Glance.Quiet.NOTHING_YET -> R.string.glance_quiet_nothing
        Glance.Quiet.NEEDS_DAYS -> R.string.glance_quiet_days
        Glance.Quiet.NEEDS_ACCESS -> R.string.glance_quiet_access
        Glance.Quiet.PLUG_IN -> R.string.glance_quiet_plug
        Glance.Quiet.UNSUPPORTED -> R.string.glance_quiet_unsupported
    }
)

/** The page a line leads to when it is not a card of the Battery screen. */
fun Glance.Destination.tab(): Tab = when (this) {
    Glance.Destination.BATTERY -> Tab.BATTERY
    Glance.Destination.APPS -> Tab.APPS
    Glance.Destination.HARDWARE -> Tab.HARDWARE
    Glance.Destination.DIAGNOSIS -> Tab.DIAGNOSIS
    Glance.Destination.STORAGE -> Tab.STORAGE
}

/** From the Overview a card of the Battery screen is reached through that screen. */
fun Glance.Target.tab(): Tab = when (this) {
    is Glance.Target.OnScreen -> Tab.BATTERY
    is Glance.Target.Page -> page.tab()
}

// ---- Data both screens need ----

/**
 * The diagnosis findings of the moment. The loaders run once a minute ([minute] is the key); the rules
 * themselves are re-run every ten seconds, not on every sample: they read settings and the VPN list.
 */
@Composable
fun rememberFindings(s: Snapshot?, data: AppsData?, minute: Int): List<Diagnosis.Finding> {
    val context = LocalContext.current
    val wakeLimit = rememberLoaded(minute) { loadWakeLimit(context) }.orEmpty()
    val sinceUpdate = rememberLoaded(minute) { loadSinceUpdate(context) }.orEmpty()
    val throttlers = rememberLoaded(minute) { loadThrottlers(context, emptyMap()) }.orEmpty()
    val backgroundLocation = rememberLoaded(minute) { loadBackgroundLocation(context) }.orEmpty()
    if (s == null || data == null) return emptyList()
    return remember(s.timeMillis / 10_000, data, wakeLimit, sinceUpdate, throttlers, backgroundLocation) {
        diagnose(context, s, data, wakeLimit, sinceUpdate = sinceUpdate, throttlers = throttlers, backgroundLocation = backgroundLocation)
    }
}

/** The app that used the most today, if it is an app of the user's (not Activity+, not the screen-off row). */
fun topApp(context: Context, data: AppsData?): Glance.TopAppFact? =
    data?.energy?.filterKeys { !it.startsWith("#") && it != context.packageName }
        ?.maxByOrNull { it.value.first }?.let { Glance.TopAppFact(it.key, it.value.first) }

fun nightFact(report: NightReport?): Glance.NightFact? =
    report?.result?.let { Glance.NightFact(it.verdict, it.suspect?.pkg) }

fun isMorning(): Boolean = LocalTime.now().hour < NightCheck.NOON_HOUR

// ---- Today at a glance ----

@Composable
fun GlanceCard(lines: List<Glance.Line>, fahrenheit: Boolean, onTarget: (Glance.Target) -> Unit) {
    Card {
        CardHeader(stringResource(R.string.glance_title), Metric.Energy, Icons.Outlined.Today)
        lines.forEach { GlanceRow(it, fahrenheit, onTarget) }
        // A fresh install has the battery level and little else; say what will come instead of showing a bare card.
        if (lines.size < 2) Note(stringResource(R.string.glance_learning), Modifier.padding(top = 6.dp))
    }
}

/** Overview: up to two items, or one quiet line saying there is nothing, never a missing card. */
@Composable
fun WorthALookCard(lines: List<Glance.Line>, fahrenheit: Boolean, onTarget: (Glance.Target) -> Unit) {
    Card {
        CardHeader(stringResource(R.string.glance_look_title), Metric.Warn, Icons.Outlined.Visibility)
        if (lines.isEmpty()) Note(stringResource(R.string.glance_look_none), Modifier.padding(top = 6.dp))
        lines.forEach { GlanceRow(it, fahrenheit, onTarget) }
    }
}

@Composable
private fun GlanceRow(line: Glance.Line, fahrenheit: Boolean, onTarget: (Glance.Target) -> Unit) {
    val context = LocalContext.current
    val label: String
    val main: String
    val leading: @Composable () -> Unit
    when (line) {
        is Glance.Line.Level -> {
            label = stringResource(R.string.glance_label_battery)
            main = outlookText(context, line.fact)
            leading = { Text(Format.percent(line.fact.fraction), style = MaterialTheme.typography.titleMedium, color = Metric.Energy) }
        }
        is Glance.Line.Night -> {
            label = stringResource(R.string.bg_night_title)
            main = nightText(context, line.fact)
            leading = { Icon(Icons.Outlined.Bedtime, null, tint = if (line.good) Metric.Energy else Metric.Warn) }
        }
        is Glance.Line.TopApp -> {
            label = stringResource(R.string.glance_label_top)
            main = appName(context, line.fact.pkg, emptyMap()) + " · " + Format.energy(line.fact.mwh)
            leading = { AppIcon(line.fact.pkg, 28.dp) }
        }
        is Glance.Line.Heads -> {
            label = stringResource(R.string.glance_label_heads)
            main = FindingText.of(context, line.finding, fahrenheit).title
            leading = { Icon(Icons.Outlined.WarningAmber, null, tint = Metric.Warn) }
        }
        is Glance.Line.Unusual -> {
            label = stringResource(R.string.glance_label_heads)
            main = stringResource(R.string.glance_unusual, appName(context, line.pkg, emptyMap()))
            leading = { Icon(Icons.Outlined.WarningAmber, null, tint = Metric.Warn) }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { onTarget(line.target) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(52.dp), verticalAlignment = Alignment.CenterVertically) { leading() }
        Column(Modifier.weight(1f)) {
            Note(label)
            Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
    }
}

/** Time of day, with the weekday when it is not today ("Tue 02:10"). */
private fun clock(context: Context, millis: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(context, millis, flags)
}

private fun outlookText(context: Context, f: Glance.LevelFact): String = when (f.outlook) {
    Glance.LevelFact.Outlook.FULL_IN -> context.getString(R.string.glance_full_in, Format.duration(f.timeToFullSeconds ?: 0))
    Glance.LevelFact.Outlook.PLUGGED -> context.getString(R.string.glance_plugged)
    Glance.LevelFact.Outlook.EMPTY_PATTERN -> context.getString(R.string.fc_until_pattern, clock(context, f.emptyAtMillis ?: 0))
    Glance.LevelFact.Outlook.EMPTY_RATE -> context.getString(R.string.fc_until_current, clock(context, f.emptyAtMillis ?: 0))
    Glance.LevelFact.Outlook.BEYOND -> context.getString(R.string.fc_beyond)
    Glance.LevelFact.Outlook.NONE -> context.getString(R.string.glance_no_estimate)
}

private fun nightText(context: Context, f: Glance.NightFact): String = when (f.verdict) {
    NightCheck.Verdict.SLEPT_WELL -> context.getString(R.string.nc_title_well)
    NightCheck.Verdict.APP_KEPT_AWAKE -> context.getString(R.string.nc_title_app, f.suspectPkg?.let { appName(context, it, emptyMap()) } ?: "")
    NightCheck.Verdict.SYSTEM_DID_NOT_SLEEP -> context.getString(R.string.nc_title_system)
    NightCheck.Verdict.HIGH_DRAIN_WHILE_ASLEEP -> context.getString(R.string.nc_title_drain)
    NightCheck.Verdict.NOT_ENOUGH_DATA -> context.getString(R.string.nc_title_none)
}

// ---- Groups, folded rows, hidden rows ----

/**
 * The cards of [sections] as items: a title per group, a card or a folded row per slot, and a
 * "Hidden: X · Show" footer. [card] draws one card. [expanded] holds the keys of the folded cards the
 * user opened.
 */
fun ScreenScope.glanceSections(
    sections: List<Glance.Section>,
    expanded: List<String>,
    onToggle: (CardId) -> Unit,
    card: @Composable (CardId) -> Unit,
) {
    sections.forEach { section ->
        item(key = "title-${section.group}", fullWidth = true) { SectionTitle(section.group.title()) }
        section.slots.forEach { slot ->
            item(key = slot.card.key) {
                val quiet = slot.status.quiet
                if (quiet == null) {
                    card(slot.card)
                } else {
                    val open = slot.status.expandable && slot.card.key in expanded
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietRow(slot.card, quiet, slot.status.expandable, open) { onToggle(slot.card) }
                        // The card itself, whatever it has to say; its own text is the "nothing yet" or the way to the data.
                        if (open) card(slot.card)
                    }
                }
            }
        }
        if (section.hidden.isNotEmpty()) {
            item(key = "hidden-${section.group}") {
                Column {
                    section.hidden.forEach { HiddenRow(it) }
                }
            }
        }
    }
}

/** A card with nothing to say: its title and why, on one line. Tapping unfolds it where it can show more. */
@Composable
private fun QuietRow(card: CardId, quiet: Glance.Quiet, expandable: Boolean, open: Boolean, onClick: () -> Unit) {
    val muted = LocalSurfaces.current.muted
    val title = card.title()
    val reason = quiet.text()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalSurfaces.current.card)
            .let { if (expandable) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                withStyle(SpanStyle(color = muted)) { append("  ·  $reason") }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (expandable) {
            Spacer(Modifier.width(8.dp))
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                stringResource(if (open) R.string.glance_collapse else R.string.glance_expand), tint = muted,
            )
        }
    }
}

/** "Hidden: Health · Show": a card the user switched off stays listed where it belongs. */
@Composable
private fun HiddenRow(card: CardId) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.glance_hidden, card.title()),
            style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { app.prefs.update { it.copy(hiddenCards = it.hiddenCards - card.key) } }) {
            Text(stringResource(R.string.glance_show))
        }
    }
}

/** Behind the folded "Background today" row when the measurement is off: why, and the way to the grant. */
@Composable
fun BackgroundHintCard(onPro: () -> Unit) {
    Card(onClick = onPro) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, null, tint = Metric.Energy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bg_today_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.glance_background_hint))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}

// ---- Settings ----

/** Settings → Cards: one switch per card of the Battery screen that can be hidden. */
@Composable
fun CardVisibilityCard(settings: Settings) {
    val hidden = settings.hiddenCards
    Card {
        Note(stringResource(R.string.glance_set_hint))
        Group.entries.forEach { group ->
            val cards = CardId.entries.filter { it.group == group && it.hideable }
            if (cards.isEmpty()) return@forEach
            Spacer(Modifier.padding(top = 8.dp))
            Text(group.title(), style = MaterialTheme.typography.titleSmall)
            cards.forEach { card ->
                Toggle(card.title(), null, card.key !in hidden) { on ->
                    app.prefs.update { it.copy(hiddenCards = if (on) it.hiddenCards - card.key else it.hiddenCards + card.key) }
                }
            }
        }
        TextButton(
            enabled = Glance.CardId.hidden(hidden).isNotEmpty(),
            onClick = { app.prefs.update { it.copy(hiddenCards = emptySet()) } },
        ) { Text(stringResource(R.string.glance_set_show_all)) }
    }
}

// ---- Overview ----

/** A hidden page on the Overview: title, one line of what is there, and a chevron. */
@Composable
fun EntryCard(icon: ImageVector, tint: Color, title: String, caption: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Note(caption)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSurfaces.current.muted)
        }
    }
}
