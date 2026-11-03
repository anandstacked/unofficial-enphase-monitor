package com.anand.envoymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anand.envoymonitor.MonitorApplication
import com.anand.envoymonitor.data.*

@Composable fun RefreshIntervalSettings(app: MonitorApplication) {
    val saved by app.pollingPreferences.intervalSeconds.collectAsState()
    var input by rememberSaveable(saved) { mutableStateOf(saved.toString()) }
    val parsed = parseRefreshSeconds(input)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Refresh interval", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                HelpIcon("Refresh interval", "Checks run while the app is open and, if enabled, during background monitoring. The delay starts after each check. Short intervals use more battery and Wi-Fi; the gateway may repeat an unchanged report.")
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text("Seconds · current $saved") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = input.isNotBlank() && parsed == null,
                supportingText = if (input.isNotBlank() && parsed == null) { { Text("Use $MIN_REFRESH_SECONDS–$MAX_REFRESH_SECONDS whole seconds.") } } else null,
                modifier = Modifier.fillMaxWidth())
            Button(enabled = parsed != null && parsed != saved, onClick = { parsed?.let(app.pollingPreferences::save) }) {
                Text("Save interval")
            }
        }
    }
}
