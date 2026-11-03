package com.anand.envoymonitor

import com.anand.envoymonitor.data.*
import org.junit.Assert.*
import org.junit.Test

class GatewayParserTest {
    private val configs = GatewayParser.meters("""[
        {"eid":704643328,"state":"enabled","measurementType":"production","phaseMode":"three","phaseCount":1,"meteringStatus":"normal"},
        {"eid":704643584,"state":"enabled","measurementType":"net-consumption","phaseMode":"three","phaseCount":1,"meteringStatus":"normal"}
    ]""")
    private fun fixture() = (1..9).joinToString(",", prefix = "{", postfix = "}") { index -> """
        "${553648384 + (index - 1) * 256}": {
            "devName":"pcu", "sn":"41000000000$index", "active":true, "modGone":true,
            "channels":[{
                "chanEid":${1627390000 + index},
                "wattHours":{"today":3000,"yesterday":2800,"week":18000},
                "watts":{"now":300},
                "lastReading":{"endDate":1791204472,"acVoltageINmV":230000,"dcVoltageINmV":42000,
                    "acCurrentInmA":200,"dcCurrentINmA":500,"acFrequencyINmHz":50000,
                    "channelTemp":33,"flags_hex":"0x0000000000000000","rssi":106},
                "lifetime":{"joulesProduced":720000000}
            }]
        }
    """ }
    @Test fun suppliedTelemetryRetainsAllNineEvenWithModGone() {
        val readings = GatewayParser.devices(fixture())
        assertEquals(9, readings.size)
        assertTrue(readings.all { it.active && it.modGone })
        val first = readings.first { it.serial == "410000000001" }
        assertEquals(230.0, first.acVoltage!!, .0001)
        assertEquals(42.0, first.dcVoltage!!, .0001)
        assertEquals(.2, first.acCurrent!!, .0001)
        assertEquals(.5, first.dcCurrent!!, .0001)
        assertEquals(50.0, first.frequency!!, .0001)
        assertEquals(200000.0, first.lifetimeWh!!, .0001)
        assertEquals(27.0, readings.sumOf { it.todayWh!! / 1000 }, .0001)
    }
    @Test fun configuredSinglePhaseExcludesUnusedNoiseChannels() {
        val json = """[{"eid":704643584,"timestamp":1791219481,"voltage":172.300,"activePower":607.836,
            "channels":[{"eid":1778385425,"timestamp":1791219481,"voltage":172.300,"activePower":607.836},
                {"eid":1778385426,"timestamp":1791219481,"voltage":3.478,"activePower":-0.216},
                {"eid":1778385427,"timestamp":1791219481,"voltage":10.402,"activePower":0}]}]"""
        val readings = GatewayParser.readings(json, configs)
        assertEquals(1, readings.size)
        assertEquals("net-consumption", readings.single().type)
        assertEquals(172.3, readings.single().voltage!!, .0001)
    }
    @Test fun oneEmptyInverterReadingDoesNotDiscardOtherDevices() {
        val root = com.google.gson.JsonParser.parseString(fixture()).asJsonObject
        root.getAsJsonObject("553648384").getAsJsonArray("channels")[0].asJsonObject.add("lastReading", com.google.gson.JsonObject())
        val rows = GatewayParser.devices(root.toString())
        assertEquals(9, rows.size)
        assertNull(rows.first { it.serial == "410000000001" }.acVoltage)
        assertTrue(rows.filter { it.serial != "410000000001" }.all { it.acVoltage != null })
    }
    @Test fun fallbackKeepsReportTimestampAndDoesNotInventVoltage() {
        val result = GatewayParser.fallback("""[{"serialNumber":"410000000001","lastReportDate":1791204472,"lastReportWatts":0,"maxReportWatts":52}]""").single()
        assertEquals(1791204472, result.timestamp)
        assertEquals(0.0, result.watts!!, 0.0)
        assertNull(result.acVoltage)
    }
    @Test fun homeConsumptionRequiresMatchingMeterTimes() {
        fun meter(type: String, ts: Long, watts: Double) = MeterReading(type, type, type, ts, null, null, null, watts, null, null, null, null, null)
        val prod = meter("production", 1000, 3000.0)
        val grid = meter("net-consumption", 1000, -2000.0)
        assertEquals(1000.0, Snapshot(listOf(prod, grid)).homeWatts!!, 0.0)
        assertNull(Snapshot(listOf(prod, grid.copy(timestamp = 900))).homeWatts)
    }
    @Test fun historyKeepsDistinctSourceCountersAndRejectsInvalidTimestamps() {
        val data = GatewayParser.devices(fixture())
        val valid = Snapshot(inverters = data, receivedAt = 1791219600)
        val samples = HistoryMapper.samples(valid, "987654321098")
        assertEquals(9, samples.filter { it.metric == "ac_voltage" }.size)
        assertEquals(230.0, samples.first { it.metric == "ac_voltage" && it.device == "410000000001" }.value, .0001)
        assertEquals(200.0, samples.first { it.metric == "reported_lifetime_energy" && it.device == "410000000001" }.value, .0001)
        assertTrue(HistoryMapper.samples(valid.copy(inverters = listOf(data.first().copy(timestamp = 0))), "987654321098").isEmpty())
    }
}
