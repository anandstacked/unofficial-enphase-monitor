package com.anand.envoymonitor

import com.anand.envoymonitor.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ChartWindowTest {
    @Test fun fingerReleaseAndInvalidPinchesNeverCrashTheWindow() {
        val bounds = ChartWindow(1791000000, 1791003600)
        assertEquals(bounds, bounds.zoom(1.0, Double.NaN, bounds))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertEquals(bounds, bounds.zoom(invalid, .5, bounds))
            assertEquals(bounds, visibleChartWindow(bounds, invalid, invalid))
        }
        assertEquals(bounds, bounds.pan(Double.NaN, bounds))
        assertEquals(bounds, visibleChartWindow(bounds, 20.0, Double.NaN))
    }
    @Test fun nativeOffsetsKeepEpochPrecisionAndStayInsideBounds() {
        val bounds = ChartWindow(1791000000, 1791003600)
        assertEquals(ChartWindow(1791000021, 1791000121), visibleChartWindow(bounds, 20.75, 120.75))
        assertEquals(bounds, visibleChartWindow(bounds, -100.0, 5000.0))
        assertEquals(ChartWindow(bounds.end - 1, bounds.end), visibleChartWindow(bounds, 5000.0, 6000.0))
    }
    @Test fun autoUsesSixtyConfiguredRefreshIntervals() {
        assertEquals(300L, autoChartSeconds(5))
        assertEquals(3600L, autoChartSeconds(60))
        assertEquals(216000L, autoChartSeconds(3600))
    }
    @Test fun zoomKeepsTappedTimeAnchoredAndClampsBothEdges() {
        val bounds = ChartWindow(1000, 4600)
        assertEquals(ChartWindow(1450, 3250), bounds.zoom(2.0, .25, bounds))
        assertEquals(ChartWindow(1000, 4600), bounds.zoom(.01, .9, bounds))
        assertEquals(60L, bounds.zoom(1000.0, .5, bounds).seconds)
    }
    @Test fun panNeverLeavesAvailableWindowOrChangesDuration() {
        val bounds = ChartWindow(1000, 4600)
        val view = ChartWindow(2200, 3400)
        assertEquals(ChartWindow(1000, 2200), view.pan(100.0, bounds))
        assertEquals(ChartWindow(3400, 4600), view.pan(-100.0, bounds))
        assertEquals(ChartWindow(1900, 3100), view.pan(.25, bounds))
    }
    @Test fun bucketCannotHideAnOutageLongerThanGapThreshold() {
        assertEquals(600L, chartBucketSeconds(ChartWindow(1000, 1000 + 30 * 86400), 600))
        assertEquals(1200L, chartBucketSeconds(ChartWindow(1000, 1000 + 30 * 86400), 1200))
        assertEquals(1L, chartBucketSeconds(ChartWindow(1000, 1060), 600))
    }
    @Test fun customTimeRoundTripsInPhoneZoneWithoutTreatingItAsUtc() {
        val zone = ZoneId.of("Asia/Kolkata")
        val epoch = parseChartTime("2026-10-05 22:28", zone)
        assertEquals("2026-10-05T16:58:00Z", java.time.Instant.ofEpochSecond(epoch).toString())
        assertEquals("2026-10-05 22:28", formatChartTime(epoch, zone))
        assertThrows(java.time.DateTimeException::class.java) { parseChartTime("2026-02-30 12:00", zone) }
    }
    @Test fun customRangeRejectsReversedFutureAndExpiredHistory() {
        val now = 1791300000L
        assertEquals(ChartWindow(now - 3600, now), validateCustomWindow(now - 3600, now, now))
        assertThrows(IllegalArgumentException::class.java) { validateCustomWindow(now, now - 1, now) }
        assertThrows(IllegalArgumentException::class.java) { validateCustomWindow(now - 3600, now + 1, now) }
        assertThrows(IllegalArgumentException::class.java) { validateCustomWindow(now - 30 * 86400 - 1, now, now) }
    }
}
