package com.anand.envoymonitor.data

data class MeterConfig(val id: String, val type: String, val enabled: Boolean, val phaseCount: Int, val status: String)
data class MeterReading(
    val id: String, val channelId: String, val type: String, val timestamp: Long,
    val voltage: Double?, val current: Double?, val frequency: Double?, val power: Double?,
    val apparentPower: Double?, val reactivePower: Double?, val powerFactor: Double?,
    val deliveredWh: Double?, val receivedWh: Double?
)
data class InverterReading(
    val serial: String, val channelId: String, val timestamp: Long, val active: Boolean,
    val modGone: Boolean, val watts: Double?, val acVoltage: Double?, val dcVoltage: Double?,
    val acCurrent: Double?, val dcCurrent: Double?, val frequency: Double?, val temperature: Double?,
    val todayWh: Double?, val yesterdayWh: Double?, val weekWh: Double?, val lifetimeWh: Double?,
    val flags: String?, val signal: Double?
)
data class Snapshot(
    val meters: List<MeterReading> = emptyList(), val inverters: List<InverterReading> = emptyList(),
    val receivedAt: Long = 0, val messages: List<String> = emptyList()
) {
    val grid get() = meters.firstOrNull { it.type == "net-consumption" }
    val production get() = meters.firstOrNull { it.type == "production" }
    val homeWatts: Double? get() {
        val p = production ?: return null
        val g = grid ?: return null
        return if (p.timestamp == g.timestamp && p.power != null && g.power != null) p.power + g.power else null
    }
}
enum class VoltageBand { NORMAL, LOW, HIGH }
fun voltageBand(voltage: Double, low: Double, high: Double) = when {
    voltage < low -> VoltageBand.LOW
    voltage > high -> VoltageBand.HIGH
    else -> VoltageBand.NORMAL
}
fun isFresh(timestamp: Long, now: Long, maxAgeSeconds: Long): Boolean =
    timestamp > 0 && timestamp <= now + 60 && now - timestamp <= maxAgeSeconds

data class AlertState(val timestamp: Long = 0, val band: VoltageBand? = null)
class VoltageAlertTracker(initial: AlertState = AlertState()) {
    private var lastTimestamp = initial.timestamp
    private var lastBand: VoltageBand? = initial.band
    val state get() = AlertState(lastTimestamp, lastBand)
    fun reset() { lastTimestamp = 0; lastBand = null }
    fun accept(reading: MeterReading?, now: Long, low: Double?, high: Double?): VoltageBand? {
        if (reading == null || low == null || high == null || low >= high || reading.voltage == null) return null
        if (!isFresh(reading.timestamp, now, 600) || reading.timestamp <= lastTimestamp) return null
        lastTimestamp = reading.timestamp
        val band = voltageBand(reading.voltage, low, high)
        val changed = band != lastBand
        lastBand = band
        return if (changed && band != VoltageBand.NORMAL) band else null
    }
}
