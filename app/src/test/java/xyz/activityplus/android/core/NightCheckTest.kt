package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.NightCheck.Doze
import xyz.activityplus.android.core.NightCheck.Measured
import xyz.activityplus.android.core.NightCheck.Reason
import xyz.activityplus.android.core.NightCheck.Span
import xyz.activityplus.android.core.NightCheck.Suspect
import xyz.activityplus.android.core.NightCheck.Usual
import xyz.activityplus.android.core.NightCheck.Verdict
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightCheckTest {
    private val min = 60_000L
    private val h = 3_600_000L
    private val zone = ZoneId.of("Europe/Vienna")
    private val day = LocalDate.of(2026, 10, 18)
    private fun at(d: LocalDate, hh: Int, mm: Int = 0) = ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, hh, mm, 0, 0, zone).toInstant().toEpochMilli()
    private fun evening(hh: Int, mm: Int = 0) = at(day.minusDays(1), hh, mm)
    private fun morning(hh: Int, mm: Int = 0) = at(day, hh, mm)

    /**
     * A span from [start] to [end] with [deep] of it in CPU suspend; the monotonic clocks start at an
     * arbitrary boot offset so only differences matter, as on a phone.
     */
    private fun span(
        start: Long, end: Long, deep: Double = 0.95, from: Double = 0.80, to: Double = 0.77,
        plugged: Boolean = false, doze: List<Doze> = emptyList(),
        mahFrom: Double? = null, mahTo: Double? = null, signal: Int? = null,
    ): Span {
        val boot = start - 5 * h
        val d = end - start
        return Span(
            start, end, start - boot, end - boot, 1_000_000L, 1_000_000L + Math.round(d * (1 - deep)),
            from, to, mahFrom, mahTo, plugged, doze, signal, signal,
        )
    }

    private fun measured(
        drain: Double = 0.4, deep: Double = 0.95, firstDeep: Long? = 40 * min, hours: Double = 8.0,
        interruptions: Int = 4, weak: Boolean = false,
    ) = Measured(0, (hours * h).toLong(), (hours * h).toLong(), deep, firstDeep, true, interruptions, drain, null, weak)

    private fun usual(drain: Double = 0.4, deep: Double = 0.95) = Usual(10, drain, deep, 4.0)

    // Spans and clocks

    @Test fun deepSleepIsElapsedMinusAwakeTime() {
        val s = span(evening(23), morning(7), deep = 0.9)
        assertEquals(8 * h, s.durationMs)
        assertEquals(Math.round(8 * h * 0.9), s.deepSleepMs)
    }

    @Test fun deepSleepNeverExceedsTheSpan() {
        val s = span(evening(23), morning(7)).copy(endUptime = 0)
        assertEquals(s.durationMs, s.deepSleepMs)
    }

    @Test fun rebootOrMovedClockMakesASpanInvalid() {
        val s = span(evening(23), morning(7))
        assertTrue(NightCheck.isValid(s))
        // A reboot restarts elapsedRealtime.
        assertFalse(NightCheck.isValid(s.copy(endElapsed = 10_000)))
        // The wall clock jumped an hour (time zone change) while elapsed time did not.
        assertFalse(NightCheck.isValid(s.copy(endMillis = s.endMillis + h)))
        // Awake longer than elapsed is impossible.
        assertFalse(NightCheck.isValid(s.copy(endUptime = s.startUptime + 9 * h)))
    }

    // The window

    @Test fun nightDateIsYesterdayBeforeFiveInTheMorning() {
        assertEquals(day.minusDays(1), NightCheck.nightDate(morning(3), zone))
        assertEquals(day, NightCheck.nightDate(morning(5), zone))
        assertEquals(day, NightCheck.nightDate(morning(22), zone))
    }

    @Test fun windowRunsFromEightInTheEveningToNoon() {
        val w = NightCheck.window(day, zone)
        assertEquals(evening(20), w.first)
        assertEquals(morning(12), w.last)
    }

    @Test fun clustersMergeShortScreenOnsAndSkipCharging() {
        val a = span(evening(23), morning(3))
        val b = span(morning(3, 5), morning(7)) // a 5 minute look at the clock
        val c = span(morning(7, 30), morning(8)) // half an hour of use before
        val charged = span(morning(8), morning(9), plugged = true)
        val clusters = NightCheck.clusters(listOf(c, charged, b, a))
        assertEquals(listOf(listOf(a, b), listOf(c)), clusters)
    }

    @Test fun pickTakesTheLongestNightAcrossAGlance() {
        val spans = listOf(
            span(evening(18), evening(19)), // evening, before the window
            span(evening(23), morning(2)),
            span(morning(2, 4), morning(6, 30)),
            span(morning(8), morning(11)), // morning at the desk: starts after 05:00, no night
        )
        val night = NightCheck.pick(spans, day, zone)!!
        assertEquals(evening(23), night.first().startMillis)
        assertEquals(morning(6, 30), night.last().endMillis)
    }

    @Test fun pickIgnoresNapsAndNightsEndingAfterNoon() {
        assertNull(NightCheck.pick(listOf(span(morning(6), morning(11))), day, zone))
        assertNull(NightCheck.pick(listOf(span(evening(23), morning(13))), day, zone))
        assertNull(NightCheck.pick(listOf(span(evening(14), evening(19))), day, zone))
        assertNull(NightCheck.pick(listOf(span(evening(23), morning(7), plugged = true)), day, zone))
    }

    @Test fun pickPrefersTheLongerOfTwoBrokenNights() {
        val first = span(evening(22), morning(1))
        val second = span(morning(1, 30), morning(7))
        assertEquals(listOf(second), NightCheck.pick(listOf(first, second), day, zone))
    }

    // Doze

    @Test fun dozeStatsCountExitsButNotTheScreenTurningOn() {
        val s = span(
            evening(23), morning(7),
            doze = listOf(
                Doze(evening(23, 5), deep = false, enter = true),
                Doze(evening(23, 50), deep = true, enter = true),
                Doze(morning(1), deep = true, enter = false), // maintenance window
                Doze(morning(1, 5), deep = true, enter = true),
                Doze(morning(7) - 10_000, deep = true, enter = false), // screen on
            ),
        )
        val d = NightCheck.dozeStats(s)
        assertEquals(evening(23, 50), d.firstDeepMillis)
        assertEquals(1, d.deepExits)
        assertTrue(d.lightReached)
    }

    @Test fun anExitWithoutAnEntryIsNotAnInterruption() {
        val s = span(evening(23), morning(7), doze = listOf(Doze(evening(23, 30), deep = true, enter = false)))
        val d = NightCheck.dozeStats(s)
        assertEquals(0, d.deepExits)
        assertNull(d.firstDeepMillis)
        assertFalse(d.lightReached)
    }

    @Test fun dozeRoundTripsThroughText() {
        val events = listOf(Doze(1L, deep = true, enter = true), Doze(2L, deep = false, enter = false))
        assertEquals(events, NightCheck.decodeDoze(NightCheck.encodeDoze(events)))
        assertEquals(emptyList<Doze>(), NightCheck.decodeDoze(null))
        assertEquals(listOf(Doze(5L, deep = true, enter = false)), NightCheck.decodeDoze("x,D,1;5,D,0;6,Q,1;7,L"))
    }

    // Drain and signal

    @Test fun counterIsUsableWhenItAgreesWithTheLevel() {
        assertTrue(NightCheck.counterUsable(120.0, 0.03, 4000.0))
        // 400 mAh of 4000 is 10 %, the level says 3 %: the counter is broken.
        assertFalse(NightCheck.counterUsable(400.0, 0.03, 4000.0))
        assertFalse(NightCheck.counterUsable(-50.0, 0.03, 4000.0))
        assertTrue(NightCheck.counterUsable(30.0, 0.0, null))
    }

    @Test fun weakSignalIsTheMiddleReading() {
        assertFalse(NightCheck.weakSignal(emptyList()))
        assertTrue(NightCheck.weakSignal(listOf(1, 1, 3)))
        assertFalse(NightCheck.weakSignal(listOf(1, 3, 4)))
        assertTrue(NightCheck.weakSignal(listOf(0, 4)))
    }

    @Test fun measureUsesTheLevelWithoutACounter() {
        val m = NightCheck.measure(listOf(span(evening(23), morning(7), deep = 0.96, from = 0.80, to = 0.76)), 4000.0)
        assertEquals(8 * h, m.asleepMs)
        assertEquals(0.96, m.deepShare, 1e-6)
        assertEquals(0.5, m.drainPerHour, 1e-9)
        assertNull(m.mahPerHour)
        assertNull(m.firstDeepAfterMs)
        assertFalse(m.weakSignal)
    }

    @Test fun measureUsesAUsableCounterOverMergedSpans() {
        val a = span(evening(23), morning(3), from = 0.80, to = 0.79, mahFrom = 3200.0, mahTo = 3160.0,
            doze = listOf(Doze(evening(23, 45), deep = true, enter = true)), signal = 1)
        val b = span(morning(3, 5), morning(7, 5), from = 0.79, to = 0.77, mahFrom = 3158.0, mahTo = 3118.0, signal = 1)
        val m = NightCheck.measure(listOf(a, b), 4000.0)
        assertEquals(8 * h, m.asleepMs)
        // 80 mAh over 8 h of a 4000 mAh battery.
        assertEquals(10.0, m.mahPerHour!!, 1e-9)
        assertEquals(0.25, m.drainPerHour, 1e-9)
        assertEquals(45 * min, m.firstDeepAfterMs)
        assertTrue(m.weakSignal)
        // The screen-on in between is not counted as drain.
        assertEquals(evening(23), m.fromMillis)
        assertEquals(morning(7, 5), m.toMillis)
    }

    // The usual night

    @Test fun medianOfOddAndEven() {
        assertEquals(2.0, NightCheck.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, NightCheck.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }

    @Test fun usualNeedsThreeNightsAndTakesTheLastFourteen() {
        assertNull(NightCheck.usual(listOf(measured(), measured())))
        val old = List(10) { measured(drain = 5.0) }
        val recent = List(14) { measured(drain = 0.5, deep = 0.9) }
        val u = NightCheck.usual(old + recent)!!
        assertEquals(14, u.nights)
        assertEquals(0.5, u.drainPerHour, 0.0)
        assertEquals(0.9, u.deepShare, 0.0)
    }

    // Judging

    @Test fun badSleepByShareByUsualOrWithoutDeepDoze() {
        assertFalse(NightCheck.sleptBadly(measured(deep = 0.95), null))
        assertTrue(NightCheck.sleptBadly(measured(deep = 0.6), null))
        assertTrue(NightCheck.sleptBadly(measured(deep = 0.78), usual(deep = 0.97)))
        assertFalse(NightCheck.sleptBadly(measured(deep = 0.78), null))
        assertTrue(NightCheck.sleptBadly(measured(deep = 0.8, firstDeep = null), null))
        // No deep Doze, but the CPU slept anyway: no reason to blame Android.
        assertFalse(NightCheck.sleptBadly(measured(deep = 0.93, firstDeep = null), null))
    }

    @Test fun highDrainAbsoluteOrAgainstTheUsualNight() {
        assertTrue(NightCheck.drainHigh(measured(drain = 2.3), null))
        assertFalse(NightCheck.drainHigh(measured(drain = 1.2), null))
        assertTrue(NightCheck.drainHigh(measured(drain = 1.2), usual(drain = 0.5)))
        // Double of very little is still little.
        assertFalse(NightCheck.drainHigh(measured(drain = 0.8), usual(drain = 0.3)))
    }

    @Test fun rankDropsAndroidsOwnRowsAndPutsTheWorstFirst() {
        val ranked = NightCheck.rank(
            listOf(
                Suspect("#system", 5 * h, 0),
                Suspect("com.example.mail", 10 * min, 20),
                Suspect("com.example.chat", 40 * min, 0),
            )
        )
        assertEquals(listOf("com.example.chat", "com.example.mail"), ranked.map { it.pkg })
        assertFalse(NightCheck.standsOut(ranked[0]))
        assertFalse(NightCheck.standsOut(Suspect("x", 5 * min, 20)))
        assertTrue(NightCheck.standsOut(Suspect("x", 0, 150)))
    }

    private val goodNight = listOf(span(evening(23), morning(7), deep = 0.96, from = 0.80, to = 0.77,
        doze = listOf(Doze(evening(23, 40), deep = true, enter = true))))

    @Test fun nothingRecordedChargedOrShort() {
        assertEquals(Reason.NO_RECORD, NightCheck.judge(null, false, 0, null, null, null).reason)
        val charged = NightCheck.judge(null, true, 8 * h, null, null, null)
        assertEquals(Verdict.NOT_ENOUGH_DATA, charged.verdict)
        assertEquals(Reason.CHARGED, charged.reason)
        val short = NightCheck.judge(listOf(span(morning(2), morning(4))), true, 0, null, null, null)
        assertEquals(Reason.SHORT, short.reason)
        assertNotNull(short.measured)
    }

    @Test fun aGoodNightSleptWellEvenWithABusyApp() {
        val r = NightCheck.judge(goodNight, true, 0, null, null, listOf(Suspect("com.example.chat", 20 * min, 0)))
        assertEquals(Verdict.SLEPT_WELL, r.verdict)
        assertEquals(Reason.GOOD, r.reason)
        assertNull(r.suspect)
        assertTrue(r.appsChecked)
    }

    @Test fun anAppThatStandsOutIsBlamedForABadNight() {
        val bad = listOf(span(evening(23), morning(7), deep = 0.5, from = 0.80, to = 0.70))
        val awake = NightCheck.judge(bad, true, 0, null, null, listOf(Suspect("com.example.chat", 3 * h, 2)))
        assertEquals(Verdict.APP_KEPT_AWAKE, awake.verdict)
        assertEquals(Reason.APP_AWAKE, awake.reason)
        assertEquals("com.example.chat", awake.suspect!!.pkg)
        val woke = NightCheck.judge(bad, true, 0, null, null, listOf(Suspect("com.example.mail", 2 * min, 400)))
        assertEquals(Reason.APP_WAKEUPS, woke.reason)
    }

    @Test fun overlappingWakelocksAloneDoNotBlameAnApp() {
        val bad = listOf(span(evening(23), morning(7), deep = 0.5, from = 0.80, to = 0.70))
        val result = NightCheck.judge(bad, true, 0, null, null, listOf(Suspect("com.example.app", 3 * h, 0)))
        assertEquals(Verdict.SYSTEM_DID_NOT_SLEEP, result.verdict)
        assertNull(result.suspect)
    }

    @Test fun androidIsBlamedWhenNoAppStandsOut() {
        val noDeep = listOf(span(evening(23), morning(7), deep = 0.6, from = 0.80, to = 0.62))
        val r = NightCheck.judge(noDeep, true, 0, null, null, listOf(Suspect("com.example.mail", 2 * min, 10)))
        assertEquals(Verdict.SYSTEM_DID_NOT_SLEEP, r.verdict)
        assertEquals(Reason.NO_DEEP_DOZE, r.reason)
        val withDoze = listOf(noDeep[0].copy(doze = listOf(Doze(evening(23, 40), deep = true, enter = true))))
        assertEquals(Reason.LOW_DEEP_SLEEP, NightCheck.judge(withDoze, true, 0, null, null, emptyList()).reason)
        // Without app data the verdict stays, but the card cannot rule an app out.
        assertFalse(NightCheck.judge(noDeep, true, 0, null, null, null).appsChecked)
    }

    @Test fun highDrainWithGoodSleepPointsAtRadioOrHardware() {
        val drain = listOf(goodNight[0].copy(endLevel = 0.62))
        val r = NightCheck.judge(drain, true, 0, null, null, emptyList())
        assertEquals(Verdict.HIGH_DRAIN_WHILE_ASLEEP, r.verdict)
        assertEquals(Reason.HIGH_DRAIN, r.reason)
        val aboveUsual = listOf(goodNight[0].copy(endLevel = 0.71)) // 1.125 %/h
        val u = NightCheck.judge(aboveUsual, true, 0, null, usual(drain = 0.4), emptyList())
        assertEquals(Reason.DRAIN_ABOVE_USUAL, u.reason)
        assertEquals(Verdict.SLEPT_WELL, NightCheck.judge(aboveUsual, true, 0, null, null, emptyList()).verdict)
    }

    @Test fun historyJudgesEachNightAgainstTheNightsBefore() {
        val dates = (5 downTo 0).map { day.minusDays(it.toLong()) }
        val spans = dates.dropLast(1).map { d ->
            span(at(d.minusDays(1), 23), at(d, 7), deep = 0.95, from = 0.80, to = 0.776) // 0.3 %/h
        } + span(evening(23), morning(7), deep = 0.95, from = 0.80, to = 0.72) // 1 %/h
        var asked = 0
        val entries = NightCheck.history(spans, dates, zone, null, judged = 2) { asked++; emptyList() }
        assertEquals(2, entries.size)
        assertEquals(2, asked)
        assertEquals(day, entries.last().date)
        val last = entries.last().result
        assertEquals(5, last.usual!!.nights)
        assertEquals(Verdict.HIGH_DRAIN_WHILE_ASLEEP, last.verdict)
        assertEquals(Reason.DRAIN_ABOVE_USUAL, last.reason)
        assertEquals(Verdict.SLEPT_WELL, entries.first().result.verdict)
    }

    @Test fun historyMarksAChargedNight() {
        val dates = listOf(day)
        val spans = listOf(span(evening(23), morning(7), plugged = true))
        val r = NightCheck.history(spans, dates, zone, null, 1) { error("no apps for a charged night") }.single().result
        assertEquals(Reason.CHARGED, r.reason)
    }
}
