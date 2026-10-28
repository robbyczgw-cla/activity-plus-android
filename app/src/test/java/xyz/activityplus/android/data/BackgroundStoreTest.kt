package xyz.activityplus.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundStoreTest {
    @Test fun apportionsIntervalsAtBothWindowEdges() {
        assertEquals(0.25, BackgroundStore.overlapShare(0, 400, 300, 500), 0.001)
        assertEquals(0.5, BackgroundStore.overlapShare(0, 400, 100, 300), 0.001)
        assertEquals(0.0, BackgroundStore.overlapShare(0, 100, 100, 300), 0.001)
        assertEquals(0.0, BackgroundStore.overlapShare(100, 100, 0, 300), 0.001)
    }
}
