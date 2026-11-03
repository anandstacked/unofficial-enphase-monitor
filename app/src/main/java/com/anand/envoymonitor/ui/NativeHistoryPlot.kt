package com.anand.envoymonitor.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anand.envoymonitor.data.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.YAxis.AxisDependency
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The library owns its touch matrix; Room updates never replace a gesture coroutine. */
internal class SolarLineChart(context: Context) : LineChart(context) {
    var baseWindow: ChartWindow? = null
    var begin: () -> Unit = {}
    var publish: (ChartWindow) -> Unit = {}
    var scrubOnDrag = false
    var displayedRows: List<Sample>? = null
    var displayedArea = false
    var displayedMultiSeries = false
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var multiTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private fun inspectTouch(event: MotionEvent) {
        getHighlightByTouchPoint(event.x, event.y)?.let { highlightValue(it, true) }
    }
    fun publishWindow() {
        baseWindow?.let { publish(visibleChartWindow(it, lowestVisibleX.toDouble(), highestVisibleX.toDouble())) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; horizontal = false; multiTouch = false
            val handled = super.onTouchEvent(event)
            if (scrubOnDrag) inspectTouch(event)
            return handled
        }
        if (event.pointerCount > 1) { horizontal = true; multiTouch = true; parent?.requestDisallowInterceptTouchEvent(true) }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            val dx = abs(event.x - downX); val dy = abs(event.y - downY)
            if (!horizontal && dy > slop && dy > dx) { parent?.requestDisallowInterceptTouchEvent(false); return false }
            if (scrubOnDrag && !multiTouch) {
                if (dx > slop) {
                    horizontal = true; parent?.requestDisallowInterceptTouchEvent(true)
                    inspectTouch(event)
                }
                return true
            }
            if (dx > slop && dx > dy) { horizontal = true; parent?.requestDisallowInterceptTouchEvent(true) }
        }
        if (scrubOnDrag && !multiTouch && event.actionMasked == MotionEvent.ACTION_UP) inspectTouch(event)
        return super.onTouchEvent(event)
    }
}

internal class HistoryPlotControls {
    var chart: SolarLineChart? = null
    fun zoom(inwards: Boolean) {
        val view = chart ?: return
        if (view.data == null || view.data.entryCount == 0) return
        view.begin()
        if (inwards) view.zoomIn() else view.zoomOut()
        view.post { view.publishWindow() }
    }
    fun reset() { chart?.apply { fitScreen(); highlightValues(null); invalidate() } }
}

