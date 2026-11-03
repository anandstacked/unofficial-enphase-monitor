package com.anand.envoymonitor.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.sync.*
import com.anand.envoymonitor.wear.complications.compactPower
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WearActivity : ComponentActivity() {
    private val app get() = application as WearApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SolarWatch(app) }
    }
    override fun onStart() { super.onStart(); app.repository.setVisible(true) }
    override fun onStop() { app.repository.setVisible(false); super.onStop() }
}

private val Mint = Color(0xFF8DE8CC)
private val Amber = Color(0xFFFFC874)
private val Muted = Color(0xFFAAB5BB)
private fun number(value: Double?, unit: String, digits: Int = 1) = value?.let { String.format(Locale.US, "%.${digits}f %s", it, unit) } ?: "Unavailable"
private fun reportTime(timestamp: Long): String = if (timestamp <= 0) "No report" else
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(timestamp))

@Composable private fun SolarWatch(app: WearApplication) {
    val state by app.repository.state.collectAsState()
    var detailSerial by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(nowSeconds()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = nowSeconds() } }
    BackHandler(detailSerial != null || help) { detailSerial = null; help = false }
    val list = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(detailSerial, help) { list.scrollToItem(0); focus.requestFocus() }
    MaterialTheme(colors = Colors(primary = Mint, onPrimary = Color.Black, background = Color.Black, onBackground = Color.White,
        surface = Color(0xFF172226), onSurface = Color.White)) {
        Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 48.dp),
                modifier = Modifier.fillMaxSize().onRotaryScrollEvent {
                    scope.launch { list.scrollBy(it.verticalScrollPixels) }; true
                }.focusRequester(focus).focusable()) {
                val envelope = state.envelope
                val snapshot = envelope?.snapshot
                val inverter = snapshot?.inverters?.firstOrNull { it.serial == detailSerial }
                if (help) {
                    item { Heading("Watch setup") }
                    item { Note("Direct gateway setup is temporarily hidden on the phone. Paired phone readings can still sync to this watch.") }
                    item { Note("Direct access needs the watch connected to your home Wi-Fi. It reads the gateway only while this app is open.") }
                    item { Note("For face updates with both apps closed, enable Continuous monitoring on the phone. Face updates may take five minutes.") }
                    item { Note("Long-press your watch face → Add watch face → Unofficial Enphase Monitor. Allow its four complications to use monitor data. They also work on compatible existing faces.") }
                    item { Note("An expired reading shows no live data. INV DAY is the nine inverters' reported total for today; a previous day's total is hidden.") }
                    item { Action("Back") { help = false } }
                } else if (detailSerial != null) {
                    item { Heading("IQ8P · ${detailSerial!!.takeLast(4)}") }
                    if (inverter == null) item { Note("No cached inverter report available.") }
                    else {
                        item { Text(number(inverter.acVoltage, "V"), color = Mint, fontSize = 30.sp) }
                        item { Note("AC voltage · ${if (isFresh(inverter.timestamp, now, 1200)) "Recent" else "Cached / stale"}", Amber) }
                        item { Note(reportTime(inverter.timestamp)) }
                        item { Metric("Power", inverter.watts?.let(::compactPower) ?: "Unavailable") }
                        item { Metric("DC voltage", number(inverter.dcVoltage, "V")) }
                        item { Metric("AC current", number(inverter.acCurrent, "A", 2)) }
                        item { Metric("DC current", number(inverter.dcCurrent, "A", 2)) }
                        item { Metric("Frequency", number(inverter.frequency, "Hz", 2)) }
                        item { Metric("Temperature", number(inverter.temperature, "°C")) }
                        item { Metric("Reported day", number(inverter.todayWh?.div(1000), "kWh", 2)) }
                        item { Note("Serial ${inverter.serial}") }
                        if (inverter.modGone) item { Note("Gateway modGone flag set. This cached flag alone does not identify a fault.", Amber) }
                    }
                    item { Action("All readings") { detailSerial = null } }
                } else {
                    val grid = snapshot?.grid
                    val fresh = grid?.let { isFresh(it.timestamp, now, 600) } == true
                    val voltage = grid?.voltage
                    val low = envelope?.lowVoltage
                    val high = envelope?.highVoltage
                    val warning = fresh && voltage != null && low != null && high != null && (voltage < low || voltage > high)
                    item { Heading("UNOFFICIAL ENPHASE MONITOR") }
                    item { Text(number(grid?.voltage, "V"), fontSize = if (grid?.voltage == null) 21.sp else 36.sp,
                        fontWeight = FontWeight.Medium, color = if (warning || !fresh) Amber else Mint) }
                    item { Note(if (fresh) "Gateway voltage" else "Cached / no live voltage", if (fresh) Muted else Amber) }
                    grid?.let { item { Note(reportTime(it.timestamp)) } }
                    item { Note(state.status, if (state.busy) Mint else Muted) }
                    snapshot?.let { data ->
                        item { Metric("Solar", data.production?.power?.let(::compactPower) ?: "Unavailable") }
                        data.production?.let { item { Note("${if (isFresh(it.timestamp, now, 600)) "Report" else "Cached"} ${reportTime(it.timestamp)}") } }
                        item { Metric("Grid", grid?.power?.let { "${compactPower(kotlin.math.abs(it))} ${if (it < 0) "export" else "import"}" } ?: "Unavailable") }
                        item { Metric("Home", data.homeWatts?.let(::compactPower) ?: "Unavailable") }
                        item { Metric("Frequency", number(grid?.frequency, "Hz", 2)) }
                        reportedDayEnergy(data, ZoneId.systemDefault())?.let { energy ->
                            item { Metric("Inverter day", number(energy.kwh, "kWh", 2)) }
                            item { Note("Reported ${energy.reportDate} · all 9 inverters") }
                        }
                        if (warning) item { Note("Outside your configured voltage limits", Amber) }
                        data.messages.forEach { message -> item { Note(message, Amber) } }
                    }
                    item { Action(if (state.busy) "Reading…" else "Refresh") { app.repository.refresh() } }
                    item { Heading("Microinverters ${snapshot?.inverters?.size ?: 0}/9") }
                    snapshot?.inverters?.forEach { inv ->
                        item {
                            Chip(onClick = { detailSerial = inv.serial }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("IQ8P · ${inv.serial.takeLast(4)}") }, secondaryLabel = {
                                    Text("${number(inv.acVoltage, "V")} · ${if (isFresh(inv.timestamp, now, 1200)) "recent" else "cached"}")
                                }, colors = ChipDefaults.secondaryChipColors())
                        }
                    }
                    item { Note(if (envelope?.source == ReadingSource.WATCH_WIFI) "Showing watch Wi-Fi readings" else "Showing phone readings") }
                    item { Action("Setup & watch face") { help = true } }
                }
            }
        }
    }
}
@Composable private fun Heading(text: String) { Text(text, color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
@Composable private fun Note(text: String, color: Color = Muted) { Text(text, color = color, fontSize = 12.sp, textAlign = TextAlign.Center) }
@Composable private fun Metric(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 19.sp)
    }
}
@Composable private fun Action(label: String, action: () -> Unit) {
    Chip(onClick = action, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
