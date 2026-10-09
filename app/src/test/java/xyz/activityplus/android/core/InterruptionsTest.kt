package xyz.activityplus.android.core

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.Interruptions.Exit
import xyz.activityplus.android.core.Interruptions.LiveWindows
import xyz.activityplus.android.core.Interruptions.LiveWindows.Window
import xyz.activityplus.android.core.Interruptions.Maker

class InterruptionsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun onlySystemKillsCount() {
        val system = listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
        )
        val notSystem = listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_SIGNALED,
        )
        val exits = (system + notSystem).map { Exit(it, now - 1000) }
        assertEquals(system.size, Interruptions.count(exits, now).count)
    }

    @Test fun onlyTheLastWeekCounts() {
        val exits = listOf(
            Exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 6 * day),
            Exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 8 * day),
            Exit(ApplicationExitInfo.REASON_LOW_MEMORY, now + day),
        )
        val c = Interruptions.count(exits, now)
        assertEquals(1, c.count)
        assertEquals(now - 6 * day, c.latestMillis)
    }

    @Test fun latestIsTheNewestCountedKill() {
        val exits = listOf(
            Exit(ApplicationExitInfo.REASON_OTHER, now - 3 * day),
            Exit(ApplicationExitInfo.REASON_CRASH, now - day),
            Exit(ApplicationExitInfo.REASON_OTHER, now - 2 * day),
        )
        val c = Interruptions.count(exits, now)
        assertEquals(2, c.count)
        assertEquals(now - 2 * day, c.latestMillis)
    }

    @Test fun killsWhileLiveWasOffDoNotCount() {
        val exits = listOf(
            Exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - day),
            Exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 2 * day),
        )
        val c = Interruptions.count(exits, now) { it == now - day }
        assertEquals(1, c.count)
        assertEquals(0, Interruptions.count(emptyList(), now).count)
        assertNull(Interruptions.count(emptyList(), now).latestMillis)
    }

    @Test fun makersFromManufacturer() {
        assertEquals(Maker.VIVO, Interruptions.maker("vivo"))
        assertEquals(Maker.XIAOMI, Interruptions.maker("Xiaomi"))
        assertEquals(Maker.XIAOMI, Interruptions.maker("Redmi"))
        assertEquals(Maker.XIAOMI, Interruptions.maker("POCO"))
        assertEquals(Maker.SAMSUNG, Interruptions.maker("samsung"))
        assertEquals(Maker.ONEPLUS, Interruptions.maker("OnePlus"))
        assertEquals(Maker.OPPO, Interruptions.maker("OPPO"))
        assertEquals(Maker.REALME, Interruptions.maker("realme"))
        assertEquals(Maker.HUAWEI, Interruptions.maker("HUAWEI"))
        assertEquals(Maker.HONOR, Interruptions.maker(" HONOR "))
        assertEquals(Maker.OTHER, Interruptions.maker("Google"))
    }

    @Test fun liveWindowsOpenAndCloseWithTheSetting() {
        var w = LiveWindows.sync(emptyList(), live = true, now = now)
        assertEquals(listOf(Window(now)), w)
        // Already on: nothing changes.
        assertEquals(w, LiveWindows.sync(w, live = true, now = now + day))
        w = LiveWindows.sync(w, live = false, now = now + day)
        assertEquals(listOf(Window(now, now + day)), w)
        // Still off: nothing changes.
        assertEquals(w, LiveWindows.sync(w, live = false, now = now + 2 * day))
        w = LiveWindows.sync(w, live = true, now = now + 3 * day)
        assertEquals(listOf(Window(now, now + day), Window(now + 3 * day)), w)
    }

    @Test fun liveWindowsAnswerForAGivenTime() {
        val w = listOf(Window(now, now + day), Window(now + 3 * day))
        assertTrue(LiveWindows.wasOn(w, now))
        assertTrue(LiveWindows.wasOn(w, now + day))
        assertFalse(LiveWindows.wasOn(w, now + 2 * day))
        assertTrue(LiveWindows.wasOn(w, now + 10 * day))
        assertFalse(LiveWindows.wasOn(w, now - 1))
    }

    @Test fun liveWindowsForgetWhatIsOlderThanAWeek() {
        val old = listOf(Window(now - 10 * day, now - 9 * day), Window(now - 3 * day, now - 2 * day))
        val kept = LiveWindows.sync(old, live = false, now = now)
        assertEquals(listOf(Window(now - 3 * day, now - 2 * day)), kept)
    }

    @Test fun liveWindowsSurviveStorage() {
        val w = listOf(Window(now, now + day), Window(now + 3 * day))
        assertEquals(w, LiveWindows.decode(LiveWindows.encode(w)))
        assertEquals(emptyList<Window>(), LiveWindows.decode(null))
        assertEquals(emptyList<Window>(), LiveWindows.decode(""))
        assertEquals(listOf(Window(now)), LiveWindows.decode("$now:;garbage"))
    }
}
