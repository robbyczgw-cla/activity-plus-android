package xyz.activityplus.android.core

/** One installed app's storage and last use, as Android reports them. */
data class PackageStorage(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val appBytes: Long,
    /** Includes the cache, as StorageStats does. */
    val dataBytes: Long,
    val cacheBytes: Long,
    /** 0 when Android has no record of the app being opened. */
    val lastUsedMillis: Long,
    val installedMillis: Long,
) {
    val sizeBytes get() = appBytes + dataBytes
}

/** The pure parts of the storage page's breakdown, cache and unused-app sections. */
object StorageApps {
    const val DAY = 86_400_000L

    /** How far back last use is asked for; covers the longest choice with room to spare. */
    const val HISTORY_DAYS = 120
    val UNUSED_CHOICES = listOf(30, 60, 90)
    const val DEFAULT_UNUSED_DAYS = 60
    const val TOP_CACHES = 10

    // Breakdown

    data class Breakdown(
        val totalBytes: Long,
        val freeBytes: Long,
        val appBytes: Long,
        val imageBytes: Long,
        val videoBytes: Long,
        val audioBytes: Long,
        /** Whatever is used but not accounted for: the system itself, documents, downloads. Never negative. */
        val otherBytes: Long,
    ) {
        val usedBytes get() = (totalBytes - freeBytes).coerceAtLeast(0)
    }

    fun breakdown(total: Long, free: Long, apps: Long, images: Long, videos: Long, audio: Long): Breakdown {
        val used = (total - free).coerceAtLeast(0)
        val known = apps.coerceAtLeast(0) + images.coerceAtLeast(0) + videos.coerceAtLeast(0) + audio.coerceAtLeast(0)
        return Breakdown(
            total, free, apps.coerceAtLeast(0), images.coerceAtLeast(0), videos.coerceAtLeast(0), audio.coerceAtLeast(0),
            (used - known).coerceAtLeast(0),
        )
    }

    // Caches

    data class Caches(val totalBytes: Long, val top: List<PackageStorage>)

    /**
     * [userCacheBytes] is the whole user's cache when Android reports it; it also covers apps this app
     * cannot list, so the total is never smaller than the listed apps.
     */
    fun caches(apps: List<PackageStorage>, userCacheBytes: Long?, top: Int = TOP_CACHES): Caches {
        val withCache = apps.filter { it.cacheBytes > 0 }
        return Caches(
            totalBytes = maxOf(withCache.sumOf { it.cacheBytes }, userCacheBytes ?: 0L),
            top = withCache.sortedWith(compareByDescending<PackageStorage> { it.cacheBytes }.thenBy { it.label.lowercase() }).take(top),
        )
    }

    // Unused apps

    /**
     * Unused means not opened for [days] days. The later of last use and install counts, so an app with
     * no record is unused only when it was installed before the cutoff, and a fresh install is not.
     */
    fun isUnused(app: PackageStorage, now: Long, days: Int): Boolean {
        if (app.system) return false
        val cutoff = now - days * DAY
        return maxOf(app.lastUsedMillis, app.installedMillis) < cutoff
    }

    data class Unused(val apps: List<PackageStorage>, val totalBytes: Long)

    /** Biggest first. */
    fun unused(apps: List<PackageStorage>, now: Long, days: Int): Unused {
        val list = apps.filter { isUnused(it, now, days) }
            .sortedWith(compareByDescending<PackageStorage> { it.sizeBytes }.thenBy { it.label.lowercase() })
        return Unused(list, list.sumOf { it.sizeBytes })
    }

    enum class AgeUnit { DAYS, MONTHS }

    data class Age(val unit: AgeUnit, val count: Int)

    /** "45 days" up to two months, then whole months; null when there is no record. */
    fun age(lastUsedMillis: Long, now: Long): Age? {
        if (lastUsedMillis <= 0) return null
        val days = ((now - lastUsedMillis) / DAY).coerceAtLeast(0).toInt()
        return if (days < 60) Age(AgeUnit.DAYS, days) else Age(AgeUnit.MONTHS, days / 30)
    }
}
