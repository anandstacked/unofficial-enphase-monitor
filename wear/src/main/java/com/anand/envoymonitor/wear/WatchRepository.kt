package com.anand.envoymonitor.wear

import android.content.Context
import com.anand.envoymonitor.data.SettingsStore
import com.anand.envoymonitor.network.*
import com.anand.envoymonitor.sync.*
import com.anand.envoymonitor.wear.complications.ComplicationUpdates
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.net.ssl.SSLException

fun nowSeconds() = System.currentTimeMillis() / 1000
data class WatchState(val envelope: SolarEnvelope? = null, val status: String = "Waiting for phone readings", val busy: Boolean = false)

class WatchRepository(private val context: Context, private val settings: SettingsStore,
                      private val cache: SnapshotCache, private val client: GatewayClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fetcher = GatewayFetcher(client)
    private var phone = cache.load(ReadingSource.PHONE)?.takeIf { !settings.state.value.paired || it.gatewaySerial == settings.state.value.serial }
    private var direct = cache.load(ReadingSource.WATCH_WIFI)?.takeIf { it.gatewaySerial == settings.state.value.serial }
    private val mutable = MutableStateFlow(WatchState(selectEnvelope(phone, direct, nowSeconds())))
    val state = mutable.asStateFlow()
    private var visible = false
    private var loop: Job? = null
    private var refreshRequested = false
    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { loop?.cancel(); loop = null; client.releaseWifi(); mutable.value = mutable.value.copy(busy = false); return }
        if (loop?.isActive == true) return
        loop = scope.launch {
            requestPhone()
            while (isActive && visible) {
                val config = settings.state.value
                if (phoneUsable(phone, nowSeconds())) {
                    client.releaseWifi()
                    mutable.value = WatchState(selectEnvelope(phone, direct, nowSeconds()), "Phone collector connected")
                } else if (!config.paired || !settings.hasToken()) {
                    mutable.value = WatchState(selectEnvelope(phone, direct, nowSeconds()), "Phone sync only · direct setup paused")
                } else {
                    mutable.value = mutable.value.copy(busy = true, status = "Connecting directly over home Wi-Fi…")
                    try {
                        val snapshot = fetcher.fetch(config, settings.token()!!)
                        ensureActive()
                        if (settings.state.value == config) {
                            val envelope = SolarEnvelope(gatewaySerial = config.serial, snapshot = snapshot, publishedAt = nowSeconds(),
                                collecting = true, source = ReadingSource.WATCH_WIFI, lowVoltage = config.lowVoltage, highVoltage = config.highVoltage)
                            direct = envelope; cache.save(envelope)
                            mutable.value = WatchState(selectEnvelope(phone, direct, nowSeconds()), "Direct home Wi-Fi · app open only")
                            ComplicationUpdates.request(context)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = when (e) {
                            is GatewayFailure -> e.message ?: "Gateway unavailable"
                            is SSLException -> "Certificate changed. Direct setup is paused in the phone app."
                            else -> "Home Wi-Fi unavailable. Connect the watch to your saved home network."
                        }
                        mutable.value = WatchState(selectEnvelope(phone, direct, nowSeconds()), message)
                    }
                }
                var remaining = 60
                while (remaining > 0 && !refreshRequested) { delay(1000); remaining-- }
                refreshRequested = false
                requestPhone()
            }
        }
    }
    fun refresh() { refreshRequested = true; scope.launch { requestPhone() } }
    suspend fun acceptPhone(envelope: SolarEnvelope) = withContext(Dispatchers.Main.immediate) {
        if (envelope.source != ReadingSource.PHONE) return@withContext
        if (settings.state.value.paired && envelope.gatewaySerial != settings.state.value.serial) return@withContext
        if (!acceptDelivery(phone, envelope)) return@withContext
        phone = envelope; cache.save(envelope)
        if (settings.state.value.paired && (settings.state.value.lowVoltage != envelope.lowVoltage || settings.state.value.highVoltage != envelope.highVoltage))
            settings.saveThresholds(envelope.lowVoltage, envelope.highVoltage)
        mutable.value = mutable.value.copy(envelope = selectEnvelope(phone, direct, nowSeconds()))
        ComplicationUpdates.request(context)
    }
    suspend fun acceptProvision(provision: WatchProvision) {
        withContext(Dispatchers.Main.immediate) { loop?.cancel(); loop = null; client.releaseWifi() }
        withContext(Dispatchers.IO) {
            settings.pair(provision.settings.host, provision.settings.serial, provision.settings.fingerprint)
            settings.saveToken(provision.token)
            settings.saveThresholds(provision.settings.lowVoltage, provision.settings.highVoltage)
        }
        withContext(Dispatchers.Main.immediate) {
            if (phone?.gatewaySerial != provision.settings.serial) phone = null
            direct = null; cache.clear(); phone?.let(cache::save); fetcher.reset()
            mutable.value = WatchState(phone, "Direct gateway access provisioned")
            if (visible) setVisible(true)
            ComplicationUpdates.request(context)
        }
    }
    private suspend fun requestPhone() {
        try {
            withTimeoutOrNull(4000) {
                val nodes = Wearable.getCapabilityClient(context).getCapability(SolarPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                val node = nodes.sortedByDescending { it.isNearby }.firstOrNull() ?: return@withTimeoutOrNull
                Wearable.getMessageClient(context).sendMessage(node.id, SolarPaths.REQUEST, byteArrayOf()).await()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Cached readings remain available while the phone is unreachable. */ }
    }
}
