package com.anand.envoymonitor.wear

import com.anand.envoymonitor.sync.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class WatchWearListener : WearableListenerService() {
    private val app get() = application as WearApplication
    override fun onDataChanged(events: DataEventBuffer) {
        // Copy bytes while the buffer is valid; the SDK authenticates package/signature.
        val payloads = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == SolarPaths.SNAPSHOT }
            .mapNotNull { runCatching { DataMapItem.fromDataItem(it.dataItem).dataMap.getByteArray("payload") }.getOrNull() }
        runBlocking { payloads.forEach { bytes -> SolarCodec.decode(bytes)?.let { app.repository.acceptPhone(it) } } }
    }
    override fun onMessageReceived(event: MessageEvent) {
        runBlocking {
            when (event.path) {
                SolarPaths.SNAPSHOT -> SolarCodec.decode(event.data)?.let { app.repository.acceptPhone(it) }
                SolarPaths.PROVISION -> {
                    val provision = SolarCodec.decodeProvision(event.data) ?: return@runBlocking
                    val nearbyPhone = runCatching { withTimeout(4000) {
                        Wearable.getNodeClient(this@WatchWearListener).connectedNodes.await().any { it.id == event.sourceNodeId && it.isNearby }
                    } }.getOrDefault(false)
                    if (!nearbyPhone) return@runBlocking
                    val success = runCatching { app.repository.acceptProvision(provision) }.isSuccess
                    runCatching { Wearable.getMessageClient(this@WatchWearListener).sendMessage(event.sourceNodeId, SolarPaths.ACK,
                        SolarCodec.encodeAck(ProvisionAck(provision.requestId, success))).await() }
                }
            }
        }
    }
}
