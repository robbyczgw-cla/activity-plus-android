package xyz.activityplus.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import xyz.activityplus.android.core.Diagnosis

/** Deep links into the system screens that actually fix things. */
object Actions {
    fun open(context: Context, fix: Diagnosis.Fix, pkg: String? = null) {
        when (fix) {
            Diagnosis.Fix.STORAGE_SETTINGS -> launch(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            Diagnosis.Fix.APP_INFO -> if (pkg != null) appInfo(context, pkg)
            Diagnosis.Fix.BATTERY_SAVER -> launch(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            Diagnosis.Fix.BATTERY_USAGE -> launch(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            Diagnosis.Fix.DEVELOPER_OPTIONS -> launch(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            Diagnosis.Fix.NONE -> Unit
        }
    }

    fun appInfo(context: Context, pkg: String) =
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun usageAccess(context: Context) {
        // Some phones open the app's own page with the package URI; fall back to the list.
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (!launch(context, direct)) launch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** Android 13+: the system's per-app language page. */
    fun appLanguage(context: Context) =
        launch(context, Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun website(context: Context) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://activityplus.xyz")))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
