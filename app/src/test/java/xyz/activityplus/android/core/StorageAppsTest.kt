package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.StorageApps.Age
import xyz.activityplus.android.core.StorageApps.AgeUnit

class StorageAppsTest {
    private val day = StorageApps.DAY
    private val now = 1_800_000_000_000L
    private val gb = 1_000_000_000L

    private fun app(
        pkg: String,
        size: Long = gb,
        cache: Long = 0,
        lastUsedDaysAgo: Int? = 100,
        installedDaysAgo: Int = 300,
        system: Boolean = false,
    ) = PackageStorage(
        pkg, pkg.uppercase(), system, appBytes = size / 2, dataBytes = size - size / 2, cacheBytes = cache,
        lastUsedMillis = lastUsedDaysAgo?.let { now - it * day } ?: 0L,
        installedMillis = now - installedDaysAgo * day,
    )

    @Test fun otherIsWhatIsLeftOver() {
        val b = StorageApps.breakdown(total = 128 * gb, free = 28 * gb, apps = 40 * gb, images = 10 * gb, videos = 20 * gb, audio = 5 * gb)
        assertEquals(100 * gb, b.usedBytes)
        assertEquals(25 * gb, b.otherBytes)
    }

    @Test fun otherIsNeverNegative() {
        // Shared storage can be counted by both the app and the media totals.
        val b = StorageApps.breakdown(total = 100 * gb, free = 50 * gb, apps = 40 * gb, images = 10 * gb, videos = 10 * gb, audio = 0)
        assertEquals(0, b.otherBytes)
    }

    @Test fun nothingLeftIsNotNegativeEither() {
        val b = StorageApps.breakdown(total = 100, free = 120, apps = 0, images = 0, videos = 0, audio = 0)
        assertEquals(0, b.usedBytes)
        assertEquals(0, b.otherBytes)
    }

    @Test fun negativeInputsAreIgnored() {
        val b = StorageApps.breakdown(100, 50, apps = -5, images = 10, videos = 0, audio = 0)
        assertEquals(0, b.appBytes)
        assertEquals(40, b.otherBytes)
    }

    @Test fun cachesAreSortedAndLimited() {
        val apps = (1..15).map { app("a$it", cache = it * 10L) } + app("none", cache = 0)
        val c = StorageApps.caches(apps, userCacheBytes = null, top = 10)
        assertEquals(10, c.top.size)
        assertEquals("a15", c.top.first().pkg)
        assertEquals("a6", c.top.last().pkg)
        assertEquals((1..15).sumOf { it * 10L }, c.totalBytes)
    }

    @Test fun cacheTotalUsesTheUsersFigureWhenLarger() {
        val apps = listOf(app("a", cache = 100), app("b", cache = 50))
        assertEquals(500, StorageApps.caches(apps, 500).totalBytes)
        assertEquals(150, StorageApps.caches(apps, 120).totalBytes)
        assertEquals(150, StorageApps.caches(apps, null).totalBytes)
    }

    @Test fun cachesTieBreakByName() {
        val c = StorageApps.caches(listOf(app("b", cache = 5), app("a", cache = 5)), null)
        assertEquals(listOf("a", "b"), c.top.map { it.pkg })
    }

    @Test fun usedRecentlyIsNotUnused() {
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = 10), now, 30))
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = 59), now, 60))
    }

    @Test fun oldUseIsUnused() {
        assertTrue(StorageApps.isUnused(app("x", lastUsedDaysAgo = 61), now, 60))
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = 61), now, 90))
    }

    @Test fun noRecordAndInstalledEarlierIsUnused() {
        assertTrue(StorageApps.isUnused(app("x", lastUsedDaysAgo = null, installedDaysAgo = 200), now, 90))
    }

    @Test fun noRecordButInstalledRecentlyIsNot() {
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = null, installedDaysAgo = 20), now, 30))
        // Installed 70 days ago and never opened: unused for 60 days, not yet for 90.
        assertTrue(StorageApps.isUnused(app("x", lastUsedDaysAgo = null, installedDaysAgo = 70), now, 60))
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = null, installedDaysAgo = 70), now, 90))
    }

    @Test fun oldRecordOfAFreshInstallDoesNotCount() {
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = 200, installedDaysAgo = 5), now, 30))
    }

    @Test fun systemAppsAreNeverUnused() {
        assertFalse(StorageApps.isUnused(app("x", lastUsedDaysAgo = null, system = true), now, 30))
    }

    @Test fun unusedAreSortedBySizeWithTotal() {
        val list = listOf(
            app("small", size = 1 * gb), app("big", size = 3 * gb), app("recent", size = 9 * gb, lastUsedDaysAgo = 1),
            app("sys", size = 9 * gb, system = true), app("mid", size = 2 * gb),
        )
        val u = StorageApps.unused(list, now, 60)
        assertEquals(listOf("big", "mid", "small"), u.apps.map { it.pkg })
        assertEquals(6 * gb, u.totalBytes)
    }

    @Test fun unusedEmptyHasZeroTotal() {
        val u = StorageApps.unused(emptyList(), now, 60)
        assertTrue(u.apps.isEmpty())
        assertEquals(0, u.totalBytes)
    }

    @Test fun longerChoicesNeverGrowTheList() {
        val list = listOf(40, 70, 100, 130).map { app("a$it", lastUsedDaysAgo = it) }
        val sizes = StorageApps.UNUSED_CHOICES.map { StorageApps.unused(list, now, it).apps.size }
        assertEquals(listOf(4, 3, 2), sizes)
    }

    @Test fun ageIsInDaysThenMonths() {
        assertNull(StorageApps.age(0, now))
        assertEquals(Age(AgeUnit.DAYS, 45), StorageApps.age(now - 45 * day, now))
        assertEquals(Age(AgeUnit.DAYS, 59), StorageApps.age(now - 59 * day - 1000, now))
        assertEquals(Age(AgeUnit.MONTHS, 2), StorageApps.age(now - 60 * day, now))
        assertEquals(Age(AgeUnit.MONTHS, 3), StorageApps.age(now - 95 * day, now))
    }

    @Test fun ageOfAFutureTimeIsZeroDays() {
        assertEquals(Age(AgeUnit.DAYS, 0), StorageApps.age(now + day, now))
    }
}
