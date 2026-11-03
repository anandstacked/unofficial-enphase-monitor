package com.anand.envoymonitor

import android.app.Application
import androidx.room.Room
import com.anand.envoymonitor.data.HistoryDatabase
import com.anand.envoymonitor.data.SettingsStore
import com.anand.envoymonitor.data.ThemePreferences
import com.anand.envoymonitor.data.PollingPreferences
import com.anand.envoymonitor.monitor.GatewayRepository
import com.anand.envoymonitor.monitor.Notifications
import com.anand.envoymonitor.network.GatewayClient
import com.anand.envoymonitor.sync.PhoneWearBridge
import com.anand.envoymonitor.sync.SnapshotCache
import com.anand.envoymonitor.sync.ReadingSource

class MonitorApplication : Application() {
    lateinit var settings: SettingsStore; private set
    lateinit var themePreferences: ThemePreferences; private set
    lateinit var pollingPreferences: PollingPreferences; private set
    lateinit var database: HistoryDatabase; private set
    lateinit var client: GatewayClient; private set
    lateinit var repository: GatewayRepository; private set
    lateinit var wearBridge: PhoneWearBridge; private set
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        themePreferences = ThemePreferences(this)
        pollingPreferences = PollingPreferences(this)
        database = Room.databaseBuilder(this, HistoryDatabase::class.java, "voltage-history.db").build()
        client = GatewayClient(this)
        Notifications.createChannels(this)
        val cache = SnapshotCache(this)
        val previous = cache.load(ReadingSource.PHONE)?.takeIf { it.gatewaySerial == settings.state.value.serial }?.snapshot
        repository = GatewayRepository(this, settings, database.samples(), client, pollingPreferences, previous)
        wearBridge = PhoneWearBridge(this, settings, repository, cache)
    }
}
