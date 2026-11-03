package com.anand.envoymonitor.sync

import android.content.Context

/** Stores measurements only. Credentials belong exclusively in SettingsStore. */
class SnapshotCache(context: Context) {
    private val preferences = context.getSharedPreferences("solar_snapshot_cache", Context.MODE_PRIVATE)
    fun load(source: ReadingSource): SolarEnvelope? = preferences.getString(source.name, null)
        ?.let { SolarCodec.decode(it.toByteArray(Charsets.UTF_8)) }
    fun save(envelope: SolarEnvelope) { preferences.edit().putString(envelope.source.name, SolarCodec.encode(envelope).toString(Charsets.UTF_8)).apply() }
    fun clear() { preferences.edit().clear().apply() }
}
