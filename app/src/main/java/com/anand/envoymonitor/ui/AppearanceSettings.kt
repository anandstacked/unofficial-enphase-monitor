package com.anand.envoymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anand.envoymonitor.MonitorApplication
import com.anand.envoymonitor.R
import com.anand.envoymonitor.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppearanceSettings(app: MonitorApplication) {
    val selected by app.themePreferences.mode.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Appearance", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                HelpIcon("Appearance", "System follows your phone's theme. Sun keeps the app light; moon keeps it dark.")
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    val drawable = when (mode) {
                        ThemeMode.SYSTEM -> R.drawable.ic_theme_system
                        ThemeMode.LIGHT -> R.drawable.ic_theme_light
                        ThemeMode.DARK -> R.drawable.ic_theme_dark
                    }
                    SegmentedButton(selected = selected == mode, onClick = { app.themePreferences.select(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        modifier = Modifier.weight(1f), icon = {}) {
                        Icon(painterResource(drawable), contentDescription = mode.label)
                    }
                }
            }
        }
    }
}
