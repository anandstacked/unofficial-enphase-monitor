package com.anand.envoymonitor.wear.complications

import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.sync.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FaceReadingTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-06T17:00:00Z").epochSecond
    private val grid = MeterReading("1", "1", "net-consumption", now, 230.0, 1.0, 50.0, 500.0, null, null, null, null, null)
    private val production = grid.copy(id = "2", type = "production", power = 3400.0)
    private val inverters = (0..8).map { i -> InverterReading("41000000000$i", "1", now, true, true, 300.0, 230.0, 40.0,
        1.0, 7.0, 50.0, 33.0, 3177.777, null, null, null, null, null) }
    private fun envelope() = SolarEnvelope(gatewaySerial = "987654321098", snapshot = Snapshot(listOf(grid, production), inverters, now),
        publishedAt = now, collecting = false)
    @Test fun voltageAndPowerExpireAtMeasurementTimeNotDeliveryTime() {
        assertEquals(now + 600, faceReading(envelope(), FaceMetric.VOLTAGE, now, zone)!!.expiresAt)
        assertNull(faceReading(envelope().copy(publishedAt = now + 700), FaceMetric.VOLTAGE, now + 700, zone))
        assertNull(faceReading(envelope(), FaceMetric.POWER, now + 601, zone))
    }
    @Test fun oldDayEnergyDisappearsAtMidnightEvenWithoutNewDelivery() {
        val day = faceReading(envelope(), FaceMetric.DAY, now, zone)!!
        assertEquals("28.6kWh", day.text)
        assertNull(faceReading(envelope(), FaceMetric.DAY, day.expiresAt, zone))
        assertNotNull(faceReading(envelope(), FaceMetric.DAY, now + 1200, zone))
    }
    @Test fun statusUsesConfiguredLimitsAndActualSource() {
        val warning = envelope().copy(lowVoltage = 235.0, highVoltage = 250.0, source = ReadingSource.WATCH_WIFI)
        assertEquals("Low V", faceReading(warning, FaceMetric.STATUS, now, zone)!!.text)
        assertTrue(faceReading(warning, FaceMetric.STATUS, now, zone)!!.description.contains("Watch Wi-Fi"))
        assertNull(faceReading(warning, FaceMetric.STATUS, now + 601, zone))
    }
    @Test fun faceFieldsRespectShortTextBudgetAndMissingData() {
        for (metric in listOf(FaceMetric.VOLTAGE, FaceMetric.POWER, FaceMetric.DAY)) assertTrue(faceReading(envelope(), metric, now, zone)!!.text.length <= 7)
        assertEquals("3.4kW", compactPower(3400.0))
        assertEquals("608W", compactPower(608.0))
        assertNull(faceReading(null, FaceMetric.VOLTAGE, now, zone))
        assertNull(faceReading(envelope().copy(snapshot = Snapshot()), FaceMetric.DAY, now, zone))
    }
}
