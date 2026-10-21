package xyz.activityplus.android.ui

import org.junit.Test
import xyz.activityplus.android.R
import xyz.activityplus.android.core.BatteryPassport
import xyz.activityplus.android.core.BatterySession
import xyz.activityplus.android.core.CapacityEstimate
import xyz.activityplus.android.core.ChargingHabits
import xyz.activityplus.android.core.Forecast
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.NightCheck
import xyz.activityplus.android.core.PlugType
import xyz.activityplus.android.core.PowerRails
import xyz.activityplus.android.core.Schedule
import xyz.activityplus.android.core.Wakeups
import xyz.activityplus.android.ui.Fixtures.NOW
import xyz.activityplus.android.ui.Fixtures.battery
import xyz.activityplus.android.ui.Fixtures.snapshot
import xyz.activityplus.android.ui.screens.BackgroundTodayCard
import xyz.activityplus.android.ui.screens.BackgroundUnavailableContent
import xyz.activityplus.android.ui.screens.BatteryPassportContent
import xyz.activityplus.android.ui.screens.ChargerTestCard
import xyz.activityplus.android.ui.screens.ChargingHabitsContent
import xyz.activityplus.android.ui.screens.ChargingHealthTrendContent
import xyz.activityplus.android.ui.screens.DrainEmptyCard
import xyz.activityplus.android.ui.screens.ForecastContent
import xyz.activityplus.android.ui.screens.HabitsData
import xyz.activityplus.android.ui.screens.NightCheckCard
import xyz.activityplus.android.ui.screens.NightReport
import xyz.activityplus.android.ui.screens.PowerRailContent
import xyz.activityplus.android.ui.screens.PowerRailUnavailable
import xyz.activityplus.android.ui.screens.PowerWindow
import xyz.activityplus.android.ui.screens.ScheduleContent
import xyz.activityplus.android.ui.screens.ScheduleHintCard
import xyz.activityplus.android.ui.screens.SessionsCard
import xyz.activityplus.android.ui.screens.SinceUnplugCard
import xyz.activityplus.android.ui.screens.WakeupContent
import xyz.activityplus.android.ui.screens.WakeupHintCard
import xyz.activityplus.android.ui.screens.WakeupOverview
import java.time.LocalDate

/** The Battery screen's cards with missing data: each one says why, nothing disappears. */
class BatteryCardsTest : CardTest() {

    // Remaining time by your pattern

    @Test fun forecastWithoutPatternOrRateSaysWhatItNeeds() {
        show { ForecastContent(battery(), Forecast.profile(emptyList(), emptyList()), NOW) }
        shown(str(R.string.fc_title))
        shown(str(R.string.es_forecast_none))
    }

    @Test fun forecastWhilePatternLoadsSaysLoading() {
        show { ForecastContent(battery(), null, NOW) }
        shown(str(R.string.loading))
    }

    @Test fun forecastAtTheCurrentRateNotesThePatternIsStillLearning() {
        show { ForecastContent(battery(currentMa = -500.0, designMah = 4000.0), Forecast.profile(emptyList(), emptyList()), NOW) }
        shown(str(R.string.fc_not_enough))
        absent(str(R.string.es_forecast_none))
    }

    // Since unplugging

    @Test fun sinceUnplugWithoutSessionSaysWhenItStarts() {
        show { SinceUnplugCard(null, loaded = true) }
        shown(str(R.string.session_since_unplug_title))
        shown(str(R.string.es_unplug_none))
    }

    @Test fun sinceUnplugInTheFirstMinuteSaysTheNumbersFollow() {
        show { SinceUnplugCard(session(seconds = 30), loaded = true) }
        shown(str(R.string.es_unplug_soon))
    }

    @Test fun sinceUnplugWhileSessionsLoadSaysLoading() {
        show { SinceUnplugCard(null, loaded = false) }
        shown(str(R.string.loading))
    }

    @Test fun sinceUnplugWithASessionShowsIt() {
        show { SinceUnplugCard(session(seconds = 7200), loaded = true) }
        shown(str(R.string.session_screen_on))
        absent(str(R.string.es_unplug_none))
    }

    // Recent charges and discharges

    @Test fun sessionsEmptySaysWhenTheyShow() {
        show { SessionsCard(emptyList()) }
        shown(str(R.string.sessions_title))
        shown(str(R.string.es_sessions_none))
    }

    @Test fun sessionsListed() {
        show { SessionsCard(listOf(session(seconds = 3600))) }
        absent(str(R.string.es_sessions_none))
        shownPart("60 → 50 %")
    }

