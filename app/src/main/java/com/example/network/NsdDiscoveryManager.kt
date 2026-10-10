package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.model.DiscoveredPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** Callback-driven LAN hints. Pairing and pinned TLS are always required independently. */
class NsdDiscoveryManager(context: Context, deviceId: String) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = peers
    private val failed = MutableStateFlow(false)
    val discoveryUnavailable: StateFlow<Boolean> = failed
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var active = false
    @Volatile private var services = java.util.concurrent.ConcurrentHashMap<DiscoveryService, NsdServiceInfo>()
    private fun key(info: NsdServiceInfo) = DiscoveryService(info.serviceName, info.serviceType)
    private fun text(info: NsdServiceInfo, field: String): String {
        val bytes = info.attributes[field] ?: return ""
        if (bytes.size > 255) return ""
        return runCatching { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString() }.getOrDefault("")
    }
    private val session = DiscoverySession(object : DiscoveryBackend {
        override fun start(found: (DiscoveryService) -> Unit, lost: (DiscoveryService) -> Unit, failed: () -> Unit): () -> Unit {
            val manager = nsd ?: throw IllegalStateException("Discovery unavailable")
            val current = java.util.concurrent.ConcurrentHashMap<DiscoveryService, NsdServiceInfo>()
            services = current
            val alive = java.util.concurrent.atomic.AtomicBoolean(true)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { failed() }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!alive.get()) return
                    val service = key(info)
                    if (service.type.trimEnd('.') != BRIDGE_SERVICE.trimEnd('.') || service.name.isBlank() || service.name.length > 256) return
                    if (current.size >= 32 && !current.containsKey(service)) return
                    current[service] = info
                    found(service)
                }
                override fun onServiceLost(info: NsdServiceInfo) { if (alive.get()) { val service = key(info); current.remove(service); lost(service) } }
            }
            manager.discoverServices(BRIDGE_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            return { alive.set(false); current.clear(); manager.stopServiceDiscovery(listener) }
        }
        override fun resolve(service: DiscoveryService, result: (DiscoveryRecord?) -> Unit) {
            val info = services[service] ?: run { result(null); return }
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(value: NsdServiceInfo, code: Int) { result(null) }
                override fun onServiceResolved(value: NsdServiceInfo) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) value.hostAddresses.ifEmpty { listOfNotNull(value.host) } else listOfNotNull(value.host)
                    result(DiscoveryRecord(text(value, "id"), text(value, "name").ifBlank { value.serviceName },
                        text(value, "fingerprint"), addresses.mapNotNull { it.hostAddress }, value.port, text(value, "ipv6") == "1"))
                }
            }) ?: result(null)
        }
    }, deviceId, { peers.value = it }, { failed.value = it })

    @Synchronized fun startDiscovery() {
        active = true
        session.start()
        if (networkCallback == null && connectivity != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkChanged() }
                override fun onLost(network: Network) { networkChanged() }
            }
            try { connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback }
            catch (_: RuntimeException) { /* Discovery and pasted pairing can still work without network callbacks. */ }
        }
    }
    @Synchronized private fun networkChanged() { if (active) session.restart() }
    fun refreshDiscovery() { networkChanged() }
    @Synchronized fun stop() {
        active = false
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        networkCallback = null
        session.stop()
    }
}
