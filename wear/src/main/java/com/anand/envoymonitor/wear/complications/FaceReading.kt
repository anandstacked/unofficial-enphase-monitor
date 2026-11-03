package com.anand.envoymonitor.wear.complications

import com.anand.envoymonitor.data.isFresh
import com.anand.envoymonitor.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class FaceMetric { VOLTAGE, POWER, DAY, STATUS }
data class FaceReading(val text: String, val title: String, val description: String, val expiresAt: Long, val reportedAt: Long)

fun compactPower(watts: Double): String = when {
    kotlin.math.abs(watts) < 1000 -> String.format(Locale.US, "%.0fW", watts)
    kotlin.math.abs(watts) < 100000 -> String.format(Locale.US, "%.1fkW", watts / 1000)
    else -> "HIGH"
}

fun faceReading(envelope: SolarEnvelope?, metric: FaceMetric, now: Long, zone: ZoneId): FaceReading? {
    val snapshot = envelope?.snapshot ?: return null
    val meter = if (metric == FaceMetric.POWER) snapshot.production else snapshot.grid
    if (metric == FaceMetric.DAY) {
        val energy = reportedDayEnergy(snapshot, zone) ?: return null
        if (energy.reportDate != Instant.ofEpochSecond(now).atZone(zone).toLocalDate()) return null
        val value = if (energy.kwh < 100) String.format(Locale.US, "%.1fkWh", energy.kwh) else "HIGH"
        return FaceReading(value, "INV DAY", "Inverter-reported ${energy.kwh} kilowatt hours for ${energy.reportDate}",
            energy.reportDate.plusDays(1).atStartOfDay(zone).toEpochSecond(), snapshot.inverters.minOf { it.timestamp })
    }
    if (meter == null || !isFresh(meter.timestamp, now, 600)) return null
    val source = if (envelope.source == ReadingSource.PHONE) "Phone" else "Watch Wi-Fi"
    val value = when (metric) {
        FaceMetric.VOLTAGE -> meter.voltage?.let { String.format(Locale.US, "%.1fV", it) } ?: return null
        FaceMetric.POWER -> meter.power?.let(::compactPower) ?: return null
        FaceMetric.STATUS -> source
        else -> return null
    }
    val voltage = meter.voltage
    val low = envelope.lowVoltage
    val high = envelope.highVoltage
    val warning = if (metric == FaceMetric.STATUS && low != null && high != null && voltage != null) {
        when { voltage < low -> "Low V"; voltage > high -> "High V"; else -> source }
    } else source
    return FaceReading(if (metric == FaceMetric.STATUS) warning else value,
        if (metric == FaceMetric.VOLTAGE) "GRID" else if (metric == FaceMetric.POWER) "SOLAR" else "AGE",
        "$value, via $source. Gateway report ${Instant.ofEpochSecond(meter.timestamp)}", meter.timestamp + 600, meter.timestamp)
}