    // Charger test

    @Test fun chargerTestOnBatterySaysToPlugIn() {
        show { ChargerTestCard(snapshot(), fahrenheit = false) }
        shown(str(R.string.chg_test_title))
        shown(str(R.string.es_charger_test_plug))
    }

    @Test fun chargerTestWhileChargingOffersTheTest() {
        show { ChargerTestCard(snapshot(battery(plug = PlugType.AC)), fahrenheit = false) }
        shown(str(R.string.chg_test_intro))
        absent(str(R.string.es_charger_test_plug))
    }

    // Where the power goes

    @Test fun powerRailsOnOldAndroidNamesTheVersion() {
        show { PowerRailUnavailable(possible = false) }
        shown(str(R.string.es_power_needs_android15))
    }

    @Test fun powerRailsOnPhoneWithoutMonitorsSaysSo() {
        show { PowerRailUnavailable(possible = true) }
        shown(str(R.string.power_unsupported))
    }

    @Test fun powerRailsWhileLoadingSaysLoading() {
        show { rails(rates = null, enough = false) }
        shown(str(R.string.loading))
    }

    @Test fun powerRailsWithTooLittleDataSaysHowLong() {
        show { rails(rates = emptyMap(), enough = false) }
        shown(str(R.string.power_wait, Format.duration(PowerRails.minWindowMs(false) / 1000)))
    }

    @Test fun powerRailsWithPlaceholderMetersSaysSo() {
        show { rails(rates = mapOf("[Rail1]:Display" to 2.0), enough = true) }
        shown(str(R.string.power_silent))
    }

    @Test fun powerRailsWithReadingsShowsTheBreakdown() {
        show { rails(rates = mapOf("[Rail1]:Display" to 600.0, "[Rail2]:CPU" to 900.0), enough = true) }
        shown(str(R.string.power_measured))
    }

    // Battery passport

    @Test fun passportWithGrantButNothingReportedSaysSo() {
        show { BatteryPassportContent(passport(granted = true), onPro = {}) }
        shown(str(R.string.passport_title))
        shown(str(R.string.es_passport_none))
    }

    @Test fun passportWithoutGrantOffersIt() {
        show { BatteryPassportContent(passport(granted = false), onPro = {}) }
        shownPart(str(R.string.passport_more))
        absent(str(R.string.es_passport_none))
    }

    @Test fun passportWithValuesShowsThem() {
        show { BatteryPassportContent(passport(granted = true).copy(sohPercent = 92), onPro = {}) }
        shown(str(R.string.passport_soh))
        absent(str(R.string.es_passport_none))
    }

    // Charging habits

    @Test fun habitsOnAFreshInstallSayWhatTheyNeed() {
        show { ChargingHabitsContent(HabitsData(null, emptyList(), ChargingHabits.Ageing.NotYet(0, 0.0)), fahrenheit = false) }
        shown(str(R.string.habits_title))
        shown(str(R.string.habits_need_days))
        shownPart(str(R.string.habits_age_more, 0, ChargingHabits.MIN_MEASUREMENTS, Math.round(ChargingHabits.MIN_SPAN_DAYS / 7).toInt()))
    }

    // Health card: capacity trend

    @Test fun capacityTrendBelowTwoChargesSaysWhenItShows() {
        show { ChargingHealthTrendContent(listOf(estimate(0, 4000.0))) }
        shown(str(R.string.chg_trend_title))
        shown(str(R.string.es_trend_none))
    }

    @Test fun capacityTrendWithTwoCharges() {
        show { ChargingHealthTrendContent(listOf(estimate(0, 4000.0), estimate(30, 3950.0))) }
        shown(str(R.string.chg_trend_note))
        absent(str(R.string.es_trend_none))
    }

    // Battery per app today (also the Overview's busiest card)

    @Test fun drainWithoutUsageAccessNamesTheReason() {
        show { DrainEmptyCard("Battery by app today", access = false) }
        shown(str(R.string.es_drain_needs_access))
        shownPart(str(R.string.access_button))
    }

    @Test fun drainWithAccessButNothingMeasured() {
        show { DrainEmptyCard("Battery by app today", access = true) }
        shown(str(R.string.drain_empty))
    }

    // Background today

    @Test fun backgroundWithoutGrantPointsToPro() {
        show { BackgroundUnavailableContent(grant = false, onPro = {}) }
        shown(str(R.string.bg_today_title))
        shown(str(R.string.es_bg_hint))
    }

