package com.anand.envoymonitor.wear

import android.app.Application
import com.anand.envoymonitor.data.SettingsStore
import com.anand.envoymonitor.network.GatewayClient
import com.anand.envoymonitor.sync.SnapshotCache

class WearApplication : Application() {
    lateinit var settings: SettingsStore; private set
    lateinit var cache: SnapshotCache; private set
    lateinit var repository: WatchRepository; private set
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this); cache = SnapshotCache(this)
        repository = WatchRepository(this, settings, cache, GatewayClient(this))
    }
}
