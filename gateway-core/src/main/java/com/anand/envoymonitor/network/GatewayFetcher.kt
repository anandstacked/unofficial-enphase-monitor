package com.anand.envoymonitor.network

import com.anand.envoymonitor.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared endpoint policy; callers own visibility, power locks, storage and alerts. */
class GatewayFetcher(private val client: GatewayClient) {
    private val mutex = Mutex()
    private var identity: Triple<String, String, String>? = null
    private var meters = emptyList<MeterConfig>()
    private var detailed = true
    fun reset() { identity = null }
    suspend fun fetch(config: ConnectionSettings, token: String): Snapshot = mutex.withLock {
        client.awaitWifi()
        withContext(Dispatchers.IO) {
            val key = Triple(config.host, config.serial, config.fingerprint)
            if (identity != key) {
                val info = client.get(config, token, "/info")
                if (Regex("<sn>([0-9]+)</sn>").find(info)?.groupValues?.get(1) != config.serial)
                    throw GatewayFailure(0, "Gateway identity changed. Check its serial and re-pair.")
                meters = GatewayParser.meters(client.get(config, token, "/ivp/meters"))
                identity = key; detailed = true
            }
            val notes = mutableListOf<String>()
            val readings = try { GatewayParser.readings(client.get(config, token, "/ivp/meters/readings"), meters) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is GatewayFailure && e.code in listOf(401, 403)) throw e
                notes += "Meter readings unavailable this check."; emptyList()
            }
            var inverters = emptyList<InverterReading>()
            if (detailed) {
                try { inverters = GatewayParser.devices(client.get(config, token, "/ivp/pdm/device_data")) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is GatewayFailure && e.code == 401) throw e
                    if (e is GatewayFailure && e.code in listOf(403, 404)) detailed = false
                    notes += "Detailed inverter readings unavailable."
                }
            }
            if (inverters.isEmpty()) {
                try { inverters = GatewayParser.fallback(client.get(config, token, "/api/v1/production/inverters")) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is GatewayFailure && e.code in listOf(401, 403)) throw e
                    notes += "Inverter power unavailable this check."
                }
                if (inverters.isNotEmpty()) notes += "Inverter power only; detail telemetry unavailable."
            }
            if (readings.isEmpty() && inverters.isEmpty()) throw GatewayFailure(0, "No measurements received.")
            Snapshot(readings, inverters, System.currentTimeMillis() / 1000, notes)
        }
    }
}
