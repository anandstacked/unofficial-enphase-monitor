package com.anand.envoymonitor.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.anand.envoymonitor.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Color(0xFF087F73), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCEDE6), onPrimaryContainer = Color(0xFF123B35),
    secondary = Color(0xFF087F73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E9E5), onSecondaryContainer = Color(0xFF153B35),
    background = Color(0xFFF3F6F6), onBackground = Color(0xFF183138),
    surface = Color.White, onSurface = Color(0xFF183138),
    surfaceVariant = Color(0xFFE6EEEE), onSurfaceVariant = Color(0xFF64767C),
    outline = Color(0xFF73858A), outlineVariant = Color(0xFFCAD7D7)
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8DE8CC), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF145046), onPrimaryContainer = Color(0xFFB0F4E1),
    secondary = Color(0xFF8DE8CC), onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF28483F), onSecondaryContainer = Color(0xFFC9EFE4),
    background = Color(0xFF10191C), onBackground = Color(0xFFE1ECEE),
    surface = Color(0xFF1A272B), onSurface = Color(0xFFE1ECEE),
    surfaceVariant = Color(0xFF2D3B40), onSurfaceVariant = Color(0xFFB3C5CA),
    outline = Color(0xFF899DA3), outlineVariant = Color(0xFF415358)
)
data class MonitorAccentColors(val warningSurface: Color, val warningText: Color, val chartGrid: Color)
private val LightAccents = MonitorAccentColors(Color(0xFFFFEED8), Color(0xFF945918), Color(0xFFE9EEEE))
private val DarkAccents = MonitorAccentColors(Color(0xFF40301E), Color(0xFFFFD39F), Color(0xFF3B4A4F))
val LocalMonitorAccents = staticCompositionLocalOf { LightAccents }

@Composable fun MonitorTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalMonitorAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
