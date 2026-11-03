package com.anand.envoymonitor.monitor

import com.anand.envoymonitor.data.MAX_REFRESH_SECONDS
import com.anand.envoymonitor.data.MIN_REFRESH_SECONDS

/** A changed interval is measured from the last completed attempt, without restarting it. */
class PollingSchedule(private val completedAtMillis: Long) {
    fun remainingMillis(nowMillis: Long, intervalSeconds: Int, failures: Int): Long {
        require(intervalSeconds in MIN_REFRESH_SECONDS..MAX_REFRESH_SECONDS)
        val retrySeconds = if (failures <= 0) 0L else (30L * (1L shl (failures - 1).coerceAtMost(4))).coerceAtMost(300)
        val waitMillis = maxOf(intervalSeconds.toLong(), retrySeconds) * 1000
        return (waitMillis - (nowMillis - completedAtMillis).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
