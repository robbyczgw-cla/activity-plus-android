package xyz.activityplus.android.ui

import org.junit.Test
import xyz.activityplus.android.R
import xyz.activityplus.android.core.StorageApps
import xyz.activityplus.android.core.StorageForecast
import xyz.activityplus.android.core.Thermal
import xyz.activityplus.android.ui.Fixtures.NOW
import xyz.activityplus.android.ui.screens.DayCard
import xyz.activityplus.android.ui.screens.HeatContent
import xyz.activityplus.android.ui.screens.HeatReport
import xyz.activityplus.android.ui.screens.NightCard
import xyz.activityplus.android.ui.screens.NotReportedNote
import xyz.activityplus.android.ui.screens.NowCard
import xyz.activityplus.android.ui.screens.SensorLogData
import xyz.activityplus.android.ui.screens.SensorLogHint
import xyz.activityplus.android.ui.screens.StorageAppsData
import xyz.activityplus.android.ui.screens.StorageCachesContent
import xyz.activityplus.android.ui.screens.StorageGrowthCard
import xyz.activityplus.android.ui.screens.StorageGrowthData
import xyz.activityplus.android.ui.screens.StorageUnusedContent

/** Hardware, Storage and the sensor log with missing data. */
class MoreCardsTest : CardTest() {

    // Hardware

    @Test fun hardwareNamesWhatThePhoneDoesNotReport() {
        show { NotReportedNote(listOf(str(R.string.hw_cameras), str(R.string.hw_sensors))) }
        shown(str(R.string.es_hw_not_reported, str(R.string.hw_cameras) + ", " + str(R.string.hw_sensors)))
    }

    @Test fun heatWithoutEpisodesOrUsageAccess() {
        show { HeatContent(emptyHeat(), access = false, grant = false, fahrenheit = false, onPro = {}) }
        shown(str(R.string.th_none))
        shown(str(R.string.th_usage_hint))
    }

    @Test fun heatWithAccessButNoAppLongEnough() {
        show { HeatContent(emptyHeat(), access = true, grant = false, fahrenheit = false, onPro = {}) }
        shown(str(R.string.th_apps_empty))
    }

    // Storage

    @Test fun storageGrowthOnTheFirstDay() {
        show { StorageGrowthCard(growth(snapshots = 1, hasApps = false)) }
        shown(str(R.string.sgrowth_too_few))
        shown(str(R.string.sgrowth_starts_today))
        shown(str(R.string.sgrowth_no_access))
    }

    @Test fun storageGrowthWithHistoryShowsTheChart() {
        show { StorageGrowthCard(growth(snapshots = 10, hasApps = true).copy(forecast = StorageForecast(StorageForecast.Kind.STABLE))) }
        shown(str(R.string.sgrowth_stable))
        shown(str(R.string.sgrowth_used_30))
        absent(str(R.string.sgrowth_starts_today))
    }

    @Test fun cachesWithoutUsageAccess() {
        show { StorageCachesContent(access = false, data = null) }
        shown(str(R.string.storageapps_needs_access))
    }

    @Test fun cachesWhileLoading() {
        show { StorageCachesContent(access = true, data = null) }
        shown(str(R.string.storageapps_loading))
    }

    @Test fun cachesWhenNoAppHasOne() {
        show { StorageCachesContent(access = true, data = StorageAppsData(emptyList(), null)) }
        shown(str(R.string.storageapps_cache_empty))
    }

    @Test fun unusedWithoutUsageAccess() {
        show { StorageUnusedContent(access = false, data = null) }
        shown(str(R.string.storageapps_needs_access))
    }

    @Test fun unusedWhenEveryAppWasOpened() {
        show { StorageUnusedContent(access = true, data = StorageAppsData(emptyList(), null)) }
        shown(str(R.string.storageapps_unused_none, StorageApps.DEFAULT_UNUSED_DAYS))
    }

    // Sensor log

    @Test fun sensorLogWithoutGrantPointsToPro() {
        show { SensorLogHint(onPro = {}) }
        shown(str(R.string.sl_hint))
    }

    @Test fun sensorLogRightNowNothing() {
        show { NowCard(emptyList()) }
        shown(str(R.string.sl_now_none))
    }

    @Test fun sensorLogNightBeforeTheLogRan() {
        show { NightCard(sensorData(since = null), emptyList()) }
        shown(str(R.string.sl_night_nodata))
    }

    @Test fun sensorLogNightQuiet() {
        show { NightCard(sensorData(since = NOW - 3 * 86_400_000L), emptyList()) }
        shown(str(R.string.sl_night_none))
    }

    @Test fun sensorLogDayBeforeTheFirstReading() {
        show { DayCard(emptyList(), sensorData(since = null)) }
        shown(str(R.string.sl_day_empty))
    }

    @Test fun sensorLogDayQuiet() {
        show { DayCard(emptyList(), sensorData(since = NOW - 86_400_000L)) }
        shown(str(R.string.sl_day_quiet))
    }

    // Helpers

    private fun emptyHeat() = HeatReport(emptyList(), Thermal.summary(emptyList()), emptyList())

    private fun growth(snapshots: Int, hasApps: Boolean) = StorageGrowthData(
        snapshots = snapshots,
        forecast = StorageForecast(StorageForecast.Kind.TOO_FEW),
        used = List(30) { if (it >= 30 - snapshots) 50e9 + it * 1e8 else null },
        totalBytes = 128_000_000_000L,
        hasApps = hasApps,
        month = emptyList(),
        monthFromMillis = null,
        week = emptyMap(),
        unusual = emptySet(),
    )

    private fun sensorData(since: Long?) = SensorLogData(
        live = emptyList(), day = emptyList(), night = emptyList(),
        nightWindow = (NOW - 10 * 3_600_000L)..(NOW - 3 * 3_600_000L),
        since = since, system = emptySet(),
    )
}
