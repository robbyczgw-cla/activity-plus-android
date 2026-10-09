package xyz.activityplus.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xyz.activityplus.android.service.MonitorService
import xyz.activityplus.android.ui.rememberSettings
import xyz.activityplus.android.ui.screens.AppsScreen
import xyz.activityplus.android.ui.screens.BatteryScreen
import xyz.activityplus.android.ui.screens.DiagnosisScreen
import xyz.activityplus.android.ui.screens.HardwareScreen
import xyz.activityplus.android.ui.screens.HistoryScreen
import xyz.activityplus.android.ui.screens.OnboardingScreen
import xyz.activityplus.android.ui.screens.OverviewScreen
import xyz.activityplus.android.ui.screens.ProScreen
import xyz.activityplus.android.ui.screens.SettingsScreen
import xyz.activityplus.android.ui.theme.ActivityPlusTheme
import xyz.activityplus.android.ui.theme.LocalSurfaces

/** DIAGNOSIS and PRO have no tab of their own; the overview, apps and battery screens open them. */
enum class Tab { OVERVIEW, APPS, BATTERY, HISTORY, HARDWARE, DIAGNOSIS, PRO }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Extras for screenshot runs: adb shell am start -n .../.MainActivity -e tab APPS [--ez settings true]
        val startTab = intent.getStringExtra("tab")?.let { n -> Tab.entries.find { it.name == n } } ?: Tab.OVERVIEW
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
                        Main(startTab, startSettings)
                    }
                }
            }
        }
        if (app.prefs.current.onboarded && app.prefs.current.live) runCatching { MonitorService.start(this) }
    }

    private val app get() = ActivityPlusApp.instance

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Main(startTab: Tab, startSettings: Boolean) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var showSettings by rememberSaveable { mutableStateOf(startSettings) }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && tab != Tab.OVERVIEW) { tab = Tab.OVERVIEW }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }
    val surfaces = LocalSurfaces.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = surfaces.card) {
                val items = listOf(
                    Triple(Tab.OVERVIEW, Icons.Outlined.Dashboard, R.string.tab_overview),
                    Triple(Tab.APPS, Icons.Outlined.Apps, R.string.tab_apps),
                    Triple(Tab.BATTERY, Icons.Outlined.BatteryChargingFull, R.string.tab_battery),
                    Triple(Tab.HISTORY, Icons.Outlined.History, R.string.tab_history),
                    Triple(Tab.HARDWARE, Icons.Outlined.DeveloperBoard, R.string.tab_hardware),
                )
                items.forEach { (t, icon, label) ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = surfaces.cardRaised),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.OVERVIEW -> OverviewScreen(onOpen = { tab = it }, onSettings = { showSettings = true })
                Tab.APPS -> AppsScreen(onPro = { tab = Tab.PRO })
                Tab.BATTERY -> BatteryScreen(onPro = { tab = Tab.PRO })
                Tab.HISTORY -> HistoryScreen()
                Tab.HARDWARE -> HardwareScreen()
                Tab.DIAGNOSIS -> DiagnosisScreen()
                Tab.PRO -> ProScreen()
            }
        }
    }
}
