package com.anand.envoymonitor.sync

import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.network.*
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateException
import java.time.Instant
import java.time.ZoneId

class SolarProtocolTest {
    private val serial = "987654321098"
    private val time = Instant.parse("2026-10-06T06:00:00Z").epochSecond
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun meter(timestamp: Long = time) = MeterReading("704643584", "1778385425", "net-consumption", timestamp,
        230.0, 3.7, 49.9, 608.0, 640.0, -100.0, 0.95, 1000.0, 2000.0)
    private fun inverter(index: Int, timestamp: Long = time) = InverterReading("41000000000$index", "1", timestamp,
        true, true, 300.0, 231.0, 40.0, 1.3, 7.5, 50.0, 33.0, 1000.0, 2000.0, 8000.0, 200000.0, null, null)
    private fun envelope(timestamp: Long = time, collecting: Boolean = true, source: ReadingSource = ReadingSource.PHONE) =
        SolarEnvelope(gatewaySerial = serial, snapshot = Snapshot(listOf(meter(timestamp)), (0..8).map { inverter(it, timestamp) }, time),
            publishedAt = time, collecting = collecting, source = source)
    private fun provision(settings: ConnectionSettings = ConnectionSettings(host = "192.168.1.100", serial = serial, fingerprint = "a".repeat(64))) =
        WatchProvision(requestId = "ae8d3723-bc13-4c88-a737-2d881771604a", settings = settings, token = "headerpart.payloadpart.signaturepart")

    @Test fun snapshotRoundTripKeepsUnitsFlagsAndTimestamp() {
        val original = envelope()
        val decoded = SolarCodec.decode(SolarCodec.encode(original))!!
        assertEquals(original, decoded)
        assertTrue(decoded.snapshot.inverters.all { it.active && it.modGone })
        assertFalse(String(SolarCodec.encode(original)).contains("token"))
    }
    @Test fun malformedOrUnknownDeliveryIsIgnored() {
        val encoded = String(SolarCodec.encode(envelope()))
        assertNull(SolarCodec.decode(encoded.replace("\"version\":1", "\"version\":2").toByteArray()))
        assertNull(SolarCodec.decode(encoded.replace("\"PHONE\"", "\"OTHER\"").toByteArray()))
        assertNull(SolarCodec.decode(encoded.replace("\"voltage\":230.0", "\"voltage\":\"NaN\"").toByteArray()))
        assertNull(SolarCodec.decode("{}".toByteArray()))
        assertNull(SolarCodec.decode(ByteArray(131073)))
    }
    @Test fun duplicateInvertersAndInvalidLimitsAreRejected() {
        val e = envelope()
        assertNull(SolarCodec.decode(SolarCodec.encode(e.copy(snapshot = e.snapshot.copy(inverters = List(9) { inverter(0) })))))
        assertNull(SolarCodec.decode(SolarCodec.encode(e.copy(lowVoltage = 250.0, highVoltage = 180.0))))
    }
    @Test fun onlyAnActiveRecentPhoneCollectorAvoidsDirectPolling() {
        assertTrue(phoneUsable(envelope(), time))
        assertFalse(phoneUsable(envelope(collecting = false), time))
        assertFalse(phoneUsable(envelope(), time + 121))
        assertFalse(phoneUsable(envelope(time - 601), time))
        assertFalse(phoneUsable(envelope(time + 61), time))
    }
    @Test fun newerDirectReadingWinsUntilPhoneCatchesUp() {
        val direct = envelope(time + 10, source = ReadingSource.WATCH_WIFI)
        assertSame(direct, selectEnvelope(envelope(), direct, time + 10))
        val phone = envelope(time + 10)
        assertSame(phone, selectEnvelope(phone, direct, time + 10))
    }
    @Test fun deliveryRejectsRegressionsAndOtherGatewaysButAllowsPartialReadings() {
        val previous = envelope()
        assertFalse(acceptDelivery(previous, envelope(time - 10)))
        assertFalse(acceptDelivery(previous, envelope().copy(publishedAt = time - 1)))
        assertFalse(acceptDelivery(previous, envelope().copy(gatewaySerial = "123456789012")))
        assertTrue(acceptDelivery(previous, envelope().copy(snapshot = envelope().snapshot.copy(meters = emptyList()))))
    }
    @Test fun dayTotalRequiresNineDistinctReportsFromOneLocalDate() {
        assertEquals(9.0, reportedDayEnergy(envelope().snapshot, zone)!!.kwh, 0.001)
        val snap = envelope().snapshot
        assertNull(reportedDayEnergy(snap.copy(inverters = snap.inverters.take(8)), zone))
        assertNull(reportedDayEnergy(snap.copy(inverters = List(9) { inverter(0) }), zone))
        assertNull(reportedDayEnergy(snap.copy(inverters = snap.inverters.dropLast(1) + inverter(8, time - 86400)), zone))
        assertNull(reportedDayEnergy(snap.copy(inverters = snap.inverters.dropLast(1) + inverter(8).copy(todayWh = null)), zone))
    }
    @Test fun nearbyProvisionRoundTripValidatesAddressPinAndToken() {
        assertNotNull(SolarCodec.decodeProvision(SolarCodec.encodeProvision(provision())))
        assertNull(SolarCodec.decodeProvision(SolarCodec.encodeProvision(provision(ConnectionSettings(host = "8.8.8.8", fingerprint = "a".repeat(64))))))
        assertNull(SolarCodec.decodeProvision(SolarCodec.encodeProvision(provision(ConnectionSettings(fingerprint = "unpaired")))))
        val bytes = String(SolarCodec.encodeProvision(provision())).replace("headerpart.payloadpart.signaturepart", "Bearer bad token")
        assertNull(SolarCodec.decodeProvision(bytes.toByteArray()))
        assertFalse(provision().toString().contains("headerpart"))
    }
    @Test fun privateAddressValidationBlocksPathsAndMalformedHosts() {
        listOf("192.168.1.100", "10.0.0.2", "172.16.0.1", "172.31.255.254").forEach { assertTrue(isPrivateIpv4(it)) }
        listOf("172.32.0.1", "192.168.18.999", "127.0.0.1", "192.168.1.100/", "https://192.168.1.100", "+10.0.0.1").forEach { assertFalse(isPrivateIpv4(it)) }
    }
    @Test fun pinnedLeafMustMatchExactCertificate() {
        val cert = "certificate fixture".toByteArray()
        val pin = certificateFingerprint(cert)
        requireCertificatePin(cert, pin)
        requireCertificatePin(cert, pin.uppercase())
        for (bad in listOf("a".repeat(64), "")) {
            try { requireCertificatePin(cert, bad); fail("Mismatched pin accepted") }
            catch (_: CertificateException) { }
        }
    }
}
