package com.anand.envoymonitor.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.anand.envoymonitor.MainActivity
import com.anand.envoymonitor.R
import com.anand.envoymonitor.data.VoltageBand

object Notifications {
    const val SERVICE_ID = 10
    private const val MONITOR = "monitor_status"
    private const val ALERTS = "voltage_alerts"
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MONITOR, "Continuous monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERTS, "Voltage threshold alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun allowed(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun open(context: Context) = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun ongoing(context: Context, status: String): Notification {
        val stop = PendingIntent.getService(context, 1, Intent(context, MonitorService::class.java).setAction(MonitorService.STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, MONITOR).setSmallIcon(R.drawable.ic_solar)
            .setContentTitle("Solar voltage monitoring").setContentText(status).setContentIntent(open(context))
            .setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    fun voltageAlert(context: Context, band: VoltageBand, voltage: Double) {
        if (!allowed(context)) return
        val label = if (band == VoltageBand.LOW) "Low" else "High"
        val notification = Notification.Builder(context, ALERTS).setSmallIcon(R.drawable.ic_solar)
            .setContentTitle("$label gateway voltage")
            .setContentText("${"%.1f".format(voltage)} V crossed your configured threshold.")
            .setContentIntent(open(context)).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(11, notification)
    }
}
