package com.anand.envoymonitor

import com.anand.envoymonitor.data.parseRefreshSeconds
import com.anand.envoymonitor.monitor.PollingSchedule
import org.junit.Assert.*
import org.junit.Test

class PollingScheduleTest {
    @Test fun intervalInputAcceptsWholeSecondsAndRejectsInvalidValues() {
        assertEquals(1, parseRefreshSeconds("1"))
        assertEquals(60, parseRefreshSeconds(" 60 "))
        assertEquals(3600, parseRefreshSeconds("3600"))
        listOf("", "0", "-1", "3601", "1.5", "seconds", "999999999999").forEach { assertNull(parseRefreshSeconds(it)) }
    }
    @Test fun shorteningAnActiveWaitCanMakeTheNextCheckDueImmediately() {
        val schedule = PollingSchedule(1000)
        assertEquals(40000, schedule.remainingMillis(21000, 60, 0))
        assertEquals(0, schedule.remainingMillis(21000, 10, 0))
    }
    @Test fun extendingAWaitPreservesTimeAlreadyElapsed() {
        val schedule = PollingSchedule(1000)
        assertEquals(5000, schedule.remainingMillis(6000, 10, 0))
        assertEquals(115000, schedule.remainingMillis(6000, 120, 0))
    }
    @Test fun failuresBackOffWithoutPollingFasterThanTheSelectedInterval() {
        val schedule = PollingSchedule(1000)
        assertEquals(30000, schedule.remainingMillis(1000, 1, 1))
        assertEquals(120000, schedule.remainingMillis(1000, 60, 3))
        assertEquals(300000, schedule.remainingMillis(1000, 1, 20))
        assertEquals(600000, schedule.remainingMillis(1000, 600, 5))
        assertEquals(1000, schedule.remainingMillis(1000, 1, 0))
    }
    @Test fun elapsedAndExpiredWaitsNeverProduceNegativeDelays() {
        val schedule = PollingSchedule(1000)
        assertEquals(60000, schedule.remainingMillis(500, 60, 0))
        assertEquals(0, schedule.remainingMillis(62000, 60, 0))
    }
}
