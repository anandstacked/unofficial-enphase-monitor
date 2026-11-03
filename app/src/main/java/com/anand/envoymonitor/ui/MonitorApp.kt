package com.anand.envoymonitor.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.anand.envoymonitor.MonitorApplication
import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.monitor.*
import com.anand.envoymonitor.network.PairingCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Teal: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
private val Amber: Color @Composable get() = LocalMonitorAccents.current.warningText
// Keep the provisioning UI available for a later fix without exposing a setup flow that currently fails.
private fun number(value: Double?, digits: Int = 1) = value?.let { "% .${digits}f".format(it).trim() } ?: "—"
private fun date(timestamp: Long, pattern: String = "dd MMM · HH:mm") = if (timestamp <= 0) "No report" else
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(timestamp))
private fun age(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return "Not reported"
    val seconds = (now - timestamp).coerceAtLeast(0)
    return when { seconds < 60 -> "Just reported"; seconds < 3600 -> "${seconds / 60} min ago"; seconds < 86400 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min ago"; else -> "${seconds / 86400} days ago" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorApp(app: MonitorApplication) {
    val state by app.repository.state.collectAsState()
    val config by app.settings.state.collectAsState()
    val themeMode by app.themePreferences.mode.collectAsState()
    var screen by rememberSaveable { mutableStateOf("Dashboard") }
    var serial by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(nowSeconds()) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler(enabled = screen != "Dashboard" || serial != null) { serial = null; screen = "Dashboard" }
    LaunchedEffect(Unit) { while (true) { now = nowSeconds(); delay(15000) } }
    fun startMonitor() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
            .onFailure { scope.launch { snack.showSnackbar("Unable to start monitoring. Reopen the app and try again.") } }
    }
    var pendingMonitor by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snack.showSnackbar("Notifications are disabled. Collection and in-app warnings still work.") }
        if (pendingMonitor) { pendingMonitor = false; startMonitor() }
    }
    fun toggleMonitor(enabled: Boolean) {
        if (!enabled) { context.stopService(Intent(context, MonitorService::class.java)); return }
        if (!config.paired || !app.settings.hasToken()) {
            screen = "Settings"; scope.launch { snack.showSnackbar("Pair the gateway and save its token first.") }; return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.allowed(context)) {
            pendingMonitor = true; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startMonitor()
    }
    MonitorTheme(themeMode) {
        Scaffold(
            containerColor = Paper,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(title = { Column { Text(if (serial == null) "Unofficial Enphase Monitor" else "Inverter details", fontWeight = FontWeight.SemiBold)
                    serial?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Muted) } } },
                    navigationIcon = { if (serial != null) TextButton(onClick = { serial = null; screen = "Dashboard" }) { Text("Back") } },
                    actions = { if (serial == null && screen == "Dashboard") IconButton(onClick = { app.repository.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh data") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Dashboard" to Icons.Default.Home, "History" to Icons.Default.List, "Settings" to Icons.Default.Settings).forEach { (name, icon) ->
                        NavigationBarItem(selected = screen == name && serial == null, onClick = { screen = name; serial = null },
                            icon = { Icon(icon, contentDescription = name) }, label = { Text(name) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    serial != null -> InverterDetails(app, serial!!, state.snapshot.inverters.firstOrNull { it.serial == serial }, now)
                    screen == "Dashboard" -> Dashboard(app, state, config, now, { serial = it }, { screen = "Settings" })
                    screen == "History" -> HistoryScreen(app)
                    else -> SettingsScreen(app, config, state, snack, ::toggleMonitor, { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
                }
            }
        }
    }
}

@Composable
private fun Dashboard(app: MonitorApplication, state: MonitorState, config: ConnectionSettings, now: Long,
                      select: (String) -> Unit, settings: () -> Unit) {
    val snapshot = state.snapshot
    val grid = snapshot.grid
    val fresh = grid?.let { isFresh(it.timestamp, now, 600) } == true
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!config.paired || !app.settings.hasToken()) item {
            SurfaceCard {
                Text("Connect your home gateway", style = MaterialTheme.typography.titleLarge)
                Button(onClick = settings) { Text("Set up connection") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF183138)), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GATEWAY VOLTAGE", color = Color(0xFFB8DAD5), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(number(grid?.voltage), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Light)
                        Text(" V", color = Color(0xFFB8DAD5), fontSize = 24.sp, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text("${number(grid?.frequency, 3)} Hz   ·   ${number(grid?.current, 3)} A", color = Color(0xFFB8DAD5))
                    Text(if (grid == null) "Waiting for net-consumption meter" else "${age(grid.timestamp, now)} · ${date(grid.timestamp)}${if (!fresh) " · STALE" else ""}",
                        color = if (fresh) Color(0xFFB8DAD5) else Color(0xFFFFD9A3), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        val alertVoltage = grid?.voltage
        val lowLimit = config.lowVoltage
        val highLimit = config.highVoltage
        if (fresh && alertVoltage != null && lowLimit != null && highLimit != null) {
            val band = voltageBand(alertVoltage, lowLimit, highLimit)
            if (band != VoltageBand.NORMAL) item {
                Warning("${if (band == VoltageBand.LOW) "Low" else "High"} voltage: ${number(alertVoltage)} V", "Outside your configured ${number(lowLimit)}–${number(highLimit)} V range.")
            }
        }
        state.error?.let { error -> item { Warning("Connection details", error) } }
        item {
            SurfaceCard {
                Section("Live power", "Positive grid power means import; negative means export. Home use is calculated only when solar and grid reports have the same timestamp. Values are the last reports received.")
                MetricRow("Solar production", snapshot.production?.power, "W")
                MetricRow("Grid power", grid?.power, "W")
                MetricRow("Home consumption", snapshot.homeWatts, "W")
                snapshot.production?.let { Text("Solar: ${date(it.timestamp)}", color = Muted, style = MaterialTheme.typography.labelSmall) }
                grid?.let { Text("Grid: ${date(it.timestamp)}", color = Muted, style = MaterialTheme.typography.labelSmall) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Section("Your microinverters")
                Text("${snapshot.inverters.size} reported", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        items(snapshot.inverters, key = { it.serial + it.channelId }) { inverter -> InverterCard(inverter, now) { select(inverter.serial) } }
        if (snapshot.inverters.isEmpty()) item { Text("Inverters will appear after the first authenticated reading.", color = Muted) }
        item {
            val today = snapshot.inverters.takeIf { it.isNotEmpty() && it.all { i -> i.todayWh != null } }?.sumOf { it.todayWh!! / 1000 }
            SurfaceCard {
                Section("Reported energy", "This day total comes from inverter reports for their reported date. Meter cumulative counters are separate in History and CSV.")
                MetricRow("Inverter-reported day total", today, "kWh", 3)
            }
        }
    }
}

@Composable private fun InverterCard(inverter: InverterReading, now: Long, click: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = click), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("IQ8P · ${inverter.serial.takeLast(4)}", fontWeight = FontWeight.SemiBold)
                Text("${number(inverter.watts, 0)} W", color = Teal, fontWeight = FontWeight.Bold)
            }
            Text("AC ${number(inverter.acVoltage)} V   ·   DC ${number(inverter.dcVoltage)} V   ·   ${number(inverter.temperature, 0)} °C", style = MaterialTheme.typography.bodyMedium)
            Text("${age(inverter.timestamp, now)}${if (!isFresh(inverter.timestamp, now, 1200)) " · stale report" else ""}  ›", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}
@Composable private fun InverterDetails(app: MonitorApplication, serial: String, inverter: InverterReading?, now: Long) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SurfaceCard { Section("Last reported measurements"); Text("${date(inverter?.timestamp ?: 0)} · ${age(inverter?.timestamp ?: 0, now)}", color = Muted)
            MetricRow("AC voltage", inverter?.acVoltage, "V", 3); MetricRow("DC voltage", inverter?.dcVoltage, "V", 3)
            MetricRow("AC current", inverter?.acCurrent, "A", 3); MetricRow("DC current", inverter?.dcCurrent, "A", 3)
            MetricRow("Power", inverter?.watts, "W"); MetricRow("Frequency", inverter?.frequency, "Hz", 3)
            MetricRow("Temperature", inverter?.temperature, "°C") } }
        item { HistoryChartCard(app, "Inverter AC voltage", "inverter", "ac_voltage", "V", fixedDevice = serial) }
        item { SurfaceCard { Section("Inverter-reported energy")
            MetricRow("Report day", inverter?.todayWh?.div(1000), "kWh", 3); MetricRow("Previous day", inverter?.yesterdayWh?.div(1000), "kWh", 3)
            MetricRow("Reported week", inverter?.weekWh?.div(1000), "kWh", 3); MetricRow("Reported lifetime", inverter?.lifetimeWh?.div(1000), "kWh", 3)
            Text("Report day: ${date(inverter?.timestamp ?: 0, "dd MMM yyyy")}", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        item { SurfaceCard { Section("Diagnostics", "Gateway flags are raw diagnostic data. A stale evening report or modGone flag alone does not establish an inverter fault.")
            Text("Registered active: ${inverter?.active ?: "unknown"}\nGateway modGone flag: ${inverter?.modGone ?: "unknown"}\nReading flags: ${inverter?.flags ?: "unavailable"}\nRaw signal indicator: ${number(inverter?.signal, 0)}", color = Muted)
        } }
    }
}

private data class HistoryMetric(val label: String, val source: String, val metric: String, val unit: String)
private val metrics = listOf(
    HistoryMetric("Gateway voltage", "combined", "correlation", "V"),
    HistoryMetric("Solar voltage", "meter:production", "voltage", "V"),
    HistoryMetric("Grid power", "meter:net-consumption", "power", "W"),
    HistoryMetric("Solar power", "meter:production", "power", "W"),
    HistoryMetric("Grid current", "meter:net-consumption", "current", "A"),
    HistoryMetric("Grid frequency", "meter:net-consumption", "frequency", "Hz"),
    HistoryMetric("Grid power factor", "meter:net-consumption", "power_factor", "ratio"),
    HistoryMetric("Meter delivered counter", "meter:net-consumption", "delivered_energy", "kWh"),
    HistoryMetric("Meter received counter", "meter:net-consumption", "received_energy", "kWh"),
    HistoryMetric("Inverter AC voltage", "inverter", "ac_voltage", "V"),
    HistoryMetric("Inverter DC voltage", "inverter", "dc_voltage", "V"),
    HistoryMetric("Inverter temperature", "inverter", "temperature", "°C"),
    HistoryMetric("Inverter power", "inverter", "power", "W")
)
@Composable private fun HistoryScreen(app: MonitorApplication) {
    val config by app.settings.state.collectAsState()
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    val metric = metrics[chosen]
    var visibleWindow by remember { mutableStateOf(ChartWindow(nowSeconds() - 3600, nowSeconds())) }
    var customExportWindow by rememberSaveable { mutableStateOf(false) }
    var exportStart by rememberSaveable { mutableLongStateOf(visibleWindow.start) }
    var exportEnd by rememberSaveable { mutableLongStateOf(visibleWindow.end) }
    val selectedStart = if (customExportWindow) exportStart else visibleWindow.start
    val selectedEnd = if (customExportWindow) exportEnd else visibleWindow.end
    val exportError = when {
        selectedEnd <= selectedStart -> "End must be after start."
        selectedEnd > nowSeconds() -> "End cannot be in the future."
        else -> null
    }
    var exportWindow by remember { mutableStateOf(visibleWindow) }
    var exportGateway by remember { mutableStateOf(config.serial) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) exporting = false
        else scope.launch {
            exporting = true
            exportMessage = try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                    stream.bufferedWriter(Charsets.UTF_8).use { CsvExporter.write(app.database.samples(), it, exportGateway, exportWindow.start, exportWindow.end) }
                }
                "CSV saved. It contains all recorded metrics in the selected time range."
            } catch (_: Exception) { "Export failed. Choose a writable destination and try again." }
            exporting = false
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SurfaceCard { Section("Your recorded history", "History keeps a rolling 30 days of readings on this phone. Choose a measurement below to change the chart.")
            ChoiceMenu("Measurement", metrics.map { it.label }, chosen) { chosen = it }
        } }
        item { HistoryChartCard(app, metric.label, metric.source, metric.metric, metric.unit, onWindowChange = { visibleWindow = it }) }
        item { SurfaceCard { Section("Export CSV", "Exports every recorded measurement in the selected window, including source, device and units. Pickers use your phone's local time; the CSV uses UTC timestamps. Only the last 30 days are stored.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showExportDateTimePicker(context, selectedStart) { picked ->
                        if (!customExportWindow) { exportStart = selectedStart; exportEnd = selectedEnd; customExportWindow = true }
                        exportStart = picked
                        exportMessage = null
                    }
                }, enabled = !exporting, modifier = Modifier.weight(1f)) {
                    Column { Text("Start", style = MaterialTheme.typography.labelSmall); Text(date(selectedStart, "dd MMM yyyy")); Text(date(selectedStart, "HH:mm")) }
                }
                OutlinedButton(onClick = {
                    showExportDateTimePicker(context, selectedEnd) { picked ->
                        if (!customExportWindow) { exportStart = selectedStart; exportEnd = selectedEnd; customExportWindow = true }
                        val minuteEnd = picked + 59
                        val currentTime = nowSeconds()
                        exportEnd = if (picked <= currentTime && minuteEnd >= currentTime) currentTime else minuteEnd
                        exportMessage = null
                    }
                }, enabled = !exporting, modifier = Modifier.weight(1f)) {
                    Column { Text("End", style = MaterialTheme.typography.labelSmall); Text(date(selectedEnd, "dd MMM yyyy")); Text(date(selectedEnd, "HH:mm")) }
                }
            }
            if (customExportWindow) TextButton(onClick = { customExportWindow = false; exportMessage = null }, enabled = !exporting) { Text("Use chart window") }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                exportWindow = ChartWindow(selectedStart, selectedEnd); exportGateway = config.serial
                exporting = true; exportMessage = null
                scope.launch {
                    try {
                        val count = withContext(Dispatchers.IO) { app.database.samples().exportCount(exportGateway, exportWindow.start, exportWindow.end) }
                        if (count == 0) { exportMessage = "No saved readings in that time range."; exporting = false }
                        else export.launch("envoy-history-${date(exportWindow.start, "yyyyMMdd-HHmm")}-${date(exportWindow.end, "yyyyMMdd-HHmm")}.csv")
                    } catch (_: Exception) { exportMessage = "Could not check saved readings. Try again."; exporting = false }
                }
            }, enabled = !exporting && exportError == null && config.serial.isNotBlank()) { Text(if (exporting) "Exporting…" else "Save CSV") }
            exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
    }
}

private fun showExportDateTimePicker(context: android.content.Context, initial: Long, onSelected: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochSecond(initial).atZone(zone)
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            onSelected(LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(zone).toEpochSecond())
        }, local.hour, local.minute, true).show()
    }, local.year, local.monthValue - 1, local.dayOfMonth).show()
}

