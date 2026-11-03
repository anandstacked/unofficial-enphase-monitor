package com.anand.envoymonitor

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import com.anand.envoymonitor.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Standalone device runner: checks actual Room/SQLite SQL in a disposable in-memory DB. */
class ChartDatabaseInstrumentation : ChartInteractionInstrumentation() {
    private var uiChecks = false
    override fun onCreate(arguments: Bundle?) { uiChecks = arguments?.getString("mode") == "ui"; super.onCreate(arguments) }
    override fun onStart() {
        if (uiChecks) { super.onStart(); return }
        val result = Bundle()
        try {
            runBlocking {
                val db = Room.inMemoryDatabaseBuilder(targetContext, HistoryDatabase::class.java).build()
                try {
                    val dao = db.samples()
                    val base = 1791000000L
                    fun sample(ts: Long, value: Double, device: String = "L1", gateway: String = "test") =
                        Sample(gateway = gateway, source = "meter:net-consumption", device = device,
                            metric = "voltage", value = value, unit = "V", measuredAt = ts, collectedAt = ts)
                    val input = (1..60000).filter { it !in 30000..31999 }.map { index ->
                        sample(base + index, when (index) { 12345 -> 170.0; 54321 -> 270.0; else -> 230.0 })
                    } + listOf(sample(base - 1, 1000.0), sample(base + 60001, 1000.0),
                        sample(base + 12345, 999.0, device = "L2"), sample(base + 12345, 888.0, gateway = "other"))
                    dao.insert(input)
                    val window = ChartWindow(base, base + 60000)
                    val points = dao.chartPoints("test", "meter:net-consumption", "voltage", "L1", window.start, window.end, chartBucketSeconds(window, 600)).first()
                    check(points.size < 2000 && points.all { it.measuredAt in window.start..window.end && it.device == "L1" && it.gateway == "test" }) { "Range/device/gateway isolation or reduction failed" }
                    check(points.minOf { it.value } == 170.0 && points.maxOf { it.value } == 270.0) { "Extrema lost beyond the old 50,000-row limit" }
                    check(points.first().measuredAt == base + 1 && points.last().measuredAt == base + 60000) { "Original endpoints lost" }
                    val segments = chartSegments(points, 600)
                    check(segments.size == 2 && segments.last().first().measuredAt == base + 32000) { "Outage joined or artificial gap introduced" }
                    val stats = dao.chartStatistics("test", "meter:net-consumption", "voltage", "L1", window.start, window.end).first()
                    check(stats.count == 58000L && stats.minimum == 170.0 && stats.maximum == 270.0 && kotlin.math.abs(stats.average!! - (230.0 - 20.0 / 58000)) < 1e-8) { "Statistics did not use all original reports" }
                    val original = dao.nearestChartSample("test", "meter:net-consumption", "voltage", "L1", window.start, window.end, base + 12346)
                    check(original?.measuredAt == base + 12346 && original.value == 230.0) { "Inspection did not read an original report" }
                    val empty = dao.chartStatistics("test", "meter:net-consumption", "voltage", "missing", window.start, window.end).first()
                    check(empty.count == 0L && empty.average == null && empty.minimum == null && empty.maximum == null) { "Empty series is not empty" }
                    val constant = dao.chartPoints("test", "meter:net-consumption", "voltage", "L1", base + 1, base + 5000, 600).first()
                    check(constant.size < 30) { "Equal minima/maxima retained every row instead of endpoints" }
                    val detail = dao.chartPoints("test", "meter:net-consumption", "voltage", "L1", base + 12340, base + 12350, 1).first()
                    check(detail.size == 11 && detail[5].value == 170.0) { "Zoomed detail lost raw measurements or inclusive bounds" }
                    result.putString("stream", "PASS: 9 Room/SQLite checks, 58,000 reports; extrema, endpoints, outage, all-report statistics, exact inspection, empty/constant/detail queries and isolation.\n")
                } finally { db.close() }
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
