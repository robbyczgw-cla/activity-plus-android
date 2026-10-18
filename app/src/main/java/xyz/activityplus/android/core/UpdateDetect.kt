package xyz.activityplus.android.core

/**
 * Which apps were updated, from what PackageManager says now against what was seen last time.
 * Android keeps only the current version and the time of the last update, so the old version of an
 * update is only known if Activity+ saw the app before; for updates before that, the version is
 * "unknown" but the time is exact. Pure, so the rules are unit-tested.
 */
object UpdateDetect {
    /** What PackageManager says about one package now (or what was seen the last time). */
    data class Installed(
        val pkg: String,
        val versionName: String?,
        val versionCode: Long,
        val firstInstallMillis: Long,
        val lastUpdateMillis: Long,
    )

    /** One update: the old version is null when it was not seen before. */
    data class Event(
        val pkg: String,
        val oldName: String?,
        val oldCode: Long?,
        val newName: String?,
        val newCode: Long,
        val timeMillis: Long,
    )

    /** Packages seen for the first time: updates this recent are taken from PackageManager's own update time. */
    const val BACKFILL_DAYS = 28

    private fun sameVersion(a: Installed, b: Installed) = a.versionCode == b.versionCode && a.versionName == b.versionName

    /**
     * The updates found by comparing [known] (the last scan, by package) with [now]. A package seen
     * before counts when its version changed. One not seen before counts when its last update time is
     * later than its install time and recent: that is an update from before Activity+ looked.
     * A fresh install is not an update, and neither is an update to the same version (split APKs,
     * a re-install): the numbers would have no reason to change.
     */
    fun detect(known: Map<String, Installed>, now: List<Installed>, nowMillis: Long): List<Event> {
        val oldest = nowMillis - BACKFILL_DAYS * 86_400_000L
        val out = ArrayList<Event>()
        for (n in now) {
            val k = known[n.pkg]
            if (k != null) {
                if (!sameVersion(k, n)) {
                    // PackageManager's time is the real one; the scan time only if it reports none.
                    val time = if (n.lastUpdateMillis > 0) n.lastUpdateMillis else nowMillis
                    out += Event(n.pkg, k.versionName, k.versionCode, n.versionName, n.versionCode, time)
                }
            } else if (n.lastUpdateMillis > n.firstInstallMillis && n.lastUpdateMillis >= oldest) {
                out += Event(n.pkg, null, null, n.versionName, n.versionCode, n.lastUpdateMillis)
            }
        }
        return out
    }
}
