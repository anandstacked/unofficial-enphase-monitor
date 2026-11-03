package com.anand.envoymonitor

import com.anand.envoymonitor.data.*
import org.junit.Assert.*
import org.junit.Test

class ChartSeriesTest {
    @Test fun nativeRendererKeepsEveryAlreadyReducedPointAndSplitsGaps() {
        val rows = (1L..1000L).map { sample(it * 60, 230.0) } + listOf(sample(70000, 231.0))
        val segments = chartSegments(rows, 600, reduce = false)
        assertEquals(2, segments.size)
        assertEquals(1000, segments.first().size)
        assertEquals(1001, segments.sumOf { it.size })
    }
    private fun sample(ts: Long, value: Double) = Sample(gateway = "test", source = "meter:net-consumption", device = "L1", metric = "voltage", value = value, unit = "V", measuredAt = ts, collectedAt = ts)
    @Test fun reductionPreservesExtremaWithoutCreatingArtificialGaps() {
        val rows = (1L..5000L).map { sample(it * 60, if (it == 224L) 170.0 else if (it == 3500L) 270.0 else 230.0) }
        val segments = chartSegments(rows, 600)
        assertEquals(1, segments.size)
        assertTrue(segments.single().size < rows.size)
        assertEquals(170.0, segments.single().minOf { it.value }, 0.0)
        assertEquals(270.0, segments.single().maxOf { it.value }, 0.0)
        assertEquals(rows.first(), segments.single().first())
        assertEquals(rows.last(), segments.single().last())
    }
    @Test fun realOutagesStaySeparate() {
        val rows = listOf(sample(1000, 230.0), sample(1300, 231.0), sample(4000, 220.0), sample(4300, 221.0))
        val segments = chartSegments(rows, 600)
        assertEquals(2, segments.size)
        assertEquals(2, segments.first().size)
        assertEquals(4000, segments.last().first().measuredAt)
    }
}
