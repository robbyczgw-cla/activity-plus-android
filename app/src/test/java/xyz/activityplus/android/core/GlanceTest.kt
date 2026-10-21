package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Glance.CardId
import xyz.activityplus.android.core.Glance.Group
import xyz.activityplus.android.core.Glance.Quiet
import xyz.activityplus.android.core.Glance.Target

class GlanceTest {
    // ---- cards that have nothing to say ----

    /** A phone a day after install: no grants, little history. */
    private val fresh = Glance.Inputs(
        plugged = false, charging = false, forecast = false, sessionSinceUnplug = false, nowDrawing = false,
        powerRails = false, night = NightCheck.Verdict.NOT_ENOUGH_DATA, wakeupAccess = false, drainApps = false,
        chargerTests = false, pastSessions = false, scheduleAccess = false, backgroundOn = false, backgroundLoaded = false,
    )

    @Test fun aFreshInstallFoldsEveryCardThatHasNothingYetAndSaysWhy() {
        val s = Glance.statuses(fresh)
        assertTrue(s.getValue(CardId.LIVE).open)
        assertEquals(Quiet.NEEDS_DAYS, s.getValue(CardId.FORECAST).quiet)
        assertEquals(Quiet.NOTHING_YET, s.getValue(CardId.SESSION).quiet)
        assertEquals(Quiet.UNSUPPORTED, s.getValue(CardId.POWER_RAIL).quiet)
        assertEquals(Quiet.NOTHING_YET, s.getValue(CardId.NIGHT).quiet)
        assertEquals(Quiet.NEEDS_ACCESS, s.getValue(CardId.WAKEUP).quiet)
        assertEquals(Quiet.NOTHING_YET, s.getValue(CardId.DRAIN).quiet)
        assertEquals(Quiet.PLUG_IN, s.getValue(CardId.CHARGER_TEST).quiet)
        assertEquals(Quiet.NOTHING_YET, s.getValue(CardId.SESSIONS).quiet)
        assertEquals(Quiet.NEEDS_ACCESS, s.getValue(CardId.SCHEDULE).quiet)
        assertEquals(Quiet.NEEDS_ACCESS, s.getValue(CardId.BACKGROUND).quiet)
    }

    @Test fun everyCardAppearsInSomeStateSoNothingVanishesWithoutAReason() {
        // Unplugged and loaded: all 16 cards have a status, open or folded with a reason.
        assertEquals(CardId.entries.toSet(), Glance.statuses(fresh).keys)
    }

    @Test fun cardsThatDoNotApplyWhilePluggedAreLeftOut() {
        val s = Glance.statuses(fresh.copy(plugged = true, charging = true))
        assertFalse(CardId.FORECAST in s)
        assertFalse(CardId.NOW_DRAWING in s)
        assertTrue(s.getValue(CardId.SESSION).open)
        assertTrue(s.getValue(CardId.CHARGER_TEST).open)
    }

    @Test fun aCardWaitsWhileItsDataLoadsInsteadOfFlashingAReason() {
        val s = Glance.statuses(fresh.copy(forecast = null, nowDrawing = null, powerRails = null, night = null, drainApps = null,
            chargerTests = null, pastSessions = null))
        listOf(CardId.FORECAST, CardId.NOW_DRAWING, CardId.POWER_RAIL, CardId.NIGHT, CardId.DRAIN, CardId.CHARGER_TEST, CardId.SESSIONS)
            .forEach { assertFalse(it.name, it in s) }
    }

    @Test fun backgroundFoldsWithoutTheGrantAndWaitsForItsRowsWithIt() {
        assertEquals(Quiet.NEEDS_ACCESS, Glance.statuses(fresh).getValue(CardId.BACKGROUND).quiet)
        assertFalse(CardId.BACKGROUND in Glance.statuses(fresh.copy(backgroundOn = true, backgroundLoaded = false)))
        assertTrue(Glance.statuses(fresh.copy(backgroundOn = true, backgroundLoaded = true)).getValue(CardId.BACKGROUND).open)
    }

    @Test fun aLineThatIsAllThereIsCannotBeExpanded() {
        val s = Glance.statuses(fresh)
        assertFalse(s.getValue(CardId.SESSIONS).expandable)
        // The hint card behind "needs access" and the card's own text behind "nothing yet" can.
        assertTrue(s.getValue(CardId.WAKEUP).expandable)
        assertTrue(s.getValue(CardId.NIGHT).expandable)
    }

