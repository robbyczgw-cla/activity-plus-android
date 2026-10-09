package xyz.activityplus.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.activityplus.android.core.ChargerTestRun.State

class ChargerTestTest {
    @Test fun averagesPeakVoltageAndCurrentOverThirtySeconds() {
        val r = ChargerTestRun(0, 0.42, 31.0)
        assertEquals(State.RUNNING, r.add(0, true, 10_000.0, 4.0, 2500.0)) // the start snapshot is not counted
        for (sec in 1..29) assertEquals(State.RUNNING, r.add(sec * 1000L, true, 10_000.0, 4.0, 2500.0))
        assertNull(r.result())
        assertEquals(State.DONE, r.add(30_000, true, 20_000.0, 4.2, 4500.0))
        val m = r.result()!!
        assertEquals(30, m.seconds)
        assertEquals((29 * 10_000.0 + 20_000.0) / 30, m.avgMw, 1e-6)
        assertEquals(20_000.0, m.peakMw, 1e-9)
        assertEquals((29 * 4.0 + 4.2) / 30, m.avgVoltageV, 1e-9)
        assertEquals((29 * 2500.0 + 4500.0) / 30, m.avgCurrentMa, 1e-9)
        assertEquals(0.42, m.startLevel, 1e-9)
        assertEquals(31.0, m.startTempC, 1e-9)
        assertEquals(30_000L, m.timeMillis)
    }

    @Test fun repeatedSnapshotsCountOnce() {
        val r = ChargerTestRun(0, 0.5, 30.0, durationSeconds = 6, minSamples = 5)
        for (sec in 1..5) { r.add(sec * 1000L, true, 5_000.0, 4.0, 1250.0); r.add(sec * 1000L, true, 99_000.0, 4.0, 1250.0) }
        r.add(6_000, true, 5_000.0, 4.0, 1250.0)
        assertEquals(5_000.0, r.result()!!.avgMw, 1e-9)
        assertEquals(5_000.0, r.result()!!.peakMw, 1e-9)
    }

    @Test fun stopsWhenChargingStops() {
        val r = ChargerTestRun(0, 0.5, 30.0)
        r.add(1000, true, 5_000.0, 4.0, 1250.0)
        assertEquals(State.ABORTED, r.add(2000, false, -300.0, 4.0, -75.0))
        // Nothing changes afterwards.
        assertEquals(State.ABORTED, r.add(31_000, true, 5_000.0, 4.0, 1250.0))
        assertNull(r.result())
    }

    @Test fun noCurrentReadingMeansNoResult() {
        val r = ChargerTestRun(0, 0.5, 30.0)
        for (sec in 1..30) r.add(sec * 1000L, true, null, 4.0, null)
        assertEquals(State.DONE, r.state)
        assertNull(r.result())
    }

    @Test fun tooFewSamplesMeanNoResult() {
        val r = ChargerTestRun(0, 0.5, 30.0)
        r.add(10_000, true, 5_000.0, 4.0, 1250.0)
        r.add(31_000, true, 5_000.0, 4.0, 1250.0)
        assertEquals(State.DONE, r.state)
        assertNull(r.result())
    }

    @Test fun elapsedIsClamped() {
        val r = ChargerTestRun(1_000, 0.5, 30.0)
        assertEquals(0, r.elapsedSeconds(500))
        assertEquals(12, r.elapsedSeconds(13_400))
        assertEquals(30, r.elapsedSeconds(99_000))
    }
}
