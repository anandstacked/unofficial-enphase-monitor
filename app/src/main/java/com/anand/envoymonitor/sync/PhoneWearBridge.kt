package com.anand.envoymonitor.sync

import android.content.Context
import com.anand.envoymonitor.data.SettingsStore
import com.anand.envoymonitor.monitor.GatewayRepository
import com.anand.envoymonitor.monitor.nowSeconds
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(FlowPreview::class)
class PhoneWearBridge(private val context: Context, private val settings: SettingsStore,
                      private val repository: GatewayRepository, private val cache: SnapshotCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Pair<String, CompletableDeferred<Boolean>>>()
    init {
        scope.launch {
            repository.state.combine(settings.state) { state, config ->
                if (state.snapshot.receivedAt > 0) SolarEnvelope(gatewaySerial = config.serial, snapshot = state.snapshot,
                    publishedAt = nowSeconds(), collecting = state.collecting, lowVoltage = config.lowVoltage, highVoltage = config.highVoltage) else null
            }.filterNotNull().debounce(300).collect { envelope ->
                cache.save(envelope)
                runCatching {
                    val map = PutDataMapRequest.create(SolarPaths.SNAPSHOT)
                    map.dataMap.putByteArray("payload", SolarCodec.encode(envelope))
                    Wearable.getDataClient(context).putDataItem(map.asPutDataRequest()).await()
                }
            }
        }
    }
    suspend fun nearbyWatches(): List<Node> = withTimeout(8000) {
        Wearable.getCapabilityClient(context).getCapability(SolarPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.filter { it.isNearby }.sortedBy { it.displayName }
    }
    suspend fun provision(nodeId: String): Boolean {
        val node = nearbyWatches().firstOrNull { it.id == nodeId } ?: error("Watch is no longer nearby")
        val config = settings.state.value
        val token = settings.token() ?: error("No owner token saved")
        check(config.paired)
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Boolean>()
        pending[id] = node.id to result
        try {
            return withTimeout(20000) {
                Wearable.getMessageClient(context).sendMessage(node.id, SolarPaths.PROVISION,
                    SolarCodec.encodeProvision(WatchProvision(requestId = id, settings = config, token = token))).await()
                result.await()
            }
        } finally { pending.remove(id) }
    }
    fun acknowledge(source: String, bytes: ByteArray) {
        val ack = SolarCodec.decodeAck(bytes) ?: return
        val request = pending[ack.requestId] ?: return
        if (request.first == source) request.second.complete(ack.successful)
    }
    suspend fun replySnapshot(nodeId: String) {
        val state = repository.state.value
        val config = settings.state.value
        val cached = cache.load(ReadingSource.PHONE)?.takeIf { it.gatewaySerial == config.serial }
        val snapshot = state.snapshot.takeIf { it.receivedAt > 0 } ?: cached?.snapshot ?: return
        val envelope = SolarEnvelope(gatewaySerial = config.serial, snapshot = snapshot, publishedAt = nowSeconds(),
            collecting = state.collecting, lowVoltage = config.lowVoltage, highVoltage = config.highVoltage)
        Wearable.getMessageClient(context).sendMessage(nodeId, SolarPaths.SNAPSHOT, SolarCodec.encode(envelope)).await()
        // Routine watch cache requests must not shorten the user's phone polling interval.
    }
}
