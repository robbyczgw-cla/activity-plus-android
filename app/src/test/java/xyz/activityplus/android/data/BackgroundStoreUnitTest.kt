package xyz.activityplus.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.activityplus.android.pro.BackgroundDelta
import java.time.ZoneId

/** The battery report's unit is confirmed by two reports and applied to the stored history exactly once. */
@RunWith(RobolectricTestRunner::class)
class BackgroundStoreUnitTest {
    private val zone = ZoneId.of("UTC")
    private lateinit var store: BackgroundStore

    // The store is a process-wide singleton: each test starts from an empty one.
    @Before fun open() {
        store = BackgroundStore.get(RuntimeEnvironment.getApplication())
        store.clear()
    }

    private fun saveDay(mah: Double) {
        val t = 1_792_000_000_000L
        val interval = BackgroundDelta.Interval(t - 3_600_000, t, false, listOf(BackgroundDelta.Usage("a.app", mah, 0, 0)), 0.0)
        store.save(BackgroundDelta.Snapshot(t, 3_600_000, null, emptyMap(), 0.0), interval, zone)
    }

    @Test fun oneReportDoesNotConfirmTwoDo() {
        assertNull(store.noteUnit(3_600_000.0))
        assertEquals(3_600_000.0, store.noteUnit(3_600_000.0)!!, 0.0)
        // A different reading later starts over, the confirmed unit stays until two agree on another.
        assertEquals(3_600_000.0, store.noteUnit(1_000.0)!!, 0.0)
        assertEquals(1_000.0, store.noteUnit(1_000.0)!!, 0.0)
    }

    @Test fun nothingDetectedChangesNothing() {
        assertNull(store.noteUnit(null))
        assertNull(store.confirmedUnit())
    }

    @Test fun theHistoryIsConvertedOnceNoMatterHowOftenOrFromWhere() {
        saveDay(285_078_419.0)
        store.applyUnit(3_600_000.0)
        store.applyUnit(3_600_000.0) // a second reading, or "Measure now" at the same time
        assertEquals(285_078_419.0 / 3_600_000.0, store.maxDayMah(), 1e-6)
        assertNull(store.lastSnapshot())
    }

    @Test fun aLaterCorrectionConvertsByTheRatioOnly() {
        saveDay(3_600_000.0)
        store.applyUnit(3_600.0) // a wrong unit, later corrected
        store.applyUnit(3_600_000.0)
        assertEquals(1.0, store.maxDayMah(), 1e-9)
    }
}
