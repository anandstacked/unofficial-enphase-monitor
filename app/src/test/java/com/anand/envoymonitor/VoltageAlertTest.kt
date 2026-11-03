package com.anand.envoymonitor

import com.anand.envoymonitor.data.*
import org.junit.Assert.*
import org.junit.Test

class VoltageAlertTest {
    private fun reading(timestamp: Long, volts: Double) = MeterReading("grid", "L1", "net-consumption", timestamp, volts, null, null, null, null, null, null, null, null)
    @Test fun cachedReadingsNeverRepeatNotificationsAndRecoveryRearms() {
        val tracker = VoltageAlertTracker()
        assertEquals(VoltageBand.LOW, tracker.accept(reading(1000, 172.0), 1001, 200.0, 250.0))
        assertNull(tracker.accept(reading(1000, 172.0), 1010, 200.0, 250.0))
        assertNull(tracker.accept(reading(1100, 173.0), 1101, 200.0, 250.0))
        assertNull(tracker.accept(reading(1200, 230.0), 1201, 200.0, 250.0))
        assertEquals(VoltageBand.LOW, tracker.accept(reading(1300, 180.0), 1301, 200.0, 250.0))
        assertEquals(VoltageBand.HIGH, tracker.accept(reading(1400, 260.0), 1401, 200.0, 250.0))
    }
    @Test fun staleMissingFutureOrDisabledReadingsCannotAlert() {
        val tracker = VoltageAlertTracker()
        assertNull(tracker.accept(reading(1000, 172.0), 1700, 200.0, 250.0))
        assertNull(tracker.accept(reading(3000, 172.0), 1700, 200.0, 250.0))
        assertNull(tracker.accept(reading(1700, 172.0), 1701, null, null))
        assertNull(tracker.accept(reading(1700, 172.0), 1701, 250.0, 200.0))
        assertNull(tracker.accept(null, 1701, 200.0, 250.0))
        assertEquals(VoltageBand.LOW, tracker.accept(reading(1700, 172.0), 1701, 200.0, 250.0))
    }
    @Test fun exactThresholdsAreWithinRange() {
        assertEquals(VoltageBand.NORMAL, voltageBand(200.0, 200.0, 250.0))
        assertEquals(VoltageBand.NORMAL, voltageBand(250.0, 200.0, 250.0))
    }
    @Test fun restoredStateSuppressesCachedAlertAfterProcessRestart() {
        val first = VoltageAlertTracker()
        assertEquals(VoltageBand.LOW, first.accept(reading(1000, 172.0), 1001, 200.0, 250.0))
        val restored = VoltageAlertTracker(first.state)
        assertNull(restored.accept(reading(1000, 172.0), 1002, 200.0, 250.0))
        assertNull(restored.accept(reading(1100, 173.0), 1101, 200.0, 250.0))
    }
}
