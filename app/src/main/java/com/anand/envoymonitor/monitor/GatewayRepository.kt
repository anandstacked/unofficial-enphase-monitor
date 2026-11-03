package com.anand.envoymonitor.monitor

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.anand.envoymonitor.data.*
import com.anand.envoymonitor.network.GatewayClient
import com.anand.envoymonitor.network.GatewayFailure
import com.anand.envoymonitor.network.GatewayFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MonitorState(
    val snapshot: Snapshot = Snapshot(), val status: String = "Pair your gateway in Settings",
    val collecting: Boolean = false, val background: Boolean = false, val lastSuccess: Long = 0,
    val error: String? = null, val nextCheck: Long = 0,
    val connection: ConnectionStatus = ConnectionStatus.NOT_TESTED, val connectionMessage: String = "Connection has not been tested"
)
fun nowSeconds() = System.currentTimeMillis() / 1000

class GatewayRepository(
    private val context: Context, private val settings: SettingsStore,
    private val dao: SampleDao, private val client: GatewayClient,
    private val pollingPreferences: PollingPreferences, previous: Snapshot? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MonitorState(snapshot = previous ?: Snapshot()))
    val state = mutable.asStateFlow()
    private val pollMutex = Mutex()
    private var visible = false
    private var background = false
    private var loop: Job? = null
    private val fetcher = GatewayFetcher(client)
    private var alerts = VoltageAlertTracker(settings.alertState())
    private var refreshRequested = false
    private var testing = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch { pollingPreferences.intervalSeconds.drop(1).collect { wake.trySend(Unit) } }
    }
    fun setVisible(value: Boolean) { visible = value; reconcile() }
    fun setBackground(value: Boolean) { background = value; reconcile() }
    fun configurationChanged() {
        loop?.cancel(); loop = null; fetcher.reset()
        alerts = VoltageAlertTracker(settings.alertState())
        mutable.value = mutable.value.copy(snapshot = Snapshot(), error = null, lastSuccess = 0,
            connection = ConnectionStatus.NOT_TESTED, connectionMessage = "Connection has not been tested")
        reconcile()
    }
    fun refresh() { scope.launch {
        if (loop?.isActive == true) { refreshRequested = true; wake.trySend(Unit) }
    } }
    suspend fun saveTokenAndTest(input: String? = null): Boolean = withContext(Dispatchers.Main.immediate) {
        check(!testing) { "Connection test already running" }
        testing = true
        var attempted = false
        var failed = false
        try {
            loop?.cancelAndJoin(); loop = null
            mutable.value = mutable.value.copy(collecting = false, connection = ConnectionStatus.TESTING,
                connectionMessage = "Testing gateway access…", status = "Testing gateway access…", error = null, nextCheck = 0)
            if (input != null) withContext(Dispatchers.IO) {
                try { settings.saveToken(input) }
                catch (e: IllegalArgumentException) { throw e }
                catch (_: Exception) { throw GatewayFailure(0, "Could not save the encrypted token. Try again.") }
            }
            val config = settings.state.value
            val token = settings.token() ?: throw IllegalArgumentException("No saved token")
            require(config.paired)
            fetcher.reset()
            attempted = true
            withTimeout(45000) { checkGateway(config, token) }
            true
        } catch (e: TimeoutCancellationException) {
            failed = true; reportProblem(e); false
        } catch (e: CancellationException) {
            mutable.value = mutable.value.copy(connection = ConnectionStatus.NOT_TESTED, connectionMessage = "Connection test cancelled")
            throw e
        } catch (e: Exception) {
            failed = true; reportProblem(e); false
        } finally {
            testing = false
            reconcile(if (attempted) if (failed) 1 else 0 else null)
        }
    }
    private fun reportProblem(error: Exception) {
        val problem = connectionProblem(error, settings.tokenState.value.metadata, nowSeconds())
        mutable.value = mutable.value.copy(status = if (problem.status == ConnectionStatus.AUTH_ERROR) "Authentication error" else "Gateway unavailable",
            error = problem.message, connection = problem.status, connectionMessage = problem.message)
    }
    private fun reconcile(testFailures: Int? = null) {
        val wanted = visible || background
        mutable.value = mutable.value.copy(background = background)
        if (testing) return
        if (!wanted) {
            loop?.cancel(); loop = null; refreshRequested = false; client.releaseWifi()
            mutable.value = mutable.value.copy(collecting = false, status = "Monitoring paused", nextCheck = 0)
            return
        }
        if (loop?.isActive == true) return
        refreshRequested = false
        loop = scope.launch {
            var failures = testFailures ?: 0
            if (testFailures != null) {
                mutable.value = mutable.value.copy(collecting = testFailures == 0)
                waitForNextCheck(failures)
            }
            while (isActive) {
                val config = settings.state.value
                val token = settings.token()
                if (!config.paired || token == null) {
                    mutable.value = mutable.value.copy(collecting = false, status = "Complete connection setup in Settings", nextCheck = 0)
                    delay(1000); continue
                }
                client.requestWifi()
                mutable.value = mutable.value.copy(collecting = true, status = "Checking gateway…", nextCheck = 0,
                    connection = if (mutable.value.connection == ConnectionStatus.NOT_TESTED) ConnectionStatus.TESTING else mutable.value.connection)
                try {
                    checkGateway(config, token)
                    failures = 0
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failures++
                    reportProblem(error)
                }
                waitForNextCheck(failures)
            }
        }
    }
    private suspend fun waitForNextCheck(failures: Int) {
        val schedule = PollingSchedule(SystemClock.elapsedRealtime())
        while (currentCoroutineContext().isActive && !refreshRequested) {
            val interval = pollingPreferences.intervalSeconds.value
            val remaining = schedule.remainingMillis(SystemClock.elapsedRealtime(), interval, failures)
            mutable.value = mutable.value.copy(nextCheck = nowSeconds() + (remaining + 999) / 1000,
                status = if (mutable.value.error == null) "Connected · refresh interval $interval s" else mutable.value.status)
            if (remaining == 0L) break
            withTimeoutOrNull(remaining) { wake.receive() }
        }
        refreshRequested = false
    }
    private suspend fun checkGateway(config: ConnectionSettings, token: String) = pollMutex.withLock {
        val snapshot = collect(config, token)
        currentCoroutineContext().ensureActive()
        if (settings.state.value != config || settings.token() != token) throw GatewayFailure(0, "Connection settings changed. Test the saved token again.")
        val now = nowSeconds()
        withContext(Dispatchers.IO) { dao.insert(HistoryMapper.samples(snapshot, config.serial)); dao.prune(now - 30L * 86400) }
        val priorAlert = alerts.state
        alerts.accept(snapshot.grid, now, config.lowVoltage, config.highVoltage)?.let { Notifications.voltageAlert(context, it, snapshot.grid!!.voltage!!) }
        if (alerts.state != priorAlert) withContext(Dispatchers.IO) { settings.saveAlertState(alerts.state) }
        mutable.value = mutable.value.copy(snapshot = snapshot, lastSuccess = now, connection = ConnectionStatus.CONNECTED,
            connectionMessage = if (snapshot.messages.isEmpty()) "Connected · authenticated gateway readings received" else "Connected · authenticated; some readings unavailable",
            status = if (snapshot.messages.isNotEmpty()) "Connected · some readings unavailable" else "Connected · refresh interval ${pollingPreferences.intervalSeconds.value} s",
            error = snapshot.messages.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
    private suspend fun collect(config: ConnectionSettings, token: String): Snapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wake = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnvoyVoltage:sample")
        wake.acquire(90000)
        try {
            return fetcher.fetch(config, token)
        } finally { if (wake.isHeld) wake.release() }
    }
}
