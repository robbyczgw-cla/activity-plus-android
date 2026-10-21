package xyz.activityplus.android.ui

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.activityplus.android.R
import xyz.activityplus.android.core.WeeklyReport
import xyz.activityplus.android.ui.Fixtures.snapshot
import xyz.activityplus.android.ui.screens.ChargeLiveSetting
import xyz.activityplus.android.ui.screens.HistoryScreen
import xyz.activityplus.android.ui.screens.LanguageRow
import xyz.activityplus.android.ui.screens.MediaScreen
import xyz.activityplus.android.ui.screens.SensorLogScreen
import xyz.activityplus.android.ui.screens.SettingsScreen
import xyz.activityplus.android.ui.screens.WeeklyReportScreen
import xyz.activityplus.android.widget.WidgetUpdater

/**
 * Whole screens as a new user sees them: empty stores, no grant, no usage access, no media access.
 * The loaders run for real against the empty databases, so this is the default setup, not a mock.
 */
class FreshInstallTest : CardTest() {

    @Test fun historyWithoutData() {
        show { HistoryScreen() }
        waitShown(str(R.string.history_empty))
        waitShown(str(R.string.es_history_drain_none))
    }

    @Test fun weeklyReportWithoutAWeek() {
        show { WeeklyReportScreen() }
        waitShown(str(R.string.weekly_not_enough_title))
        shown(str(R.string.weekly_not_enough_body, WeeklyReport.MIN_DAYS))
    }

    @Test fun sensorLogWithoutGrant() {
        show { SensorLogScreen(onPro = {}) }
        shown(str(R.string.sl_hint))
    }

    @Test fun mediaWithoutAccessExplainsWhy() {
        show { MediaScreen() }
        shown(str(R.string.media_why))
    }

    @Test fun mediaWithAccessButNoFiles() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        )
        show { MediaScreen() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.media_none)).fetchSemanticsNodes().size == 3 }
        // Largest videos, folders, old and large: each says it found nothing.
        rule.onAllNodesWithText(str(R.string.media_none)).assertCountEquals(3)
        shown(str(R.string.media_dup_intro))
    }

    @Test fun settingsWithoutGrantExplainTheMissingSwitch() {
        show { SettingsScreen(onBack = {}, onWeekly = {}) }
        shown(str(R.string.es_bg_setting_grant))
    }

    @Test fun panelWidgetFooterBeforeAnyMeasurement() {
        assertEquals(str(R.string.es_widget_nothing_today), WidgetUpdater.footer(context, snapshot()))
    }

    @Config(sdk = [32])
    @Test fun liveUpdateSwitchOnOldAndroidSaysWhy() {
        show { ChargeLiveSetting(on = false) }
        shown(str(R.string.es_live_needs_android16))
    }

    @Config(sdk = [32])
    @Test fun languageRowOnOldAndroidSaysWhy() {
        show { LanguageRow() }
        shown(str(R.string.es_lang_needs_android13))
    }
}
