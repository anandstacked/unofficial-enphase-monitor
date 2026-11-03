package com.anand.envoymonitor.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.anand.envoymonitor.R
import com.anand.envoymonitor.data.Sample
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** A compact marker tied to the highlighted gateway-voltage report. */
internal class PowerReadingMarkerView(context: Context) : MarkerView(context, R.layout.chart_power_marker) {
    var readingsAt: Map<Long, Map<String, Sample>> = emptyMap()

    private val panel = findViewById<android.widget.LinearLayout>(R.id.marker_panel)
    private val time = findViewById<TextView>(R.id.marker_time)
    private val voltage = findViewById<TextView>(R.id.marker_voltage)
    private val solar = findViewById<TextView>(R.id.marker_solar)
    private val grid = findViewById<TextView>(R.id.marker_grid)
    private val home = findViewById<TextView>(R.id.marker_home)
    private val date = DateTimeFormatter.ofPattern("dd MMM · HH:mm:ss").withZone(ZoneId.systemDefault())
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun watts(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.0f W", it) } ?: "—"

    fun setColors(surface: Int, foreground: Int, secondary: Int, accent: Int, outline: Int) {
        panel.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(12f)
            setStroke(dp(1f).toInt(), outline)
        }
        time.setTextColor(secondary)
        voltage.setTextColor(accent)
        listOf(solar, grid, home).forEach { it.setTextColor(foreground) }
    }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val selected = e?.data as? Sample ?: return
        val values = readingsAt[selected.measuredAt].orEmpty()
        time.text = date.format(Instant.ofEpochSecond(selected.measuredAt))
        voltage.text = "Voltage  ${String.format(Locale.getDefault(), "%.2f V", selected.value)}"
        solar.text = "Solar production  ${watts(values["solar"]?.value)}"
        val gridPower = values["grid"]?.value
        grid.text = when {
            gridPower == null -> "Grid  —"
            gridPower > 0 -> "Grid import  ${watts(gridPower)}"
            gridPower < 0 -> "Grid export  ${watts(abs(gridPower))}"
            else -> "Grid balanced  0 W"
        }
        home.text = "Home use  ${watts(values["home"]?.value)}"
        super.refreshContent(e, highlight)
        setOffset(-width / 2f, -height - dp(10f))
    }
}
