package com.anand.envoymonitor.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object GatewayParser {
    private fun JsonObject.number(key: String): Double? = runCatching {
        get(key)?.takeUnless { it.isJsonNull }?.asDouble?.takeIf { it.isFinite() }
    }.getOrNull()
    private fun JsonObject.string(key: String): String? = runCatching { get(key)?.asString }.getOrNull()
    private fun JsonObject.bool(key: String, default: Boolean = false) = runCatching { get(key)?.asBoolean }.getOrNull() ?: default
    private fun JsonObject.obj(key: String) = runCatching { getAsJsonObject(key) }.getOrNull() ?: JsonObject()
    private fun JsonObject.objects(key: String) = runCatching { getAsJsonArray(key).mapNotNull { if (it.isJsonObject) it.asJsonObject else null } }.getOrDefault(emptyList())
    fun meters(json: String): List<MeterConfig> = JsonParser.parseString(json).asJsonArray.mapNotNull {
        runCatching {
            val o = it.asJsonObject
            MeterConfig(o.string("eid") ?: return@mapNotNull null, o.string("measurementType") ?: "unknown",
                o.string("state") == "enabled", (o.number("phaseCount") ?: 1.0).toInt().coerceIn(1, 3), o.string("meteringStatus") ?: "unknown")
        }.getOrNull()
    }
    fun readings(json: String, configs: List<MeterConfig>): List<MeterReading> {
        val byId = configs.associateBy { it.id }
        return JsonParser.parseString(json).asJsonArray.flatMap { element ->
            val o = element.asJsonObject
            val id = o.string("eid") ?: return@flatMap emptyList()
            val config = byId[id] ?: return@flatMap emptyList()
            if (!config.enabled) return@flatMap emptyList()
            val channels = o.objects("channels").take(config.phaseCount)
            (channels.ifEmpty { listOf(o) }).map { c ->
                MeterReading(id, c.string("eid") ?: id, config.type, (c.number("timestamp") ?: o.number("timestamp") ?: 0.0).toLong(),
                    c.number("voltage"), c.number("current"), c.number("freq"), c.number("activePower"),
                    c.number("apparentPower"), c.number("reactivePower"), c.number("pwrFactor"), c.number("actEnergyDlvd"), c.number("actEnergyRcvd"))
            }
        }
    }
    fun devices(json: String): List<InverterReading> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.entrySet().flatMap { (_, value) ->
            if (!value.isJsonObject) return@flatMap emptyList()
            val o = value.asJsonObject
            if (o.string("devName") != "pcu" || !o.bool("active")) return@flatMap emptyList()
            val serial = o.string("sn") ?: return@flatMap emptyList()
            o.objects("channels").map { c ->
                val last = c.obj("lastReading")
                val energy = c.obj("wattHours")
                InverterReading(serial, c.string("chanEid") ?: serial, (last.number("endDate") ?: 0.0).toLong(), true,
                    o.bool("modGone"), c.obj("watts").number("now"), last.number("acVoltageINmV")?.div(1000),
                    last.number("dcVoltageINmV")?.div(1000), last.number("acCurrentInmA")?.div(1000),
                    last.number("dcCurrentINmA")?.div(1000), last.number("acFrequencyINmHz")?.div(1000),
                    last.number("channelTemp"), energy.number("today"), energy.number("yesterday"), energy.number("week"),
                    c.obj("lifetime").number("joulesProduced")?.div(3600), last.string("flags_hex"), last.number("rssi"))
            }
        }.sortedBy { it.serial }
    }
    fun fallback(json: String): List<InverterReading> = JsonParser.parseString(json).asJsonArray.mapNotNull {
        runCatching {
            val o = it.asJsonObject
            val serial = o.string("serialNumber") ?: return@mapNotNull null
            InverterReading(serial, serial, (o.number("lastReportDate") ?: 0.0).toLong(), true, false,
                o.number("lastReportWatts"), null, null, null, null, null, null, null, null, null, null, null, null)
        }.getOrNull()
    }
}
