package com.anand.envoymonitor.monitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.anand.envoymonitor.MonitorApplication
import kotlinx.coroutines.*

class MonitorService : Service() {
    companion object { const val STOP = "com.anand.envoymonitor.STOP" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MonitorApplication
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.SERVICE_ID, Notifications.ongoing(this, "Connecting to home gateway…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        app.repository.setBackground(true)
        scope.launch {
            app.repository.state.collect { state ->
                val status = state.snapshot.grid?.voltage?.let { "Last ${"%.1f".format(it)} V · ${state.status}" } ?: state.status
                getSystemService(android.app.NotificationManager::class.java).notify(Notifications.SERVICE_ID, Notifications.ongoing(this@MonitorService, status))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { app.repository.setBackground(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); app.repository.setBackground(false); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