    @Test fun aNightAndDataWithGrantsOpenTheCards() {
        val rich = fresh.copy(
            forecast = true, sessionSinceUnplug = true, nowDrawing = true, powerRails = true, night = NightCheck.Verdict.SLEPT_WELL,
            wakeupAccess = true, drainApps = true, chargerTests = true, pastSessions = true, scheduleAccess = true,
            backgroundOn = true, backgroundLoaded = true,
        )
        assertTrue(Glance.statuses(rich).values.all { it.open })
    }

    // ---- order and grouping ----

    private fun groups(plugged: Boolean, s: Map<CardId, Glance.Status>, hidden: Set<String> = emptySet()) =
        Glance.layout(plugged, s, hidden).map { it.group }

    @Test fun openCardsComeBeforeFoldedOnesInsideAGroup() {
        val s = Glance.statuses(fresh.copy(forecast = true, nowDrawing = true))
        val now = Glance.layout(false, s, emptySet()).first { it.group == Group.NOW }
        // Declared order: live, forecast, session, now drawing, rails. Session and rails are folded, so they sink.
        assertEquals(listOf(CardId.LIVE, CardId.FORECAST, CardId.NOW_DRAWING, CardId.SESSION, CardId.POWER_RAIL), now.slots.map { it.card })
    }

    @Test fun groupsFollowTheDefaultOrderWhenEverythingHasSomethingToSay() {
        val rich = Glance.statuses(
            fresh.copy(
                forecast = true, sessionSinceUnplug = true, nowDrawing = true, powerRails = true, night = NightCheck.Verdict.SLEPT_WELL,
                wakeupAccess = true, drainApps = true, chargerTests = true, pastSessions = true, scheduleAccess = true,
                backgroundOn = true, backgroundLoaded = true,
            )
        )
        assertEquals(
            listOf(Group.NOW, Group.LAST_NIGHT, Group.APPS, Group.CHARGING_HEALTH, Group.PLANNED),
            groups(false, rich),
        )
    }

    @Test fun aChargingPhoneMovesChargingAndHealthUp() {
        val s = Glance.statuses(fresh.copy(plugged = true, charging = true, night = NightCheck.Verdict.SLEPT_WELL, drainApps = true))
        assertEquals(Group.NOW, groups(true, s)[0])
        assertEquals(Group.CHARGING_HEALTH, groups(true, s)[1])
    }

    @Test fun aGroupOfOnlyFoldedCardsSinksBelowGroupsWithSomethingToSay() {
        // Last night is folded (no access, no night) while the apps have data: apps first.
        val s = Glance.statuses(fresh.copy(drainApps = true))
        val order = groups(false, s)
        assertTrue(order.indexOf(Group.APPS) < order.indexOf(Group.LAST_NIGHT))
        assertEquals(Group.NOW, order.first())
    }

    @Test fun theLiveGroupStaysFirstEvenWhenFolded() {
        assertEquals(Group.NOW, groups(false, Glance.statuses(fresh)).first())
    }

    @Test fun aHiddenCardLeavesItsSlotAndIsListedAtTheBottomOfItsGroup() {
        val s = Glance.statuses(fresh.copy(drainApps = true))
        val apps = Glance.layout(false, s, setOf("drain")).first { it.group == Group.APPS }
        assertTrue(apps.slots.isEmpty())
        assertEquals(listOf(CardId.DRAIN), apps.hidden)
    }

    @Test fun theLiveCardCannotBeHidden() {
        val now = Glance.layout(false, Glance.statuses(fresh), setOf("live")).first { it.group == Group.NOW }
        assertTrue(now.slots.any { it.card == CardId.LIVE })
        assertTrue(now.hidden.isEmpty())
    }

    @Test fun unknownKeysInTheHiddenSetAreIgnored() {
        val sections = Glance.layout(false, Glance.statuses(fresh), setOf("fromALaterVersion"))
        assertTrue(sections.all { it.hidden.isEmpty() })
    }

    @Test fun aHiddenCardThatDoesNotApplyWhilePluggedIsNotListed() {
        val s = Glance.statuses(fresh.copy(plugged = true, charging = true))
        val now = Glance.layout(true, s, setOf("forecast")).first { it.group == Group.NOW }
        assertTrue(now.hidden.isEmpty())
    }

    @Test fun aGroupWithNothingToShowAndNothingHiddenHasNoTitle() {
        // While the apps load there is no drain card; the group is not shown as an empty title.
        val s = Glance.statuses(fresh.copy(drainApps = null))
        assertFalse(Group.APPS in groups(false, s))
    }

