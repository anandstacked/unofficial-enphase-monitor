package com.anand.envoymonitor.sync

import android.content.Context
import com.anand.envoymonitor.data.SettingsStore
import com.anand.envoymonitor.monitor.GatewayRepository
import com.anand.envoymonitor.monitor.nowSeconds
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

@OptIn(FlowPreview::class)
class PhoneWearBridge(private val context: Context, private val settings: SettingsStore,
                      private val repository: GatewayRepository, private val cache: SnapshotCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
