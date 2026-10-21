package xyz.activityplus.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.core.WindowLayout
import xyz.activityplus.android.service.MonitorService
import xyz.activityplus.android.ui.components.NavLabel
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.screens.AppsScreen
import xyz.activityplus.android.ui.screens.BatteryScreen
import xyz.activityplus.android.ui.screens.DiagnosisScreen
import xyz.activityplus.android.ui.screens.HardwareScreen
import xyz.activityplus.android.ui.screens.HistoryScreen
import xyz.activityplus.android.ui.screens.MediaScreen
import xyz.activityplus.android.ui.screens.OnboardingScreen
import xyz.activityplus.android.ui.screens.OverviewScreen
import xyz.activityplus.android.ui.screens.ProScreen
import xyz.activityplus.android.ui.screens.SensorLogScreen
import xyz.activityplus.android.ui.screens.SettingsScreen
import xyz.activityplus.android.ui.screens.StorageScreen
import xyz.activityplus.android.ui.screens.WeeklyReportScreen
import xyz.activityplus.android.ui.theme.ActivityPlusTheme
import xyz.activityplus.android.ui.theme.LocalSurfaces

/** DIAGNOSIS, PRO, WEEKLY, MEDIA and SENSORS have no tab of their own; the overview, apps, battery and settings screens open them. */
enum class Tab { OVERVIEW, APPS, BATTERY, HISTORY, HARDWARE, DIAGNOSIS, PRO, WEEKLY, STORAGE, MEDIA, SENSORS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Extras for screenshot runs: adb shell am start -n .../.MainActivity -e tab APPS [--ez settings true]
        val startTab = tabOf(intent) ?: Tab.OVERVIEW
        val startSettings = intent.getBooleanExtra("settings", false)
        setContent {
            ActivityPlusTheme {
                val settings = rememberSettings()
                // Surface, not a plain Box: it also sets the content color, so text outside the
                // Scaffold (onboarding, settings) is light in dark mode.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!settings.onboarded) {
                        OnboardingScreen(
                            onRequestNotifications = ::requestNotifications,
                            onDone = {
                                app.prefs.update { it.copy(onboarded = true) }
                                if (app.prefs.current.live) MonitorService.start(this@MainActivity)
                            },
                        )
                    } else {
                        Main(startTab, startSettings, requestedTab) { requestedTab = null }
                    }
                }
            }
        }
        if (app.prefs.current.onboarded && app.prefs.current.live) runCatching { MonitorService.start(this) }
    }

    private val app get() = ActivityPlusApp.instance

    // A notification tapped while the app is open (singleTask) arrives here, not in onCreate.
    private var requestedTab by mutableStateOf<Tab?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = tabOf(intent)
    }

    private fun tabOf(intent: Intent): Tab? = intent.getStringExtra("tab")?.let { n -> Tab.entries.find { it.name == n } }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
private fun Main(startTab: Tab, startSettings: Boolean, requestedTab: Tab?, onRequestHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var showSettings by rememberSaveable { mutableStateOf(startSettings) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            showSettings = false
            onRequestHandled()
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && tab != Tab.OVERVIEW) { tab = if (tab == Tab.MEDIA) Tab.STORAGE else Tab.OVERVIEW }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onWeekly = { tab = Tab.WEEKLY; showSettings = false })
        return
    }
    val surfaces = LocalSurfaces.current
    val items = listOf(
        Triple(Tab.OVERVIEW, Icons.Outlined.Dashboard, R.string.tab_overview),
        Triple(Tab.APPS, Icons.Outlined.Apps, R.string.tab_apps),
        Triple(Tab.BATTERY, Icons.Outlined.BatteryChargingFull, R.string.tab_battery),
        Triple(Tab.HISTORY, Icons.Outlined.History, R.string.tab_history),
        Triple(Tab.HARDWARE, Icons.Outlined.DeveloperBoard, R.string.tab_hardware),
    )
    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.OVERVIEW -> OverviewScreen(onOpen = { tab = it }, onSettings = { showSettings = true })
            Tab.APPS -> AppsScreen(onPro = { tab = Tab.PRO })
            Tab.BATTERY -> BatteryScreen(onPro = { tab = Tab.PRO }, onSelfCost = { showSettings = true }, onOpen = { tab = it })
            Tab.HISTORY -> HistoryScreen()
            Tab.HARDWARE -> HardwareScreen(onOpen = { tab = it })
            Tab.DIAGNOSIS -> DiagnosisScreen(onOpen = { tab = it })
            Tab.PRO -> ProScreen()
            Tab.STORAGE -> StorageScreen()
            Tab.WEEKLY -> WeeklyReportScreen()
            Tab.MEDIA -> MediaScreen()
            Tab.SENSORS -> SensorLogScreen(onPro = { tab = Tab.PRO })
        }
    }

    if (WindowLayout.useRail(LocalConfiguration.current.screenWidthDp.toFloat())) {
        // Landscape, tablets, foldables: the bar moves to the left edge, where the thumbs are and
        // where it takes no height from a screen that has little of it.
        val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = surfaces.card, windowInsets = sides.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)) {
                // Scrolls: five items are taller than a phone held sideways.
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items.forEach { (t, icon, label) ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label), maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = surfaces.cardRaised),
                        )
                    }
                }
            }
            // The rail already keeps clear of the system bar and cutout on its side; the pages pad the rest.
            Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(sides.only(WindowInsetsSides.Start))) { content() }
        }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = surfaces.card) {
                items.forEach { (t, icon, label) ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { NavLabel(stringResource(label)) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = surfaces.cardRaised),
                    )
                }
            }
        },
    ) { padding ->
        // Consumed, so the pages do not pad the status bar a second time (Scaffold already left room for it).
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}