    @Test fun everyHiddenCardStaysReachable() {
        val s = Glance.statuses(fresh)
        val hidden = CardId.entries.filter { it.hideable }.map { it.key }.toSet()
        val sections = Glance.layout(false, s, hidden)
        val listed = sections.flatMap { it.hidden }.toSet()
        assertEquals(CardId.entries.filter { it.hideable }.toSet(), listed)
    }

    @Test fun itemIndexesCountTitlesSlotsAndFooters() {
        val s = Glance.statuses(fresh.copy(drainApps = true))
        val sections = Glance.layout(false, s, setOf("health"))
        val index = Glance.itemIndexes(sections, first = 2)
        // First section title sits at 2, so its first card at 3.
        assertEquals(3, index.getValue(CardId.LIVE))
        val flat = ArrayList<String>()
        sections.forEach { sec ->
            flat += "title:${sec.group}"
            sec.slots.forEach { flat += it.card.name }
            if (sec.hidden.isNotEmpty()) flat += "footer:${sec.group}"
        }
        sections.flatMap { it.slots }.forEach { slot ->
            assertEquals(slot.card.name, flat[index.getValue(slot.card) - 2])
        }
        assertEquals("footer:${Group.CHARGING_HEALTH}", flat[index.getValue(CardId.HEALTH) - 2])
    }

    // ---- today at a glance ----

    private fun level(plugged: Boolean = false, charging: Boolean = false, pattern: Boolean = false, emptyAt: Long? = null, toFull: Long? = null) =
        Glance.LevelFact(0.62, plugged, charging, toFull, pattern, emptyAt)

    private fun finding(kind: Diagnosis.Kind, severity: Diagnosis.Severity = Diagnosis.Severity.WARN, pkg: String? = null) =
        Diagnosis.Finding(kind, severity, Diagnosis.Fix.NONE, pkg)

    @Test fun theOutlookFollowsPlugAndForecast() {
        assertEquals(Glance.LevelFact.Outlook.FULL_IN, level(plugged = true, charging = true, toFull = 600).outlook)
        assertEquals(Glance.LevelFact.Outlook.PLUGGED, level(plugged = true, charging = false).outlook)
        assertEquals(Glance.LevelFact.Outlook.PLUGGED, level(plugged = true, charging = true, toFull = null).outlook)
        assertEquals(Glance.LevelFact.Outlook.EMPTY_PATTERN, level(pattern = true, emptyAt = 1L).outlook)
        assertEquals(Glance.LevelFact.Outlook.EMPTY_RATE, level(pattern = false, emptyAt = 1L).outlook)
        assertEquals(Glance.LevelFact.Outlook.BEYOND, level(pattern = true, emptyAt = null).outlook)
        assertEquals(Glance.LevelFact.Outlook.NONE, level().outlook)
    }

    @Test fun aFreshInstallGetsTheLevelAloneNotAnEmptyCard() {
        val lines = Glance.glance(Glance.Facts(level = level()))
        assertEquals(1, lines.size)
        assertTrue(lines.single() is Glance.Line.Level)
    }

    @Test fun theLevelIsAlwaysFirstEvenBehindALoudWarning() {
        val facts = Glance.Facts(
            level = level(pattern = true, emptyAt = 1L),
            findings = listOf(finding(Diagnosis.Kind.HOT, Diagnosis.Severity.BAD)),
            night = Glance.NightFact(NightCheck.Verdict.SLEPT_WELL, null),
        )
        val lines = Glance.glance(facts)
        assertTrue(lines.first() is Glance.Line.Level)
        assertTrue(lines[1] is Glance.Line.Heads)
    }

    @Test fun onlyTheMostImportantWarningIsShown() {
        val facts = Glance.Facts(
            level = level(),
            findings = listOf(
                finding(Diagnosis.Kind.SCREEN_OFF_DRAIN), finding(Diagnosis.Kind.WAKELOCK_LIMIT, pkg = "a"),
                finding(Diagnosis.Kind.HOT, Diagnosis.Severity.BAD),
            ),
        )
        val warnings = Glance.glance(facts).filterIsInstance<Glance.Line.Heads>()
        assertEquals(1, warnings.size)
        assertEquals(Diagnosis.Kind.HOT, warnings.single().finding.kind)
    }

    @Test fun equalSeverityIsDecidedByWhatDrainsTheBatteryFirst() {
        val facts = Glance.Facts(
            findings = listOf(finding(Diagnosis.Kind.BATTERY_WORN), finding(Diagnosis.Kind.WAKELOCK_LIMIT, pkg = "a")),
        )
        assertEquals(Diagnosis.Kind.WAKELOCK_LIMIT, (Glance.glance(facts).single() as Glance.Line.Heads).finding.kind)
    }

