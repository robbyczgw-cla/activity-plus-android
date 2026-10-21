package xyz.activityplus.android.core

/**
 * Structure for the Battery screen and the Overview: what matters now ("Today at a glance"), which
 * group a card belongs to, which cards have nothing to say and fold into one line, and which the user
 * hid. Nothing here computes a new number; it only picks from what the cards already work out.
 * No Android types, so the rules are tested on the JVM.
 */
object Glance {
    enum class Group { NOW, LAST_NIGHT, APPS, CHARGING_HEALTH, PLANNED }

    /**
     * The cards of the Battery screen. [key] is what Prefs stores for a hidden card, so it never
     * changes; the order of the entries is the order inside a group when everything has something to say.
     */
    enum class CardId(val key: String, val group: Group, val hideable: Boolean = true, val unpluggedOnly: Boolean = false) {
        LIVE("live", Group.NOW, hideable = false),
        FORECAST("forecast", Group.NOW, unpluggedOnly = true),
        SESSION("session", Group.NOW),
        NOW_DRAWING("nowDrawing", Group.NOW, unpluggedOnly = true),
        POWER_RAIL("powerRails", Group.NOW),
        NIGHT("night", Group.LAST_NIGHT),
        WAKEUP("wakeups", Group.LAST_NIGHT),
        DRAIN("drain", Group.APPS),
        CHARGER_TEST("chargerTest", Group.CHARGING_HEALTH),
        HEALTH("health", Group.CHARGING_HEALTH),
        PASSPORT("passport", Group.CHARGING_HEALTH),
        HABITS("habits", Group.CHARGING_HEALTH),
        SESSIONS("sessions", Group.CHARGING_HEALTH),
        SCHEDULE("schedule", Group.PLANNED),
        BACKGROUND("background", Group.PLANNED),
        PRO("pro", Group.PLANNED);

        companion object {
            /** Unknown keys (a card of a later version) are ignored. */
            fun hidden(keys: Set<String>): Set<CardId> = entries.filter { it.hideable && it.key in keys }.toSet()
        }
    }

    // ---- Which cards have something to say ----

    /** Why a card folds into a line. Each has its own wording; none says "empty". */
    enum class Quiet { NOTHING_YET, NEEDS_DAYS, NEEDS_ACCESS, PLUG_IN, UNSUPPORTED }

    /**
     * [quiet] null: the card is open. Otherwise it shows as one line with the reason; [expandable] says
     * whether a tap can show more (the card's own text, or its hint how to get the data) or whether the
     * line already is everything there is.
     */
    data class Status(val quiet: Quiet? = null, val expandable: Boolean = true) {
        val open get() = quiet == null
    }

    private val OPEN = Status()

    /** What the Battery screen knows about its data. Null means "still loading": the card waits instead of flashing a reason. */
    data class Inputs(
        val plugged: Boolean,
        val charging: Boolean,
        /** The forecast card has a pattern or a current-rate estimate to show. */
        val forecast: Boolean?,
        /** The session since unplugging has lasted long enough to show. */
        val sessionSinceUnplug: Boolean,
        /** An app is on screen and the battery is drawing. */
        val nowDrawing: Boolean?,
        /** The phone reports power per component. */
        val powerRails: Boolean?,
        val night: NightCheck.Verdict?,
        /** BATTERY_STATS is granted, which the wakeup counters need. */
        val wakeupAccess: Boolean,
        /** Energy per app was measured today. */
        val drainApps: Boolean?,
        /** The user saved at least one charger test. */
        val chargerTests: Boolean?,
        /** Past charges or discharges that lasted. */
        val pastSessions: Boolean?,
        /** The computer grant or Shizuku, which the alarm and job reading needs. */
        val scheduleAccess: Boolean,
        /** The background measurement runs (setting and computer grant). */
        val backgroundOn: Boolean,
        /** Today's background rows are loaded; only asked when [backgroundOn]. */
        val backgroundLoaded: Boolean,
    )

