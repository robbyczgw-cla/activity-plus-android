package xyz.activityplus.android.ui

import org.junit.Test
import xyz.activityplus.android.R
import xyz.activityplus.android.core.AppCost
import xyz.activityplus.android.core.SinceUpdate
import xyz.activityplus.android.core.UsageCost
import xyz.activityplus.android.ui.screens.AfterUpdates
import xyz.activityplus.android.ui.screens.AfterUpdatesCard
import xyz.activityplus.android.ui.screens.AppDetailCoverageContent
import xyz.activityplus.android.ui.screens.CostPerHourContent

/** The Apps page and the app detail with missing data. */
class AppsCardsTest : CardTest() {

    @Test fun costPerHourWithTooFewMinutesSaysHowManyAreNeeded() {
        show { CostPerHourContent(emptyList()) }
        shown(str(R.string.cost_title))
        shown(str(R.string.es_cost_none, (UsageCost.MIN_SECONDS / 60).toInt()))
    }

    @Test fun costPerHourListsMeasuredApps() {
        show { CostPerHourContent(listOf(AppCost("com.example.maps", 3600.0, 2500.0, 12.0))) }
        shown(str(R.string.cost_note))
        absent(str(R.string.es_cost_none, (UsageCost.MIN_SECONDS / 60).toInt()))
    }

    @Test fun afterUpdatesWithNoRecentUpdateSaysSo() {
        show { AfterUpdatesCard(AfterUpdates(emptyList(), emptyList(), waiting = false)) {} }
        shown(str(R.string.su_title))
        shown(str(R.string.es_updates_none, SinceUpdate.RECENT_DAYS))
    }

    @Test fun afterUpdatesWithOnlyFreshUpdatesSaysTheyNeedDays() {
        show { AfterUpdatesCard(AfterUpdates(emptyList(), emptyList(), waiting = true)) {} }
        shown(str(R.string.su_waiting))
        absent(str(R.string.es_updates_none, SinceUpdate.RECENT_DAYS))
    }

    @Test fun appDetailWithoutGrantSaysWhichSectionsNeedIt() {
        show { AppDetailCoverageContent(granted = false, onPro = {}) }
        shown(str(R.string.es_detail_sections))
        shown(str(R.string.es_detail_grant))
        shownPart(str(R.string.es_set_up))
    }

    @Test fun appDetailWithGrantOnlyExplainsTheMissingSections() {
        show { AppDetailCoverageContent(granted = true, onPro = {}) }
        shown(str(R.string.es_detail_sections))
        absent(str(R.string.es_detail_grant))
    }
}