    @Test fun infoFindingsAndNonBatteryKindsAreNotHeadsUpOnTheBatteryScreen() {
        val facts = Glance.Facts(
            findings = listOf(
                finding(Diagnosis.Kind.POWER_SAVE, Diagnosis.Severity.INFO), finding(Diagnosis.Kind.TOP_DRAIN_APP, Diagnosis.Severity.INFO),
                finding(Diagnosis.Kind.STORAGE_FULL, Diagnosis.Severity.BAD), finding(Diagnosis.Kind.LOW_MEMORY),
            ),
        )
        assertTrue(Glance.glance(facts).isEmpty())
    }

    @Test fun heatPointsAtTheLiveCardAndThrottlingAtTheHardwarePage() {
        val hot = Glance.glance(Glance.Facts(findings = listOf(finding(Diagnosis.Kind.HOT)))).single()
        assertEquals(Target.OnScreen(CardId.LIVE), hot.target)
        val throttle = Glance.glance(Glance.Facts(findings = listOf(finding(Diagnosis.Kind.THROTTLING_APP, pkg = "x")))).single()
        assertEquals(Target.Page(Glance.Destination.HARDWARE), throttle.target)
    }

    @Test fun anUnusualAppCountsAsAWarningAndLeadsToTheWakeupCard() {
        val line = Glance.glance(Glance.Facts(unusualPkg = "com.example")).single()
        assertEquals(Glance.Line.Unusual("com.example"), line)
        assertEquals(Target.OnScreen(CardId.WAKEUP), line.target)
    }

    @Test fun aWarningBeatsAnUnusualAppWhenItIsWorse() {
        val facts = Glance.Facts(unusualPkg = "a", findings = listOf(finding(Diagnosis.Kind.HOT, Diagnosis.Severity.BAD)))
        assertTrue(Glance.glance(facts).single() is Glance.Line.Heads)
        // An unusual app outranks a plain warning.
        val mild = Glance.Facts(unusualPkg = "a", findings = listOf(finding(Diagnosis.Kind.BATTERY_WORN)))
        assertTrue(Glance.glance(mild).single() is Glance.Line.Unusual)
    }

    @Test fun aBadNightIsLouderThanAGoodOneAndLouderInTheMorning() {
        val bad = Glance.Line.Night(Glance.NightFact(NightCheck.Verdict.SYSTEM_DID_NOT_SLEEP, null), morning = false)
        val badMorning = bad.copy(morning = true)
        val good = Glance.Line.Night(Glance.NightFact(NightCheck.Verdict.SLEPT_WELL, null), morning = false)
        assertTrue(badMorning.score > bad.score)
        assertTrue(bad.score > good.score)
        assertTrue(good.score < Glance.Line.TopApp(Glance.TopAppFact("a", 100.0)).score)
    }

    @Test fun lastNightWithoutDataIsLeftOut() {
        val lines = Glance.glance(Glance.Facts(night = Glance.NightFact(NightCheck.Verdict.NOT_ENOUGH_DATA, null)))
        assertTrue(lines.isEmpty())
    }

    @Test fun aBadNightComesBeforeTheBusiestAppAndAGoodOneAfterIt() {
        val top = Glance.TopAppFact("a", 500.0)
        val bad = Glance.glance(Glance.Facts(night = Glance.NightFact(NightCheck.Verdict.APP_KEPT_AWAKE, "b"), topApp = top))
        assertTrue(bad[0] is Glance.Line.Night)
        assertTrue(bad[1] is Glance.Line.TopApp)
        val good = Glance.glance(Glance.Facts(night = Glance.NightFact(NightCheck.Verdict.SLEPT_WELL, null), topApp = top))
        assertTrue(good[0] is Glance.Line.TopApp)
        assertTrue(good[1] is Glance.Line.Night)
    }

    @Test fun anAppThatUsedAlmostNothingIsNotNamed() {
        val lines = Glance.glance(Glance.Facts(topApp = Glance.TopAppFact("a", Glance.MIN_TOP_APP_MWH - 1)))
        assertTrue(lines.isEmpty())
    }

    @Test fun theCardNeverHasMoreThanFourLines() {
        val facts = Glance.Facts(
            level = level(), night = Glance.NightFact(NightCheck.Verdict.APP_KEPT_AWAKE, "b"), topApp = Glance.TopAppFact("a", 500.0),
            findings = listOf(finding(Diagnosis.Kind.HOT)), unusualPkg = "c",
        )
        assertEquals(4, Glance.glance(facts).size)
        assertEquals(2, Glance.glance(facts, max = 2).size)
    }

