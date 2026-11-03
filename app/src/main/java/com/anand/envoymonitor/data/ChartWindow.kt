package com.anand.envoymonitor.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.roundToLong

data class ChartWindow(val start: Long, val end: Long) {
    init { require(end > start) }
    val seconds: Long get() = end - start

    fun zoom(factor: Double, anchor: Double, bounds: ChartWindow): ChartWindow {
        if (!factor.isFinite() || factor <= 0 || !anchor.isFinite()) return this
        val fraction = anchor.coerceIn(0.0, 1.0)
        val span = (seconds / factor).roundToLong().coerceIn(minOf(60L, bounds.seconds), bounds.seconds)
        val pivot = start + seconds * fraction
        val left = (pivot - span * fraction).roundToLong().coerceIn(bounds.start, bounds.end - span)
        return ChartWindow(left, left + span)
    }

    fun pan(fraction: Double, bounds: ChartWindow): ChartWindow {
        if (!fraction.isFinite()) return this
        val span = seconds.coerceAtMost(bounds.seconds)
        val left = (start - (seconds * fraction).roundToLong()).coerceIn(bounds.start, bounds.end - span)
        return ChartWindow(left, left + span)
    }
}

fun chartBucketSeconds(window: ChartWindow, gapSeconds: Long): Long =
    (window.seconds / 350).coerceIn(1L, gapSeconds)

fun autoChartSeconds(refreshSeconds: Int): Long = refreshSeconds.coerceIn(1, 3600) * 60L

/** Chart libraries use offsets, never float epoch timestamps. Ignore transient invalid gestures. */
fun visibleChartWindow(bounds: ChartWindow, leftOffset: Double, rightOffset: Double): ChartWindow {
    if (!leftOffset.isFinite() || !rightOffset.isFinite() || rightOffset <= leftOffset) return bounds
    val left = (bounds.start + leftOffset.coerceIn(0.0, bounds.seconds.toDouble())).roundToLong()
        .coerceIn(bounds.start, bounds.end - 1)
    val right = (bounds.start + rightOffset.coerceIn(0.0, bounds.seconds.toDouble())).roundToLong()
        .coerceIn(left + 1, bounds.end)
    return ChartWindow(left, right)
}

private val customTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
fun parseChartTime(text: String, zone: ZoneId): Long = LocalDateTime.parse(text.trim(), customTimeFormat).atZone(zone).toEpochSecond()
fun formatChartTime(timestamp: Long, zone: ZoneId): String =
    customTimeFormat.format(java.time.Instant.ofEpochSecond(timestamp).atZone(zone))

fun validateCustomWindow(start: Long, end: Long, now: Long): ChartWindow {
    require(end > start) { "End must be after start." }
    require(end <= now) { "End cannot be in the future." }
    require(start >= now - 30 * 86400L) { "Choose a start within the last 30 days." }
    return ChartWindow(start, end)
}