    @Test fun backgroundSwitchedOffSaysWhere() {
        show { BackgroundUnavailableContent(grant = true, onPro = {}) }
        shown(str(R.string.es_bg_off))
    }

    @Test fun backgroundBeforeTheFirstReadingSaysWhenItComes() {
        show { BackgroundTodayCard(emptyList(), onPro = {}) }
        shown(str(R.string.bg_today_empty))
    }

    // Who woke the phone

    @Test fun wakeupsWithoutGrantPointToPro() {
        show { WakeupHintCard(onPro = {}) }
        shown(str(R.string.wk_hint))
    }

    @Test fun wakeupsBeforeTheFirstIntervalSayWhenTheyCome() {
        show { WakeupContent(WakeupOverview(emptyList(), null, null, emptySet(), measured = false), emptySet()) {} }
        shown(str(R.string.wk_empty))
    }

    @Test fun wakeupsMeasuredButQuiet() {
        show { WakeupContent(WakeupOverview(emptyList(), null, null, emptySet(), measured = true), emptySet()) {} }
        shown(str(R.string.wk_quiet))
    }

    @Test fun wakeupsListed() {
        val app = Wakeups.AppCounters("com.example.chat", Wakeups.Counters.ZERO.copy(wakeups = 12))
        show { WakeupContent(WakeupOverview(listOf(app), null, null, emptySet(), measured = true), emptySet()) {} }
        absent(str(R.string.wk_quiet))
        shownPart(plural(R.plurals.wk_wakeups, 12, 12))
    }

    // Planned for tonight

    @Test fun scheduleWithoutGrantPointsToPro() {
        show { ScheduleHintCard(onPro = {}) }
        shown(str(R.string.sc_hint))
    }

    @Test fun scheduleWhileReading() {
        show { ScheduleContent(null, reading = true, failed = false, canRead = false, onRead = {}, onSelect = {}) }
        shown(str(R.string.sc_reading))
    }

    @Test fun scheduleReadingFailed() {
        show { ScheduleContent(null, reading = false, failed = true, canRead = false, onRead = {}, onSelect = {}) }
        shown(str(R.string.sc_failed))
    }

    @Test fun scheduleWithShizukuWaitsForTheTap() {
        show { ScheduleContent(null, reading = false, failed = false, canRead = true, onRead = {}, onSelect = {}) }
        shown(str(R.string.sc_shizuku))
        shownPart(str(R.string.sc_read_now))
    }

    @Test fun scheduleWithNothingPlanned() {
        val plan = Schedule.Plan(NOW, NOW until NOW + 8 * 3_600_000L, null, null, emptyList())
        show { ScheduleContent(plan, reading = false, failed = false, canRead = false, onRead = {}, onSelect = {}) }
        shown(str(R.string.sc_quiet))
    }

    // Last night

    @Test fun nightWithoutRecordSaysWhy() {
        val result = NightCheck.Result(NightCheck.Verdict.NOT_ENOUGH_DATA, NightCheck.Reason.NO_RECORD)
        val report = NightReport(LocalDate.of(2025, 10, 9), result, listOf(NightCheck.Entry(LocalDate.of(2025, 10, 9), result)), appsGrant = false)
        show { NightCheckCard(report, onPro = {}) }
        shown(str(R.string.nc_title_none))
        shown(str(R.string.nc_reason_no_record))
    }

    // Helpers

    private fun session(seconds: Long) = BatterySession(
        charging = false, startMillis = NOW - seconds * 1000, endMillis = NOW, startLevel = 0.6, endLevel = 0.5,
        energyMwh = 1500.0, peakMw = 4000.0, screenOnSeconds = seconds / 2.0, screenOffSeconds = seconds / 2.0, maxTempC = 32.0,
    )

    private fun passport(granted: Boolean) = BatteryPassport(
        madeMillis = null, firstUseMillis = null, monthsInUse = null, cyclesPerMonth = null, policy = null,
        part = null, sohPercent = null, capacityLevel = null, verdict = null, statsGranted = granted,
    )

    private fun estimate(days: Long, mah: Double) = CapacityEstimate(NOW + days * 86_400_000L, mah, 0.2, 0.9)

    @androidx.compose.runtime.Composable
    private fun rails(rates: Map<String, Double>?, enough: Boolean) = PowerRailContent(
        options = listOf(PowerWindow.HOUR, PowerWindow.TODAY), window = PowerWindow.HOUR, onChoose = {},
        rates = rates, coveredMs = 0L, enough = enough, drawMw = null, fine = false, canGetExact = false, onPro = {},
    )

}
