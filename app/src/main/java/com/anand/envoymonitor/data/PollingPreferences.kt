package com.anand.envoymonitor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_REFRESH_SECONDS = 60
const val MIN_REFRESH_SECONDS = 1
const val MAX_REFRESH_SECONDS = 3600
fun parseRefreshSeconds(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in MIN_REFRESH_SECONDS..MAX_REFRESH_SECONDS }

class PollingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("polling", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching { preferences.getInt("interval_seconds", DEFAULT_REFRESH_SECONDS) }
        .getOrDefault(DEFAULT_REFRESH_SECONDS).takeIf { it in MIN_REFRESH_SECONDS..MAX_REFRESH_SECONDS } ?: DEFAULT_REFRESH_SECONDS)
    val intervalSeconds = mutable.asStateFlow()
    fun save(seconds: Int) {
        require(seconds in MIN_REFRESH_SECONDS..MAX_REFRESH_SECONDS)
        preferences.edit().putInt("interval_seconds", seconds).apply()
        mutable.value = seconds
    }
}
