package xyz.activityplus.android.data

import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Packages with a VPN service. They run a service all day on purpose, so Diagnosis skips them. */
object VpnApps {
    fun packages(context: Context): Set<String> =
        // Visible to us because the manifest queries android.net.VpnService.
        context.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .toSet()
}