    @Test fun aHiddenCardTakesItsLineAway() {
        val facts = Glance.Facts(
            night = Glance.NightFact(NightCheck.Verdict.SLEPT_WELL, null), topApp = Glance.TopAppFact("a", 500.0),
            findings = listOf(finding(Diagnosis.Kind.WAKELOCK_LIMIT, pkg = "x")),
        )
        val lines = Glance.glance(facts, hidden = setOf(CardId.NIGHT, CardId.WAKEUP))
        assertEquals(1, lines.size)
        assertTrue(lines.single() is Glance.Line.TopApp)
        assertNull(lines.firstOrNull { it is Glance.Line.Night || it is Glance.Line.Heads })
    }

    @Test fun theLevelLineFallsBackToTheLiveCardWhenTheForecastIsHidden() {
        val facts = Glance.Facts(level = level(pattern = true, emptyAt = 5L))
        assertEquals(Target.OnScreen(CardId.FORECAST), Glance.glance(facts).single().target)
        assertEquals(Target.OnScreen(CardId.LIVE), Glance.glance(facts, hidden = setOf(CardId.FORECAST)).single().target)
        assertEquals(Target.OnScreen(CardId.LIVE), Glance.glance(Glance.Facts(level = level())).single().target)
        assertEquals(Target.OnScreen(CardId.SESSION), Glance.glance(Glance.Facts(level = level(plugged = true, charging = true))).single().target)
    }

    // ---- Overview: worth a look ----

    @Test fun nothingWorthALookIsAnEmptyList() {
        assertTrue(Glance.worthALook(Glance.Facts(level = level(), topApp = Glance.TopAppFact("a", 900.0))).isEmpty())
    }

    @Test fun aNightThatWentWellIsNotWorthALook() {
        val facts = Glance.Facts(night = Glance.NightFact(NightCheck.Verdict.SLEPT_WELL, null), morning = true)
        assertTrue(Glance.worthALook(facts).isEmpty())
    }

    @Test fun atMostTwoItemsTheMostRelevantFirst() {
        val facts = Glance.Facts(
            findings = listOf(
                finding(Diagnosis.Kind.LONG_UPTIME), finding(Diagnosis.Kind.STORAGE_FULL, Diagnosis.Severity.BAD),
                finding(Diagnosis.Kind.HOT, Diagnosis.Severity.BAD),
            ),
            night = Glance.NightFact(NightCheck.Verdict.HIGH_DRAIN_WHILE_ASLEEP, null), morning = true,
        )
        val items = Glance.worthALook(facts)
        assertEquals(2, items.size)
        assertEquals(Diagnosis.Kind.HOT, (items[0] as Glance.Line.Heads).finding.kind)
        assertEquals(Diagnosis.Kind.STORAGE_FULL, (items[1] as Glance.Line.Heads).finding.kind)
    }

    @Test fun whatTheVerdictAlreadySaysIsSkipped() {
        val hot = finding(Diagnosis.Kind.HOT, Diagnosis.Severity.BAD)
        val worn = finding(Diagnosis.Kind.BATTERY_WORN)
        val items = Glance.worthALook(Glance.Facts(findings = listOf(hot, worn)), skip = setOf(Glance.findingKey(hot)))
        assertEquals(listOf(Diagnosis.Kind.BATTERY_WORN), items.map { (it as Glance.Line.Heads).finding.kind })
    }

    @Test fun theOverviewAlsoShowsStorageAndMemoryFindingsAndLeadsStorageToItsPage() {
        val items = Glance.worthALook(Glance.Facts(findings = listOf(finding(Diagnosis.Kind.STORAGE_FULL), finding(Diagnosis.Kind.LOW_MEMORY))))
        assertEquals(2, items.size)
        assertEquals(Target.Page(Glance.Destination.STORAGE), items.first { (it as Glance.Line.Heads).finding.kind == Diagnosis.Kind.STORAGE_FULL }.target)
        assertEquals(Target.Page(Glance.Destination.DIAGNOSIS), items.first { (it as Glance.Line.Heads).finding.kind == Diagnosis.Kind.LOW_MEMORY }.target)
    }

    @Test fun sameFindingKindOfAnotherAppIsADifferentKey() {
        assertFalse(Glance.findingKey(finding(Diagnosis.Kind.WAKELOCK_LIMIT, pkg = "a")) == Glance.findingKey(finding(Diagnosis.Kind.WAKELOCK_LIMIT, pkg = "b")))
    }
}
