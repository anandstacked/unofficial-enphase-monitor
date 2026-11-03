package com.anand.envoymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anand.envoymonitor.MonitorApplication
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.launch

@Composable
fun WatchSetupCard(app: MonitorApplication) {
    val scope = rememberCoroutineScope()
    val config by app.settings.state.collectAsState()
    var watches by remember { mutableStateOf<List<Node>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Node?>(null) }
    selected?.let { node ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Enable direct access on ${node.displayName}?") },
            text = { Text("Send this nearby paired watch your gateway address, serial, certificate pin and owner token. The watch encrypts the token and uses it only for direct home-Wi-Fi readings while its app is open.") },
            confirmButton = { TextButton(onClick = {
                selected = null; watches = emptyList(); busy = true
                scope.launch {
                    message = try {
                        if (app.wearBridge.provision(node.id)) "Watch confirmed setup. Direct Wi-Fi access is ready."
                        else "The watch could not save setup. Open its app and try again."
                    } catch (_: Exception) { "No confirmation received. Keep the watch nearby, open its app and try again." }
                    busy = false
                }
            }) { Text("Send to watch") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
    }
    if (watches.isNotEmpty() && selected == null) {
        AlertDialog(onDismissRequest = { watches = emptyList() }, title = { Text("Choose your nearby watch") },
            text = { Column { watches.forEach { node -> TextButton(onClick = { selected = node }) { Text(node.displayName) } } } },
            confirmButton = { TextButton(onClick = { watches = emptyList() }) { Text("Cancel") } })
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Galaxy Watch setup", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                HelpIcon("Galaxy Watch setup", "Install Unofficial Enphase Monitor on the watch and pair it with this phone over Bluetooth. Phone readings sync without sending the token. Direct home-Wi-Fi readings need one-time watch setup. Keep phone background monitoring on for face updates while both apps are closed.")
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && config.paired, onClick = {
                busy = true
                scope.launch {
                    try {
                        if (!app.settings.hasToken()) message = "Save an owner token in phone Settings before sending direct access."
                        else { watches = app.wearBridge.nearbyWatches(); if (watches.isEmpty()) message = "No nearby Envoy watch app found. Check installation and Bluetooth pairing." }
                    }
                    catch (_: Exception) { message = "Watch discovery unavailable. Check Google Play services and Bluetooth pairing." }
                    busy = false
                }
            }) { Text(if (busy) "Waiting for watch…" else "Set up nearby watch") }
        }
    }
}
