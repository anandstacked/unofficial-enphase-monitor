package com.anand.envoymonitor.sync

import com.anand.envoymonitor.MonitorApplication
import com.google.android.gms.wearable.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PhoneWearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val app = application as MonitorApplication
        when (event.path) {
            SolarPaths.REQUEST -> runBlocking { withTimeoutOrNull(5000) { runCatching { app.wearBridge.replySnapshot(event.sourceNodeId) } } }
        }
    }
}
