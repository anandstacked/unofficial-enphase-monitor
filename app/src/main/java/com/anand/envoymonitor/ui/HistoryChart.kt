package com.anand.envoymonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anand.envoymonitor.MonitorApplication
import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.monitor.nowSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.pm.ActivityInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val rangeLabels = listOf("Auto", "5 min", "15 min", "1 hour", "6 hours", "24 hours", "7 days", "30 days", "Custom")
private val rangeSeconds = listOf(0L, 300L, 900L, 3600L, 21600L, 86400L, 604800L, 2592000L)
private fun chartNumber(value: Double?, unit: String): String = value?.let {
    String.format(Locale.getDefault(), if (unit == "ratio" || unit == "kWh") "%.3f" else if (unit == "W") "%.0f" else "%.2f", it)
} ?: "—"
private fun chartDate(time: Long, pattern: String = "dd MMM · HH:mm:ss"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(time))

@Composable
fun HistoryChartCard(app: MonitorApplication, title: String, source: String, metric: String, unit: String,
                     fixedDevice: String? = null, onWindowChange: (ChartWindow) -> Unit = {}) {
    val config by app.settings.state.collectAsState()
    val interval by app.pollingPreferences.intervalSeconds.collectAsState()
    var chosen by rememberSaveable { mutableIntStateOf(3) }
    var area by rememberSaveable { mutableStateOf(true) }
    var showLimits by rememberSaveable { mutableStateOf(false) }
    var customStart by rememberSaveable { mutableLongStateOf(nowSeconds() - 3600) }
    var customEnd by rememberSaveable { mutableLongStateOf(nowSeconds()) }
    var frozenStart by rememberSaveable { mutableLongStateOf(0) }
    var frozenEnd by rememberSaveable { mutableLongStateOf(0) }
    var viewStart by rememberSaveable { mutableLongStateOf(0) }
    var viewEnd by rememberSaveable { mutableLongStateOf(0) }
    var clock by remember { mutableLongStateOf(nowSeconds()) }
    var editRange by remember { mutableStateOf(false) }
    var selectedDevice by rememberSaveable(source, metric) { mutableStateOf("") }
    var selectedSample by remember { mutableStateOf<Sample?>(null) }
    var expandedChart by rememberSaveable { mutableStateOf(false) }
    var orientationBeforeExpansion by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    var inspectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val controls = remember { HistoryPlotControls() }
    LaunchedEffect(Unit) { while (true) { clock = nowSeconds(); delay(10000) } }
    val devices by remember(config.serial, source, metric) {
        app.database.samples().chartDevices(config.serial, source, metric)
    }.collectAsState(emptyList())
    val device = fixedDevice ?: selectedDevice.takeIf { it in devices } ?: devices.firstOrNull().orEmpty()
    val rolling = if (chosen == 8) ChartWindow(customStart, customEnd)
        else ChartWindow(clock - if (chosen == 0) autoChartSeconds(interval) else rangeSeconds[chosen], clock)
    val base = if (frozenEnd > frozenStart) ChartWindow(frozenStart, frozenEnd) else rolling
    val window = if (viewEnd > viewStart) visibleChartWindow(base, (viewStart - base.start).toDouble(), (viewEnd - base.start).toDouble()) else base
    val currentWindow by rememberUpdatedState(window)
    val gap = if (source == "inverter") 1200L else 600L
    val rows by key(config.serial, source, metric, device, base) {
        remember { app.database.samples().chartPoints(config.serial, source, metric, device, base.start, base.end, chartBucketSeconds(base, gap)) }
            .collectAsState(initial = null)
    }
    val correlationRows by key(config.serial, source, metric, base) {
        remember {
            if (source == "combined") app.database.samples().voltagePowerCorrelation(config.serial, base.start, base.end)
            else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
    }
    var cachedRows by remember(config.serial, source, metric, device) { mutableStateOf(emptyList<Sample>()) }
    SideEffect { if (rows != null) cachedRows = rows!! }
    val detail by key(config.serial, source, metric, device, window, base) {
        remember {
            if (window == base) flowOf(emptyList()) else app.database.samples().chartPoints(config.serial, source, metric, device, window.start, window.end, chartBucketSeconds(window, gap))
        }.collectAsState(emptyList())
    }
    val plottedRows = remember(rows, cachedRows, detail, base, correlationRows, source) {
        if (source == "combined") return@remember correlationRows.mapIndexed { index, point ->
            val (sourceName, metricName, pointUnit) = when (point.series) {
                "voltage" -> Triple("meter:net-consumption", "voltage", "V")
                "solar" -> Triple("meter:production", "power", "W")
                "grid" -> Triple("meter:net-consumption", "power", "W")
                else -> Triple("combined", "home_power", "W")
            }
            Sample(id = index.toLong(), gateway = config.serial, source = sourceName, device = point.series,
                metric = metricName, value = point.value, unit = pointUnit, measuredAt = point.measuredAt, collectedAt = point.measuredAt)
        }
        ((rows ?: cachedRows) + detail).filter { it.measuredAt in base.start..base.end }
            .distinctBy { it.measuredAt }.sortedBy { it.measuredAt }
    }
    val chartRows = remember(plottedRows, source) {
        if (source == "combined") plottedRows.filter { it.device == "voltage" } else plottedRows
    }
    val stats by key(config.serial, source, metric, device, window) {
        remember { app.database.samples().chartStatistics(config.serial, source, metric, device, window.start, window.end) }
            .collectAsState(initial = null)
    }
    LaunchedEffect(window) { onWindowChange(window) }
    LaunchedEffect(source, metric, device) { selectedSample = null; inspectionJob?.cancel() }
    DisposableEffect(Unit) { onDispose { inspectionJob?.cancel() } }
    fun pause() { if (frozenEnd == 0L) { frozenStart = base.start; frozenEnd = base.end } }
    fun reset() {
        frozenStart = 0; frozenEnd = 0; viewStart = 0; viewEnd = 0
        selectedSample = null; inspectionJob?.cancel(); controls.reset(); clock = nowSeconds()
    }
    val activity = LocalContext.current as? Activity
    fun closeExpandedChart() {
        expandedChart = false
        activity?.requestedOrientation = orientationBeforeExpansion
    }
    fun inspectAt(timestamp: Long) {
        pause(); inspectionJob?.cancel()
        if (source == "combined") {
            selectedSample = chartRows.firstOrNull { it.measuredAt == timestamp }
            return
        }
        val requested = window
        inspectionJob = scope.launch {
            val sample = app.database.samples().nearestChartSample(config.serial, source, metric, device, requested.start, requested.end, timestamp)
            if (requested == currentWindow) selectedSample = sample
        }
    }
    if (editRange) CustomRangeDialog(customStart, customEnd, onDismiss = { editRange = false }) { start, end ->
        customStart = start; customEnd = end; chosen = 8; reset(); editRange = false
    }
    LaunchedEffect(selectedSample) {
        if (source == "combined" && selectedSample != null) {
            delay(8000)
            selectedSample = null
        }
    }
    val latest = selectedSample ?: chartRows.lastOrNull { it.measuredAt in window.start..window.end }
    val canShowLimits = source == "meter:net-consumption" && metric == "voltage" && config.lowVoltage != null && config.highVoltage != null
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (frozenEnd > 0) TextButton(onClick = { reset() }) { Text(if (chosen == 8) "Reset" else "Go live") }
                if (activity != null) IconButton(onClick = {
                    orientationBeforeExpansion = activity.requestedOrientation
                    expandedChart = true
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }) { Icon(painterResource(com.anand.envoymonitor.R.drawable.ic_landscape), contentDescription = "Expand chart") }
            }
            if (source == "combined") {
                Text("${chartNumber(latest?.value, "V")} V", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            } else Text("${chartNumber(latest?.value, unit)} $unit", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(latest?.let { "${if (selectedSample != null) "Selected" else "Last report"} · ${chartDate(it.measuredAt)}" } ?: "No recorded readings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${rangeLabels[chosen]} ▾") }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        rangeLabels.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = {
                            expanded = false; if (index == 8) editRange = true else { chosen = index; reset() }
                        }) }
                    }
                }
                if (source != "combined") Box(Modifier.weight(1f)) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${if (area) "Area" else "Line"} ▾") }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        listOf("Area", "Line").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { area = label == "Area"; expanded = false }) }
                    }
                }
            }
            if (fixedDevice == null && devices.size > 1) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text("${if (source == "inverter") "Inverter" else "Channel"}: $device ▾") }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        devices.forEach { serial -> DropdownMenuItem(text = { Text(serial) }, onClick = { selectedDevice = serial; selectedSample = null; expanded = false }) }
                    }
                }
            }
            if (!expandedChart) Box(Modifier.fillMaxWidth()) {
                NativeHistoryPlot(chartRows, base, window, stats, if (source == "combined") "V" else unit, gap, if (source == "combined") false else area,
                    if (showLimits && canShowLimits) config.lowVoltage!! to config.highVoltage!! else null,
                    controls, selectedSample, onBegin = { pause() }, onViewport = { next ->
                        // Native transforms are committed after the gesture, not on every pointer event.
                        if (next != window) {
                            pause(); viewStart = next.start; viewEnd = next.end
                            if (source != "combined") selectedSample = null
                        }
                    }, onInspect = ::inspectAt, scrubOnDrag = source == "combined",
                    markerRows = if (source == "combined") plottedRows else emptyList())
            }
            if (canShowLimits) FilterChip(selected = showLimits, onClick = { showLimits = !showLimits }, label = { Text("Limits") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Min" to stats?.minimum, "Average" to stats?.average, "Max" to stats?.maximum).forEach { (label, value) ->
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(chartNumber(value, unit), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${chartDate(window.start, "dd MMM HH:mm")} – ${chartDate(window.end, "dd MMM HH:mm")}${if (source == "combined") "" else " · ${stats?.count ?: "…"} reports"}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HelpIcon("Chart gestures", if (source == "combined")
                    "Drag on the voltage line to inspect solar, grid and home power at that report time. Pinch or double-tap to zoom; drag horizontally to browse. Go live resumes the rolling view."
                    else "Pinch or double-tap to zoom. Drag horizontally to browse; tap a reading to inspect it. Go live resumes the rolling view.")
            }
        }
    }
    if (expandedChart && activity != null) Dialog(
        onDismissRequest = ::closeExpandedChart,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = ::closeExpandedChart) {
                        Icon(painterResource(com.anand.envoymonitor.R.drawable.ic_landscape), contentDescription = "Close expanded chart")
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    NativeHistoryPlot(chartRows, base, window, stats, if (source == "combined") "V" else unit, gap, if (source == "combined") false else area,
                        if (showLimits && canShowLimits) config.lowVoltage!! to config.highVoltage!! else null,
                        controls, selectedSample, onBegin = { pause() }, onViewport = { next ->
                            if (next != window) {
                                pause(); viewStart = next.start; viewEnd = next.end
                                if (source != "combined") selectedSample = null
                            }
                        }, onInspect = ::inspectAt, scrubOnDrag = source == "combined",
                        markerRows = if (source == "combined") plottedRows else emptyList(), plotHeight = maxHeight - 48.dp)
                }
            }
        }
    }
}
@Composable
private fun CustomRangeDialog(start: Long, end: Long, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    var startText by remember { mutableStateOf(formatChartTime(start, zone)) }
    var endText by remember { mutableStateOf(formatChartTime(end, zone)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Custom time range") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Local time (${zone.id}) · last 30 days\nFormat: YYYY-MM-DD HH:MM", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(startText, { startText = it }, label = { Text("Start") }, singleLine = true)
            OutlinedTextField(endText, { endText = it }, label = { Text("End") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val window = validateCustomWindow(parseChartTime(startText, zone), parseChartTime(endText, zone), nowSeconds())
            onApply(window.start, window.end)
        } catch (e: java.time.DateTimeException) { error = "Enter valid dates and times in YYYY-MM-DD HH:MM format." }
        catch (e: IllegalArgumentException) { error = e.message }
    }) { Text("Apply") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