    /** Only the cards that should appear now; a missing card is not applicable or still loading. */
    fun statuses(i: Inputs): Map<CardId, Status> {
        val out = LinkedHashMap<CardId, Status>()
        fun put(card: CardId, status: Status?) {
            if (status != null) out[card] = status
        }
        put(CardId.LIVE, OPEN)
        if (!i.plugged) put(CardId.FORECAST, i.forecast?.let { if (it) OPEN else Status(Quiet.NEEDS_DAYS, expandable = false) })
        put(CardId.SESSION, if (i.plugged || i.sessionSinceUnplug) OPEN else Status(Quiet.NOTHING_YET, expandable = false))
        if (!i.plugged) put(CardId.NOW_DRAWING, i.nowDrawing?.let { if (it) OPEN else Status(Quiet.NOTHING_YET, expandable = false) })
        put(CardId.POWER_RAIL, i.powerRails?.let { if (it) OPEN else Status(Quiet.UNSUPPORTED, expandable = false) })
        put(CardId.NIGHT, i.night?.let { if (it == NightCheck.Verdict.NOT_ENOUGH_DATA) Status(Quiet.NOTHING_YET) else OPEN })
        put(CardId.WAKEUP, if (i.wakeupAccess) OPEN else Status(Quiet.NEEDS_ACCESS))
        put(CardId.DRAIN, i.drainApps?.let { if (it) OPEN else Status(Quiet.NOTHING_YET) })
        // Charging is the moment the test can run; unplugged it is a line unless there is a ranking to read.
        put(CardId.CHARGER_TEST, if (i.charging) OPEN else i.chargerTests?.let { if (it) OPEN else Status(Quiet.PLUG_IN, expandable = false) })
        put(CardId.HEALTH, OPEN)
        put(CardId.PASSPORT, OPEN)
        put(CardId.HABITS, OPEN)
        put(CardId.SESSIONS, i.pastSessions?.let { if (it) OPEN else Status(Quiet.NOTHING_YET, expandable = false) })
        put(CardId.SCHEDULE, if (i.scheduleAccess) OPEN else Status(Quiet.NEEDS_ACCESS))
        put(CardId.BACKGROUND, if (!i.backgroundOn) Status(Quiet.NEEDS_ACCESS) else if (i.backgroundLoaded) OPEN else null)
        put(CardId.PRO, OPEN)
        return out
    }

    // ---- Order and grouping ----

    data class Slot(val card: CardId, val status: Status)

    /** One group: its cards, open ones first, and the cards the user hid (listed as "Hidden: X · Show"). */
    data class Section(val group: Group, val slots: List<Slot>, val hidden: List<CardId>)

    /**
     * The groups in the order of what needs attention: the live card's group always leads, a charging
     * phone puts charging and health next, and a group whose cards all fold sinks below the others.
     * Inside a group, cards with something to say come before folded ones; ties keep the declared order.
     */
    fun layout(plugged: Boolean, status: Map<CardId, Status>, hidden: Set<String>): List<Section> {
        val gone = CardId.hidden(hidden)
        val sections = Group.entries.mapNotNull { group ->
            val cards = CardId.entries.filter { it.group == group }
            val slots = cards.filter { it in status && it !in gone }.map { Slot(it, status.getValue(it)) }
                .sortedBy { if (it.status.open) 0 else 1 }
            // A hidden card that does not apply right now (forecast while charging) would only be noise.
            val hiddenHere = cards.filter { it in gone && !(plugged && it.unpluggedOnly) }
            if (slots.isEmpty() && hiddenHere.isEmpty()) null else Section(group, slots, hiddenHere)
        }
        fun rank(s: Section): Int {
            val base = when (s.group) {
                Group.NOW -> 0
                Group.CHARGING_HEALTH -> if (plugged) 1 else 4
                Group.LAST_NIGHT -> 2
                Group.APPS -> 3
                Group.PLANNED -> 5
            }
            val allFolded = s.slots.none { it.status.open }
            return if (s.group == Group.NOW) base else base + if (allFolded) 10 else 0
        }
        return sections.sortedBy(::rank)
    }

    /**
     * Where each card's item sits in the list [layout] describes, for scrolling to it. Per section: one
     * title, one item per slot, one footer when cards are hidden. [first] is the index of the first title.
     * A hidden card points at its group's footer.
     */
    fun itemIndexes(sections: List<Section>, first: Int): Map<CardId, Int> {
        val out = HashMap<CardId, Int>()
        var i = first
        sections.forEach { s ->
            i++ // the title
            s.slots.forEach { out[it.card] = i++ }
            if (s.hidden.isNotEmpty()) {
                val footer = i++
                s.hidden.forEach { out[it] = footer }
            }
        }
        return out
    }

    // ---- Today at a glance ----

    enum class Destination { BATTERY, APPS, HARDWARE, DIAGNOSIS, STORAGE }

    /** Where a line leads: a card on the Battery screen, or another page. */
    sealed interface Target {
        data class OnScreen(val card: CardId) : Target
        data class Page(val page: Destination) : Target
    }

