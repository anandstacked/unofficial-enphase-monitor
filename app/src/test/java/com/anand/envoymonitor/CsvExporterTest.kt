package com.anand.envoymonitor

import com.anand.envoymonitor.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class CsvExporterTest {
    @Test fun pagingExportsEverySourceAndUsesUtcWithEscapedCells() = runBlocking {
        val rows = (1L..2005L).map { id -> Sample(id, "gateway", if (id % 2 == 0L) "inverter" else "meter:net-consumption",
            "device\"quoted", "voltage", 172.3, "V", 1791219481, 1791219485) } +
            Sample(2006, "gateway", "outside-window", "device", "voltage", 999.0, "V", 1791220001, 1791220001)
        val dao = object : SampleDao {
            override suspend fun insert(samples: List<Sample>) = Unit
            override suspend fun prune(cutoff: Long) = Unit
            override fun chart(gateway: String, source: String, metric: String, start: Long, device: String): Flow<List<Sample>> = flowOf(emptyList())
            override fun chartDevices(gateway: String, source: String, metric: String): Flow<List<String>> = flowOf(emptyList())
            override fun chartStatistics(gateway: String, source: String, metric: String, device: String, start: Long, end: Long): Flow<ChartStatistics> = flowOf(ChartStatistics(0, null, null, null))
            override fun chartPoints(gateway: String, source: String, metric: String, device: String, start: Long, end: Long, bucketSeconds: Long): Flow<List<Sample>> = flowOf(emptyList())
            override fun voltagePowerCorrelation(gateway: String, start: Long, end: Long): Flow<List<CorrelationReading>> = flowOf(emptyList())
            override suspend fun nearestChartSample(gateway: String, source: String, metric: String, device: String, start: Long, end: Long, target: Long): Sample? = null
            override suspend fun exportPage(gateway: String, start: Long, end: Long, afterId: Long) = rows.filter { it.id > afterId && it.gateway == gateway && it.measuredAt in start..end }.take(1000)
            override suspend fun exportCount(gateway: String, start: Long, end: Long) = rows.count { it.gateway == gateway && it.measuredAt in start..end }
            override fun count(gateway: String): Flow<Int> = flowOf(rows.size)
            override fun devices(gateway: String): Flow<List<String>> = flowOf(emptyList())
        }
        val writer = StringWriter()
        CsvExporter.write(dao, writer, "gateway", 1791219000, 1791220000)
        val text = writer.toString()
        assertEquals(2006, text.split("\r\n").count { it.isNotEmpty() })
        assertTrue(text.contains("\"device\"\"quoted\""))
        assertTrue(text.contains("2026-10-05T16:58:01Z"))
        assertTrue(text.contains("\"meter:net-consumption\""))
        assertTrue(text.contains("\"inverter\""))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("outside-window"))
    }
}
