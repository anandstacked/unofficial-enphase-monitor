package com.anand.envoymonitor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

/** Phone appearance is independent of gateway configuration and watch provisioning. */
class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        ThemeMode.valueOf(preferences.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM))
    val mode = mutable.asStateFlow()
    fun select(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
        mutable.value = mode
    }
}
