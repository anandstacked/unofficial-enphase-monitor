package com.anand.envoymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anand.envoymonitor.ui.MonitorApp

class MainActivity : ComponentActivity() {
    private val app get() = application as MonitorApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MonitorApp(app) }
    }
    override fun onStart() { super.onStart(); app.repository.setVisible(true) }
    override fun onStop() { app.repository.setVisible(false); super.onStop() }
}
