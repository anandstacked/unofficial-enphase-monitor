package com.anand.envoymonitor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.anand.envoymonitor.data.ConnectionSettings
import com.anand.envoymonitor.sync.isPrivateIpv4
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class PairingCandidate(val host: String, val serial: String, val firmware: String, val fingerprint: String)
class GatewayFailure(val code: Int, message: String) : Exception(message)

class GatewayClient(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var requestedNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { requestedNetwork = network }
        override fun onLost(network: Network) { if (requestedNetwork == network) requestedNetwork = null }
    }
    private var requested = false
    fun requestWifi() {
        if (!requested) {
            connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            requested = true
        }
    }
    fun releaseWifi() {
        if (requested) { runCatching { connectivity.unregisterNetworkCallback(callback) }; requested = false; requestedNetwork = null }
    }
    suspend fun awaitWifi() {
        requestWifi()
        withTimeout(15000) {
            while (runCatching { wifiNetwork() }.isFailure) delay(200)
        }
    }
    fun wifiNetwork(): Network = requestedNetwork ?: connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: throw GatewayFailure(0, "Connect this device to your home Wi-Fi.")
    private fun validateHost(host: String) {
        require(isPrivateIpv4(host)) { "Enter the gateway's local address, such as 192.168.1.25." }
    }
    private fun digest(cert: X509Certificate) = certificateFingerprint(cert.encoded)
    private fun client(host: String, fingerprint: String?, observed: ((X509Certificate) -> Unit)? = null): OkHttpClient {
        validateHost(host)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificates are not supported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Missing gateway certificate")
                if (fingerprint != null) requireCertificatePin(chain[0].encoded, fingerprint)
                observed?.invoke(chain[0])
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { name, _ -> name == host }
            .socketFactory(wifiNetwork().socketFactory).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
    suspend fun inspect(host: String, expectedSerial: String = ""): PairingCandidate {
        require(expectedSerial.isEmpty() || expectedSerial.matches(Regex("[0-9]{12}"))) { "Enter the 12-digit gateway serial or leave it blank." }
        var certificate: X509Certificate? = null
        val client = client(host, null) { certificate = it }
        val request = Request.Builder().url("https://$host/info").build()
        val xml = try {
            awaitGatewayResponse(client.newCall(request), maxBodyBytes = 65_536)
        } catch (e: GatewayFailure) {
            if (e.code == 401 || e.code == 403) throw GatewayFailure(e.code, "This device did not share its gateway identity.")
            throw e
        }
        val serial = Regex("<sn>([0-9]{12})</sn>").find(xml)?.groupValues?.get(1)
        if (serial == null || !Regex("""\A\s*(?:<\?xml[^?]*\?>\s*)?<envoy_info(?:\s|>)""").containsMatchIn(xml)) {
            throw GatewayFailure(0, "This address did not identify an Enphase gateway.")
        }
        if (expectedSerial.isNotEmpty() && serial != expectedSerial) {
            throw GatewayFailure(0, "Gateway serial does not match. Check the address and serial.")
        }
        val cert = certificate ?: throw CertificateException("Missing certificate")
        return PairingCandidate(host, serial, Regex("<software>([^<]+)</software>").find(xml)?.groupValues?.get(1) ?: "Unknown", digest(cert))
    }
    suspend fun get(settings: ConnectionSettings, token: String, path: String): String {
        require(settings.paired) { "Pair the gateway before entering a token." }
        require(path in setOf("/info", "/ivp/meters", "/ivp/meters/readings", "/ivp/pdm/device_data", "/api/v1/production/inverters"))
        val request = Request.Builder().url("https://${settings.host}$path").header("Accept", "application/json")
            .header("Authorization", "Bearer $token").build()
        return awaitGatewayResponse(client(settings.host, settings.fingerprint).newCall(request))
    }
}