    data class LevelFact(
        val fraction: Double,
        val plugged: Boolean,
        val charging: Boolean,
        val timeToFullSeconds: Long?,
        /** The forecast has learned enough days to speak for the user's pattern. */
        val patternReady: Boolean,
        /** When the battery runs out: by the pattern when it is ready, else at the current rate. */
        val emptyAtMillis: Long?,
    ) {
        enum class Outlook { FULL_IN, PLUGGED, EMPTY_PATTERN, EMPTY_RATE, BEYOND, NONE }

        val outlook: Outlook
            get() = when {
                plugged -> if (charging && timeToFullSeconds != null) Outlook.FULL_IN else Outlook.PLUGGED
                emptyAtMillis != null -> if (patternReady) Outlook.EMPTY_PATTERN else Outlook.EMPTY_RATE
                // Ready, and still nothing: it lasts past the forecast's horizon.
                patternReady -> Outlook.BEYOND
                else -> Outlook.NONE
            }
    }

    data class NightFact(val verdict: NightCheck.Verdict, val suspectPkg: String?)

    data class TopAppFact(val pkg: String, val mwh: Double)

    /** [findings] are the Diagnosis findings of the moment, [unusualPkg] the app whose wakeups are far above its usual day. */
    data class Facts(
        val level: LevelFact? = null,
        val night: NightFact? = null,
        val topApp: TopAppFact? = null,
        val findings: List<Diagnosis.Finding> = emptyList(),
        val unusualPkg: String? = null,
        /** Before noon, when last night is still the news. */
        val morning: Boolean = false,
    )

    sealed class Line {
        abstract val target: Target
        /** Equal keys are the same news, so the Overview can skip what its verdict already says. */
        abstract val key: String
        abstract val score: Int

        data class Level(val fact: LevelFact, override val target: Target) : Line() {
            override val key get() = "level"
            override val score get() = PINNED
        }

        data class Night(val fact: NightFact, val morning: Boolean) : Line() {
            override val target: Target get() = Target.OnScreen(CardId.NIGHT)
            override val key get() = "night"
            override val score get() = when {
                fact.verdict == NightCheck.Verdict.SLEPT_WELL -> if (morning) 50 else 30
                else -> if (morning) 85 else 65
            }
            val good get() = fact.verdict == NightCheck.Verdict.SLEPT_WELL
        }

        data class TopApp(val fact: TopAppFact) : Line() {
            override val target: Target get() = Target.OnScreen(CardId.DRAIN)
            override val key get() = "top:${fact.pkg}"
            override val score get() = 40
        }

        data class Heads(val finding: Diagnosis.Finding, override val target: Target) : Line() {
            override val key get() = findingKey(finding)
            override val score get() = (if (finding.severity == Diagnosis.Severity.BAD) 100 else 80) - priority(finding.kind)
        }

        data class Unusual(val pkg: String) : Line() {
            override val target: Target get() = Target.OnScreen(CardId.WAKEUP)
            override val key get() = "unusual:$pkg"
            override val score get() = 75
        }
    }

    private const val PINNED = 1000

    /** The lines on the Battery screen's card: the level, one heads-up, last night, the busiest app. */
    const val GLANCE_LINES = 4

    /** At most this many items on the Overview. */
    const val WORTH_A_LOOK = 2

    /** The key a finding has as a [Line], for [worthALook]'s skip set. */
    fun findingKey(f: Diagnosis.Finding) = "finding:${f.kind}:${f.pkg.orEmpty()}"

    // Which finding shows first when severities tie: what drains or heats the battery before housekeeping.
    private val PRIORITY = listOf(
        Diagnosis.Kind.HOT, Diagnosis.Kind.WAKELOCK_LIMIT, Diagnosis.Kind.THROTTLING_APP, Diagnosis.Kind.SCREEN_OFF_DRAIN,
        Diagnosis.Kind.BACKGROUND_SERVICE, Diagnosis.Kind.DOZE_ALARM, Diagnosis.Kind.SINCE_UPDATE, Diagnosis.Kind.BACKGROUND_LOCATION,
        Diagnosis.Kind.BACKGROUND_DATA, Diagnosis.Kind.BATTERY_WORN, Diagnosis.Kind.STORAGE_FULL, Diagnosis.Kind.LOW_MEMORY,
        Diagnosis.Kind.LONG_UPTIME, Diagnosis.Kind.SLOW_ANIMATIONS, Diagnosis.Kind.UNUSED_APPS,
    )

    private fun priority(kind: Diagnosis.Kind) = PRIORITY.indexOf(kind).let { if (it < 0) PRIORITY.size else it }

