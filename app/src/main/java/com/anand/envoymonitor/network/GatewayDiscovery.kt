package com.anand.envoymonitor.network

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.anand.envoymonitor.sync.isPrivateIpv4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume

private const val ENVOY_SERVICE = "_enphase-envoy._tcp."

internal suspend fun findGateways(context: Context, client: GatewayClient): List<PairingCandidate> = coroutineScope {
    val network = client.wifiNetwork()
    val localName = async(Dispatchers.IO) {
        try { network.getAllByName("envoy.local").toList().privateIpv4Addresses() }
        catch (_: UnknownHostException) { emptyList() }
    }
    val advertised = discoverEnvoyAddresses(context, network)
    val addresses = (advertised + localName.await()).distinct().take(8)
    addresses.map { address ->
        async {
            withTimeoutOrNull(5_000) {
                try { client.inspect(address) }
                catch (_: IOException) { null }
                catch (_: GatewayFailure) { null }
            }
        }
    }.awaitAll().filterNotNull().distinctBy { it.serial to it.fingerprint }.sortedBy { it.serial }
}

private fun List<InetAddress>.privateIpv4Addresses(): List<String> =
    filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.filter(::isPrivateIpv4)

private suspend fun discoverEnvoyAddresses(context: Context, network: Network): List<String> {
    val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
    val multicastLock = runCatching {
        context.getSystemService(WifiManager::class.java)?.createMulticastLock("envoy-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
    try {
        val services = mutableListOf<NsdServiceInfo>()
        withTimeoutOrNull(3_000) { envoyServices(context, manager, network).toList(services) }
        val addresses = mutableListOf<String>()
        withTimeoutOrNull(4_000) {
            for (service in services.distinctBy { it.serviceName }.take(8)) {
                addresses += resolveEnvoy(manager, service).privateIpv4Addresses()
            }
        }
        return addresses
    } finally {
        multicastLock?.release()
    }
}

private fun envoyServices(context: Context, manager: NsdManager, network: Network) = callbackFlow {
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onServiceFound(service: NsdServiceInfo) { trySend(service) }
        override fun onServiceLost(service: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(type: String) { close() }
        override fun onStartDiscoveryFailed(type: String, error: Int) { close() }
        override fun onStopDiscoveryFailed(type: String, error: Int) { close() }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        manager.discoverServices(ENVOY_SERVICE, NsdManager.PROTOCOL_DNS_SD, network, context.mainExecutor, listener)
    } else {
        manager.discoverServices(ENVOY_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
}

@Suppress("DEPRECATION") // One-shot resolution also supports Android 10–13.
private suspend fun resolveEnvoy(manager: NsdManager, service: NsdServiceInfo): List<InetAddress> =
    suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { continuation.resume(emptyList()) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                continuation.resume(addresses)
            }
        }
        manager.resolveService(service, listener)
        if (Build.VERSION.SDK_INT >= 34) {
            continuation.invokeOnCancellation { runCatching { manager.stopServiceResolution(listener) } }
        }
    }
