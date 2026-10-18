package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.UpdateDetect.Installed

class UpdateDetectTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun app(
        pkg: String = "com.chat", name: String? = "2.24.20", code: Long = 224, installed: Long = now - 200 * day, updated: Long = installed,
    ) = Installed(pkg, name, code, installed, updated)

    @Test fun recentUpdateOfAnUnseenAppTakesPackageManagersTime() {
        val updated = now - 5 * day
        val events = UpdateDetect.detect(emptyMap(), listOf(app(updated = updated)), now)
        val e = events.single()
        assertEquals("com.chat", e.pkg)
        assertEquals(updated, e.timeMillis)
        assertEquals("2.24.20", e.newName)
        assertEquals(224L, e.newCode)
        // Nothing is known about the version before.
        assertEquals(null, e.oldName)
        assertEquals(null, e.oldCode)
    }

    @Test fun aFreshInstallIsNotAnUpdate() {
        val t = now - 3 * day
        assertTrue(UpdateDetect.detect(emptyMap(), listOf(app(installed = t, updated = t)), now).isEmpty())
    }

    @Test fun anOldUpdateIsLeftOutOfTheBackfill() {
        val old = app(updated = now - (UpdateDetect.BACKFILL_DAYS + 1) * day)
        assertTrue(UpdateDetect.detect(emptyMap(), listOf(old), now).isEmpty())
    }

    @Test fun aVersionChangeKeepsTheOldVersion() {
        val before = app(name = "2.24.19", code = 223)
        val after = app(name = "2.24.20", code = 224, updated = now - 2 * day)
        val e = UpdateDetect.detect(mapOf(before.pkg to before), listOf(after), now).single()
        assertEquals("2.24.19", e.oldName)
        assertEquals(223L, e.oldCode)
        assertEquals("2.24.20", e.newName)
        assertEquals(now - 2 * day, e.timeMillis)
    }

    @Test fun theSameVersionIsNotAnUpdateEvenIfTheTimeMoved() {
        val before = app()
        val reinstalled = app(updated = now - day)
        assertTrue(UpdateDetect.detect(mapOf(before.pkg to before), listOf(reinstalled), now).isEmpty())
    }

    @Test fun aNewNameWithTheSameCodeCounts() {
        val before = app(name = "2.24.20")
        val after = app(name = "2.24.20.1", updated = now - day)
        assertEquals(1, UpdateDetect.detect(mapOf(before.pkg to before), listOf(after), now).size)
    }

    @Test fun anAppInstalledAfterTheFirstScanIsNotAnUpdate() {
        val known = app("com.old")
        val fresh = app("com.new", installed = now - day, updated = now - day)
        assertTrue(UpdateDetect.detect(mapOf(known.pkg to known), listOf(known, fresh), now).isEmpty())
    }

    @Test fun aMissingUpdateTimeFallsBackToTheScanTime() {
        val before = app(name = "1", code = 1)
        val after = app(name = "2", code = 2, installed = 0, updated = 0)
        assertEquals(now, UpdateDetect.detect(mapOf(before.pkg to before), listOf(after), now).single().timeMillis)
    }

    @Test fun onlyChangedAppsProduceEvents() {
        val a = app("a")
        val b = app("b")
        val b2 = app("b", name = "9", code = 9, updated = now - day)
        val events = UpdateDetect.detect(mapOf("a" to a, "b" to b), listOf(a, b2), now)
        assertEquals(listOf("b"), events.map { it.pkg })
    }
}
