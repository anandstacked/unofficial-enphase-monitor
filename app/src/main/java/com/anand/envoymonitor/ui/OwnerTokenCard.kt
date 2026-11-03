package com.anand.envoymonitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anand.envoymonitor.MonitorApplication
import com.anand.envoymonitor.data.tokenDuration
import com.anand.envoymonitor.monitor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun OwnerTokenCard(app: MonitorApplication) {
    val config by app.settings.state.collectAsState()
    val credential by app.settings.tokenState.collectAsState()
    val state by app.repository.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(nowSeconds()) }
    LaunchedEffect(Unit) { while (true) { now = nowSeconds(); delay(1000) } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun test(value: String?) {
        checking = true; localError = null
        scope.launch {
            try { val connected = app.repository.saveTokenAndTest(value); if (connected && value != null) input = "" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { localError = "Could not complete the connection test. Try again." }
            finally { checking = false }
        }
    }
    fun time(seconds: Long) = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
    val status = when (state.connection) {
        ConnectionStatus.NOT_TESTED -> "Not tested"
        ConnectionStatus.TESTING -> "Testing…"
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.AUTH_ERROR -> "Authentication error"
        ConnectionStatus.UNAVAILABLE -> "Connection unavailable"
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("2 · Local access token", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                HelpIcon("Local access token", "Get a token with your system-owner account in the browser; your password never enters this app. Enphase sets the expiry. A reported expiry does not verify access, so use Test saved token. If rejected early, check phone and gateway time and owner permissions. After replacing a token, send access to the watch again.")
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Local access token") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !checking, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { test(input) }, enabled = config.paired && input.isNotBlank() && !checking) {
                Text(if (checking) "Testing connection…" else "Save & test connection")
            }
            OutlinedButton(onClick = { test(null) }, enabled = config.paired && credential.saved && !checking) { Text("Test saved token") }
            Text("Connection: $status", fontWeight = FontWeight.SemiBold,
                color = if (state.connection == ConnectionStatus.AUTH_ERROR || state.connection == ConnectionStatus.UNAVAILABLE)
                    LocalMonitorAccents.current.warningText else MaterialTheme.colorScheme.primary)
            Text(localError ?: state.connectionMessage, style = MaterialTheme.typography.bodySmall)
            Text(if (credential.saved) "Token encrypted and saved." else "No token saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            credential.metadata?.let { metadata ->
                metadata.role?.let { Text("Reported token role: $it", style = MaterialTheme.typography.bodySmall) }
                metadata.issuedAt?.let { Text("Issued: ${time(it)}", style = MaterialTheme.typography.bodySmall) }
                metadata.lifetimeSeconds?.let { Text("Issued lifetime: ${tokenDuration(it)}", style = MaterialTheme.typography.bodySmall) }
                metadata.expiresAt?.let { expiry ->
                    Text("Expires: ${time(expiry)}", style = MaterialTheme.typography.bodySmall)
                    Text("Remaining: ${tokenDuration(expiry - now)}", color = if (expiry <= now + 3600) LocalMonitorAccents.current.warningText else MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: Text("Expiry is not reported in this token.", style = MaterialTheme.typography.bodySmall)
                metadata.warning(now, config.serial)?.let { Text(it, color = LocalMonitorAccents.current.warningText, style = MaterialTheme.typography.bodySmall) }
            } ?: run { if (credential.saved) Text("Token details could not be decoded. Use the connection test to verify access.", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                val uri = Uri.parse("https://enlighten.enphaseenergy.com/entrez-auth-token").buildUpon().appendQueryParameter("serial_num", config.serial).build()
                try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { localError = "Could not open the browser. Open Enlighten to generate an owner token." }
            }, enabled = !checking) { Text("Get owner token in browser") }
        }
    }
}