    /** The battery related kinds, with the card that shows more; null for kinds that are not about the battery. */
    private fun batteryTarget(kind: Diagnosis.Kind): Target? = when (kind) {
        Diagnosis.Kind.HOT -> Target.OnScreen(CardId.LIVE)
        Diagnosis.Kind.WAKELOCK_LIMIT -> Target.OnScreen(CardId.WAKEUP)
        Diagnosis.Kind.SCREEN_OFF_DRAIN -> Target.OnScreen(CardId.DRAIN)
        Diagnosis.Kind.BATTERY_WORN -> Target.OnScreen(CardId.HEALTH)
        Diagnosis.Kind.DOZE_ALARM -> Target.OnScreen(CardId.SCHEDULE)
        // The heat per app lives with the hardware, the rest with the app's own page.
        Diagnosis.Kind.THROTTLING_APP -> Target.Page(Destination.HARDWARE)
        Diagnosis.Kind.BACKGROUND_SERVICE, Diagnosis.Kind.SINCE_UPDATE, Diagnosis.Kind.BACKGROUND_DATA -> Target.Page(Destination.APPS)
        Diagnosis.Kind.BACKGROUND_LOCATION -> Target.Page(Destination.DIAGNOSIS)
        else -> null
    }

    /** Everything else a finding can be about; the Overview shows these too. */
    private fun otherTarget(kind: Diagnosis.Kind): Target? = when (kind) {
        Diagnosis.Kind.STORAGE_FULL -> Target.Page(Destination.STORAGE)
        Diagnosis.Kind.LOW_MEMORY, Diagnosis.Kind.LONG_UPTIME, Diagnosis.Kind.SLOW_ANIMATIONS, Diagnosis.Kind.UNUSED_APPS ->
            Target.Page(Destination.DIAGNOSIS)
        else -> null
    }

    /** Warnings and worse only: an info finding is not news. */
    private fun heads(findings: List<Diagnosis.Finding>, battery: Boolean): List<Line.Heads> =
        findings.filter { it.severity >= Diagnosis.Severity.WARN }.mapNotNull { f ->
            (batteryTarget(f.kind) ?: if (battery) null else otherTarget(f.kind))?.let { Line.Heads(f, it) }
        }

    /** A target whose card the user hid is not offered: the user said they do not want it. */
    private fun shown(target: Target, hidden: Set<CardId>) = target !is Target.OnScreen || target.card !in hidden

    /**
     * The card at the top of the Battery screen. The level comes first; then, by relevance, the one
     * most important heads-up, last night (louder in the morning and when it went wrong) and the app
     * that used the most today. A line is left out when its card is hidden or there is nothing to say.
     */
    fun glance(f: Facts, hidden: Set<CardId> = emptySet(), max: Int = GLANCE_LINES): List<Line> {
        val level = f.level?.let { Line.Level(it, levelTarget(it, hidden)) }
        val warnings = heads(f.findings, battery = true) + listOfNotNull(f.unusualPkg?.let { Line.Unusual(it) })
        val rest = buildList {
            warnings.filter { shown(it.target, hidden) }.maxByOrNull { it.score }?.let { add(it) }
            f.night?.takeIf { it.verdict != NightCheck.Verdict.NOT_ENOUGH_DATA }
                ?.let { Line.Night(it, f.morning) }?.takeIf { shown(it.target, hidden) }?.let { add(it) }
            f.topApp?.takeIf { it.mwh >= MIN_TOP_APP_MWH }?.let { Line.TopApp(it) }?.takeIf { shown(it.target, hidden) }?.let { add(it) }
        }.sortedByDescending { it.score }
        return (listOfNotNull(level) + rest).take(max)
    }

    /** An app has to have used this much to be named; a few mWh is the phone idling. */
    const val MIN_TOP_APP_MWH = 20.0

    private fun levelTarget(l: LevelFact, hidden: Set<CardId>): Target {
        val card = when {
            l.plugged -> CardId.SESSION
            l.outlook != LevelFact.Outlook.NONE -> CardId.FORECAST
            else -> CardId.LIVE
        }
        return Target.OnScreen(if (card in hidden) CardId.LIVE else card)
    }

    /**
     * The Overview's "worth a look": at most [max] items, the most relevant first, from the same
     * material as [glance] but only what calls for attention (a night that went well does not).
     * [skip] holds the keys the verdict above already says.
     */
    fun worthALook(f: Facts, skip: Set<String> = emptySet(), max: Int = WORTH_A_LOOK): List<Line> {
        val candidates = heads(f.findings, battery = false) +
            listOfNotNull(f.unusualPkg?.let { Line.Unusual(it) }) +
            listOfNotNull(f.night?.takeIf { it.verdict != NightCheck.Verdict.NOT_ENOUGH_DATA }?.let { Line.Night(it, f.morning) }?.takeIf { !it.good })
        return candidates.filter { it.key !in skip }.sortedByDescending { it.score }.take(max)
    }
}