@Composable
internal fun NativeHistoryPlot(rows: List<Sample>, base: ChartWindow, window: ChartWindow,
    stats: ChartStatistics?, unit: String, gap: Long, area: Boolean, limits: Pair<Double, Double>?,
    controls: HistoryPlotControls, selected: Sample?, onBegin: () -> Unit,
    onViewport: (ChartWindow) -> Unit, onInspect: (Long) -> Unit, multiSeries: Boolean = false,
    scrubOnDrag: Boolean = false, markerRows: List<Sample> = emptyList(), plotHeight: Dp = 220.dp) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary.toArgb()
    val muted = colors.onSurfaceVariant.toArgb()
    val grid = colors.outlineVariant.copy(alpha = .4f).toArgb()
    val warning = LocalMonitorAccents.current.warningText.toArgb()
    val currentBegin by rememberUpdatedState(onBegin)
    val currentViewport by rememberUpdatedState(onViewport)
    val currentInspect by rememberUpdatedState(onInspect)
    val groupedSeries = remember(rows, gap, multiSeries) {
        val groups = if (multiSeries) rows.groupBy { it.device } else mapOf("default" to rows)
        groups.mapValues { (_, values) -> chartSegments(values.filter { it.value.toFloat().isFinite() }, gap, reduce = false) }
    }
    val readingsAt = remember(markerRows) {
        markerRows.groupBy { it.measuredAt }.mapValues { (_, values) -> values.associateBy { it.device } }
    }
    AndroidView(modifier = Modifier.fillMaxWidth().height(plotHeight), factory = { context ->
        SolarLineChart(context).apply {
            controls.chart = this
            contentDescription = "Interactive history chart"
            description.isEnabled = false
            legend.isEnabled = false
            setNoDataText("No reports in this range")
            setNoDataTextColor(muted)
            setDrawBorders(false)
            setDrawGridBackground(false)
            setTouchEnabled(true)
            setDragEnabled(true)
            setDragYEnabled(false)
            setScaleXEnabled(true)
            setScaleYEnabled(false)
            setPinchZoom(true)
            setDoubleTapToZoomEnabled(true)
            setHighlightPerDragEnabled(true)
            isDragDecelerationEnabled = false
            axisRight.isEnabled = multiSeries
            if (multiSeries) {
                legend.isEnabled = true
                legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                legend.orientation = Legend.LegendOrientation.HORIZONTAL
                legend.setDrawInside(false)
            }
            setExtraOffsets(4f, 12f, 14f, 4f)
            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(entry: Entry?, highlight: Highlight?) {
                    val sample = entry?.data as? Sample ?: return
                    begin(); currentInspect(sample.measuredAt)
                }
                override fun onNothingSelected() = Unit
            })
            onChartGestureListener = object : OnChartGestureListener {
                override fun onChartGestureStart(e: MotionEvent?, gesture: ChartTouchListener.ChartGesture?) = Unit
                override fun onChartGestureEnd(e: MotionEvent?, gesture: ChartTouchListener.ChartGesture?) { publishWindow() }
                override fun onChartScale(e: MotionEvent?, x: Float, y: Float) { begin() }
                override fun onChartTranslate(e: MotionEvent?, x: Float, y: Float) { begin() }
                override fun onChartDoubleTapped(e: MotionEvent?) { begin(); post { publishWindow() } }
                override fun onChartLongPressed(e: MotionEvent?) = Unit
                override fun onChartSingleTapped(e: MotionEvent?) = Unit
                override fun onChartFling(a: MotionEvent?, b: MotionEvent?, x: Float, y: Float) = Unit
            }
        }
    }, update = { chart ->
        val reset = chart.baseWindow != base
        chart.baseWindow = base
        chart.begin = { currentBegin() }
        chart.publish = { currentViewport(it) }
        chart.scrubOnDrag = scrubOnDrag
        if (scrubOnDrag) {
            val marker = (chart.marker as? PowerReadingMarkerView) ?: PowerReadingMarkerView(chart.context).also {
                it.setChartView(chart)
                chart.marker = it
            }
            marker.readingsAt = readingsAt
            marker.setColors(colors.surface.toArgb(), colors.onSurface.toArgb(), muted, primary, colors.outlineVariant.toArgb())
        } else chart.marker = null
        chart.setNoDataTextColor(muted)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            axisMinimum = 0f; axisMaximum = base.seconds.toFloat()
            textColor = muted; textSize = 10f
            setLabelCount(3, false)
            granularity = 1f
            setDrawAxisLine(false); setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    if (!value.isFinite()) return ""
                    val timestamp = base.start + value.toLong()
                    return DateTimeFormatter.ofPattern(if (window.seconds >= 86400) "dd MMM HH:mm" else if (window.seconds <= 300) "HH:mm:ss" else "HH:mm")
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(timestamp))
                }
            }
        }
        val voltageRows = rows.filter { it.device == "voltage" && it.measuredAt in window.start..window.end }
        val visibleValues = (if (multiSeries) voltageRows else rows.filter { it.measuredAt in window.start..window.end }).map { it.value }
        val minimum = stats?.minimum ?: visibleValues.minOrNull() ?: 0.0
        val maximum = stats?.maximum ?: visibleValues.maxOrNull() ?: 1.0
        val low = minOf(minimum, limits?.first ?: minimum)
        val high = maxOf(maximum, limits?.second ?: maximum)
        val spread = (high - low).coerceAtLeast(if (unit == "ratio") .02 else 1.0)
        chart.axisLeft.apply {
            axisMinimum = (low - spread * .15).toFloat()
            axisMaximum = (high + spread * .15).toFloat()
            textColor = muted; textSize = 10f
            gridColor = grid; gridLineWidth = .7f
            enableGridDashedLine(5f, 6f, 0f)
            setLabelCount(4, false); setDrawAxisLine(false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format(Locale.getDefault(), if (unit == "W") "%.0f" else if (unit == "ratio") "%.2f" else "%.1f", value)
            }
            removeAllLimitLines()
            limits?.let { (a, b) -> listOf(a, b).forEach { value ->
                addLimitLine(LimitLine(value.toFloat()).apply { lineColor = warning; lineWidth = 1f; enableDashedLine(7f, 5f, 0f) })
            } }
        }
        if (multiSeries) {
            chart.axisRight.apply {
                isEnabled = true
                val watts = rows.filter { it.unit == "W" && it.measuredAt in window.start..window.end }.map { it.value }
                val minW = watts.minOrNull() ?: 0.0; val maxW = watts.maxOrNull() ?: 1.0
                val spreadW = (maxW - minW).coerceAtLeast(1.0)
                axisMinimum = (minW - spreadW * .15).toFloat(); axisMaximum = (maxW + spreadW * .15).toFloat()
                textColor = muted; textSize = 10f; setLabelCount(4, false); setDrawAxisLine(false); setDrawGridLines(false)
                valueFormatter = object : ValueFormatter() { override fun getFormattedValue(value: Float) = String.format(Locale.getDefault(), "%.0f", value) }
            }
        }
        if (reset || chart.displayedRows !== rows || chart.displayedArea != area || chart.displayedMultiSeries != multiSeries) {
            val palette = mapOf("voltage" to primary, "solar" to 0xffe58b18.toInt(), "grid" to 0xff5279d2.toInt(), "home" to 0xff8a59b4.toInt())
            val labels = mapOf("voltage" to "Voltage (V)", "solar" to "Solar (W)", "grid" to "Grid (W)", "home" to "Home (W)")
            val datasets = groupedSeries.flatMap { (seriesName, segments) -> segments.map { segment ->
                val lineColor = palette[seriesName] ?: primary
                LineDataSet(segment.map { Entry((it.measuredAt - base.start).toFloat(), it.value.toFloat(), it) }, labels[seriesName] ?: "Reports").apply {
                    color = lineColor; lineWidth = 2.4f
                    axisDependency = if (multiSeries && seriesName != "voltage") AxisDependency.RIGHT else AxisDependency.LEFT
                    mode = LineDataSet.Mode.LINEAR
                    setDrawValues(false)
                    setDrawCircles(segment.size == 1)
                    circleRadius = 3f; setCircleColor(lineColor); setDrawCircleHole(false)
                    highLightColor = lineColor; highlightLineWidth = 1f
                    enableDashedHighlightLine(4f, 4f, 0f); setDrawHorizontalHighlightIndicator(false)
                    setDrawFilled(area && segment.size > 1)
                    fillDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf((lineColor and 0x00ffffff) or 0x40000000, (lineColor and 0x00ffffff) or 0x04000000))
                } as ILineDataSet
            } }
            chart.data = if (datasets.isEmpty()) null else LineData(datasets)
            chart.notifyDataSetChanged()
            chart.displayedRows = rows
            chart.displayedArea = area
            chart.displayedMultiSeries = multiSeries
        }
        if ((chart.data?.entryCount ?: 0) > 0) chart.setVisibleXRangeMinimum(minOf(10L, base.seconds).toFloat())
        if (reset) chart.fitScreen()
        if (selected == null) chart.highlightValues(null)
        chart.invalidate()
    }, onRelease = { chart -> if (controls.chart === chart) controls.chart = null })
}
