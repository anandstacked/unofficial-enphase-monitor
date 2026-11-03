package com.anand.envoymonitor.sync

import com.anand.envoymonitor.data.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId

object SolarPaths {
    const val SNAPSHOT = "/envoy/snapshot/v1"
    const val REQUEST = "/envoy/request/v1"
    const val PROVISION = "/envoy/provision/v1"
    const val ACK = "/envoy/provisioned/v1"
    const val PHONE_CAPABILITY = "envoy_phone_v1"
    const val WATCH_CAPABILITY = "envoy_watch_v1"
}
enum class ReadingSource { PHONE, WATCH_WIFI }
data class SolarEnvelope(
    val version: Int = 1, val gatewaySerial: String, val snapshot: Snapshot,
    val publishedAt: Long, val collecting: Boolean, val source: ReadingSource = ReadingSource.PHONE,
    val lowVoltage: Double? = null, val highVoltage: Double? = null
)
// Deliberately not a data class: toString must never print the credential.
class WatchProvision(val version: Int = 1, val requestId: String, val settings: ConnectionSettings, val token: String)
data class ProvisionAck(val requestId: String, val successful: Boolean)

object SolarCodec {
    private val gson = Gson()
    fun encode(envelope: SolarEnvelope): ByteArray = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): SolarEnvelope? = runCatching {
        require(bytes.size in 1..131072)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version").asInt == 1)
        val envelope = gson.fromJson(root, SolarEnvelope::class.java)
        require(envelope.gatewaySerial.matches(Regex("[0-9]{12}")))
        require(root.get("source")?.asString in ReadingSource.entries.map { it.name } && envelope.publishedAt > 0)
        val snapshot = envelope.snapshot
        require(snapshot.receivedAt >= 0 && snapshot.meters.size <= 6 && snapshot.inverters.size <= 50 && snapshot.messages.size <= 20)
        require(snapshot.inverters.map { it.serial }.distinct().size == snapshot.inverters.size)
        require(snapshot.messages.all { it.length <= 1024 })
        snapshot.meters.forEach { m ->
            require(m.id.isNotBlank() && m.channelId.isNotBlank() && m.type.isNotBlank() && m.timestamp >= 0)
            require(listOf(m.voltage, m.current, m.frequency, m.power, m.apparentPower, m.reactivePower, m.powerFactor, m.deliveredWh, m.receivedWh).all { it == null || it.isFinite() })
        }
        snapshot.inverters.forEach { i ->
            require(i.serial.matches(Regex("[0-9]{12}")) && i.channelId.isNotBlank() && i.timestamp >= 0)
            require(listOf(i.watts, i.acVoltage, i.dcVoltage, i.acCurrent, i.dcCurrent, i.frequency, i.temperature, i.todayWh, i.yesterdayWh, i.weekWh, i.lifetimeWh, i.signal).all { it == null || it.isFinite() })
        }
        require(validLimits(envelope.lowVoltage, envelope.highVoltage))
        envelope
    }.getOrNull()
    fun encodeProvision(provision: WatchProvision): ByteArray = gson.toJson(provision).toByteArray(Charsets.UTF_8)
    fun decodeProvision(bytes: ByteArray): WatchProvision? = runCatching {
        require(bytes.size in 1..24576)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version").asInt == 1)
        val requestId = root.get("requestId").asString
        require(requestId.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")))
        val settings = gson.fromJson(root.get("settings"), ConnectionSettings::class.java)
        require(settings.serial.matches(Regex("[0-9]{12}")) && settings.fingerprint.matches(Regex("[a-fA-F0-9]{64}")))
        require(isPrivateIpv4(settings.host) && validLimits(settings.lowVoltage, settings.highVoltage))
        val token = root.get("token").asString
        require(token.length in 20..16384 && token.matches(Regex("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")))
        WatchProvision(requestId = requestId, settings = settings, token = token)
    }.getOrNull()
    fun encodeAck(ack: ProvisionAck): ByteArray = gson.toJson(ack).toByteArray(Charsets.UTF_8)
    fun decodeAck(bytes: ByteArray): ProvisionAck? = runCatching {
        require(bytes.size in 1..512)
        gson.fromJson(bytes.toString(Charsets.UTF_8), ProvisionAck::class.java).also { require(it.requestId.length == 36) }
    }.getOrNull()
}
fun validLimits(low: Double?, high: Double?) = (low == null && high == null) ||
    (low != null && high != null && low.isFinite() && high.isFinite() && low > 0 && low < high)
fun isPrivateIpv4(host: String): Boolean {
    if (!host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return false
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && (octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))
}
fun phoneUsable(phone: SolarEnvelope?, now: Long): Boolean = phone != null && phone.collecting &&
    isFresh(phone.publishedAt, now, 120) && phone.snapshot.grid?.let { isFresh(it.timestamp, now, 600) } == true
fun selectEnvelope(phone: SolarEnvelope?, direct: SolarEnvelope?, now: Long): SolarEnvelope? {
    if (phone == null) return direct
    if (direct == null || phone.gatewaySerial != direct.gatewaySerial) return phone
    val phoneTime = phone.snapshot.grid?.timestamp ?: 0
    val directTime = direct.snapshot.grid?.timestamp ?: 0
    return if (phoneUsable(phone, now) && phoneTime >= directTime) phone else if (directTime > phoneTime) direct else phone
}
fun acceptDelivery(previous: SolarEnvelope?, incoming: SolarEnvelope): Boolean = previous == null ||
    (previous.gatewaySerial == incoming.gatewaySerial && incoming.publishedAt >= previous.publishedAt &&
        (incoming.snapshot.grid == null || previous.snapshot.grid == null ||
            incoming.snapshot.grid!!.timestamp >= previous.snapshot.grid!!.timestamp))

data class ReportDayEnergy(val kwh: Double, val reportDate: java.time.LocalDate)
fun reportedDayEnergy(snapshot: Snapshot, zone: ZoneId): ReportDayEnergy? {
    if (snapshot.inverters.size != 9 || snapshot.inverters.map { it.serial }.distinct().size != 9 ||
        snapshot.inverters.any { it.timestamp <= 0 || it.todayWh == null || !it.todayWh.isFinite() || it.todayWh < 0 }) return null
    val dates = snapshot.inverters.map { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.distinct()
    if (dates.size != 1) return null
    return ReportDayEnergy(snapshot.inverters.sumOf { it.todayWh!! / 1000 }, dates.single())
}