@Composable private fun SettingsScreen(app: MonitorApplication, config: ConnectionSettings, state: MonitorState, snack: SnackbarHostState, toggleMonitor: (Boolean) -> Unit, notifications: () -> Unit) {
    val activity = LocalContext.current as Activity
    DisposableEffect(Unit) { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(config.host) }
    var serial by remember { mutableStateOf(config.serial) }
    var low by remember(config.lowVoltage) { mutableStateOf(config.lowVoltage?.toString().orEmpty()) }
    var high by remember(config.highVoltage) { mutableStateOf(config.highVoltage?.toString().orEmpty()) }
    var candidate by remember { mutableStateOf<PairingCandidate?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val messages = { message: String -> scope.launch { snack.showSnackbar(message) }; Unit }
    candidate?.let { c ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Pair this gateway?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Serial ${c.serial}\nFirmware ${c.firmware}\nAddress ${c.host}")
                Text("SHA-256 certificate fingerprint", fontWeight = FontWeight.SemiBold)
                Text(c.fingerprint.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall)
                Text("Confirm this is your home gateway. The app will trust this certificate only at this address. No token has been sent.")
            } }, confirmButton = { TextButton(onClick = {
                app.settings.pair(c.host, c.serial, c.fingerprint); app.repository.configurationChanged()
                candidate = null; messages("Gateway paired. Save your owner token next.")
            }) { Text("Pair gateway") } }, dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } })
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { AppearanceSettings(app) }
        item { RefreshIntervalSettings(app) }
        item { SurfaceCard {
            Section("1 · Pair your gateway", "Connect to your home Wi-Fi. Inspecting shows the gateway identity and certificate fingerprint before you choose to trust it.")
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Local IPv4 address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = serial, onValueChange = { serial = it }, label = { Text("Gateway serial") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (config.paired) Text("Paired with ${config.serial} at ${config.host}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { scope.launch {
                busy = true
                try { candidate = withContext(Dispatchers.IO) { app.client.inspect(host.trim(), serial.trim()) } }
                catch (e: Exception) { messages(if (e is IllegalArgumentException) e.message ?: "Check the address and serial." else "Could not identify the gateway. Check Wi-Fi, address and serial.") }
                busy = false
            } }, enabled = !busy) { Text(if (busy) "Checking…" else if (config.paired) "Inspect / re-pair" else "Inspect gateway") }
        } }
        item { OwnerTokenCard(app) }
        item { SurfaceCard {
            Section("3 · Voltage warnings", "Alerts use fresh grid-meter voltage. Set your own low and high limits; warnings stay off until you save valid limits.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = low, onValueChange = { low = it }, label = { Text("Low · V") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = high, onValueChange = { high = it }, label = { Text("High · V") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val l = low.toDoubleOrNull(); val h = high.toDoubleOrNull()
                    if (l == null || h == null || !l.isFinite() || !h.isFinite() || l <= 0 || h <= l) messages("Enter positive limits with low below high.")
                    else { app.settings.saveThresholds(l, h); app.repository.configurationChanged(); messages("Voltage limits saved.") }
                }) { Text("Save limits") }
                TextButton(onClick = { app.settings.saveThresholds(null, null); low = ""; high = ""; app.repository.configurationChanged(); messages("Threshold warnings disabled.") }) { Text("Disable") }
            }
            if (!Notifications.allowed(context)) Text("Notifications off · in-app warnings still work", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (!Notifications.allowed(context)) TextButton(onClick = notifications) { Text("Enable notifications") }
        } }
        item { SurfaceCard {
            Section("Recording & privacy", "Background monitoring needs home Wi-Fi and may be stopped by battery restrictions, force-stop or reboot. Reopen the app to resume. Readings stay on this phone for 30 days. The app never changes production settings, and tokens are excluded from exports and logs.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monitor while app is closed", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = state.background, onCheckedChange = toggleMonitor)
            }
        } }
    }
}

@Composable private fun ChoiceMenu(label: String, values: List<String>, selected: Int, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${values.getOrNull(selected).orEmpty()}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEachIndexed { i, text -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(i); expanded = false }) }
        }
    }
}
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Section(text: String, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (help != null) HelpIcon(text, help)
    }
}
@Composable private fun MetricRow(label: String, value: Double?, unit: String, digits: Int = 1) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text("${number(value, digits)} $unit", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Warning(title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = LocalMonitorAccents.current.warningSurface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.SemiBold, color = Amber); Text(text, color = Amber, style = MaterialTheme.typography.bodySmall) }
    }
}
