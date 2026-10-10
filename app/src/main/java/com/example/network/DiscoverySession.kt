package com.example.network

import com.example.model.DiscoveredPeer
import com.example.model.PairedDevice
import java.net.InetAddress
import java.util.Locale

internal const val BRIDGE_SERVICE = "_macbridge._tcp."
internal data class DiscoveryService(val name: String, val type: String)
internal data class DiscoveryRecord(val id: String, val name: String, val fingerprint: String,
    val addresses: List<String>, val port: Int, val ipv6: Boolean)
internal interface DiscoveryBackend {
    fun start(found: (DiscoveryService) -> Unit, lost: (DiscoveryService) -> Unit, failed: () -> Unit): () -> Unit
    fun resolve(service: DiscoveryService, result: (DiscoveryRecord?) -> Unit)
}

/** Bounded, serialized resolution; late callbacks cannot revive lost services or stopped sessions. */
internal class DiscoverySession(private val backend: DiscoveryBackend, private val selfId: String,
    private val publish: (List<DiscoveredPeer>) -> Unit, private val unavailable: (Boolean) -> Unit) {
    private var generation = 0L
    private var sequence = 0L
    private var stopBackend: (() -> Unit)? = null
    private var started = false
    private var resolving: Long? = null
    private data class Entry(val version: Long, var peer: DiscoveredPeer? = null, var queued: Boolean = true)
    private val entries = linkedMapOf<DiscoveryService, Entry>()

    @Synchronized fun start() {
        if (started) return
        started = true
        val token = ++generation
        unavailable(false)
        try {
            val stop = backend.start({ found(token, it) }, { lost(token, it) }, { fail(token) })
            if (token == generation && started) stopBackend = stop else stop()
        } catch (_: RuntimeException) { fail(token) }
    }
    @Synchronized fun stop() {
        generation++
        started = false
        val previous = stopBackend
        stopBackend = null
        resolving = null
        entries.clear()
        publish(emptyList())
        try { previous?.invoke() } catch (_: RuntimeException) { /* Pasted-code pairing remains available. */ }
    }
    @Synchronized fun restart() { stop(); start() }
    @Synchronized private fun fail(token: Long) {
        if (token != generation) return
        stop()
        unavailable(true)
    }
    @Synchronized private fun found(token: Long, service: DiscoveryService) {
        if (token != generation || !started || service.type.trimEnd('.') != BRIDGE_SERVICE.trimEnd('.') || service.name.isBlank() || service.name.length > 256) return
        if (!entries.containsKey(service) && entries.size >= 32) return
        if (entries[service]?.queued == true || (resolving != null && entries[service]?.version == resolving)) return
        entries[service] = Entry(++sequence, entries[service]?.peer)
        drain(token)
    }
    @Synchronized private fun lost(token: Long, service: DiscoveryService) {
        if (token != generation) return
        entries.remove(service)
        emit()
    }
    private fun emit() { publish(entries.values.mapNotNull { it.peer }.distinctBy { it.id }) }
    private fun drain(token: Long) {
        if (resolving != null || !started) return
        val next = entries.entries.firstOrNull { it.value.queued } ?: return
        val service = next.key
        val version = next.value.version
        next.value.queued = false
        resolving = version
        try { backend.resolve(service) { resolved(token, service, version, it) } }
        catch (_: RuntimeException) { resolved(token, service, version, null) }
    }
    @Synchronized private fun resolved(token: Long, service: DiscoveryService, version: Long, value: DiscoveryRecord?) {
        if (token != generation || resolving != version) return
        resolving = null
        val entry = entries[service]?.takeIf { it.version == version }
        if (entry != null) {
            val peer = value?.toPeer(selfId)
            // Preserve observation time for equal data: StateFlow must stay quiet when idle.
            entry.peer = if (peer != null && entry.peer?.copy(lastSeenTimestamp = peer.lastSeenTimestamp) == peer) entry.peer else peer
            emit()
        }
        drain(token)
    }
}

internal fun DiscoveryRecord.toPeer(selfId: String): DiscoveredPeer? {
    if (id.isBlank() || id == selfId || id.length > 128 || id.any { it.isISOControl() } || port !in 1..65535 ||
        !fingerprint.matches(Regex("(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}"))) return null
    val hosts = addresses.take(16).mapNotNull { raw ->
        // NSD supplies numeric addresses; never perform attacker-controlled hostname DNS here.
        if (raw.length > 128 || !raw.matches(Regex("[0-9a-fA-F:.%a-zA-Z0-9_-]+")) || (!raw.contains(':') && !raw.matches(Regex("[0-9.]+")))) null
        else runCatching { InetAddress.getByName(raw) }.getOrNull()?.takeUnless { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
    }
    val host = hosts.firstOrNull { it.address.size == 4 } ?: hosts.firstOrNull { ipv6 && it.address.size == 16 } ?: return null
    return DiscoveredPeer(id, name.filterNot { it.isISOControl() }.take(100).ifBlank { "Nearby Mac" },
        host.hostAddress ?: return null, port, fingerprint.uppercase(Locale.ROOT))
}

/** Discovery is only an address hint. TLS still authenticates against the stored key and pin. */
internal fun discoveryEndpoint(saved: PairedDevice, peers: List<DiscoveredPeer>): DiscoveredPeer? =
    if (saved.isBlocked) null else peers.firstOrNull { it.id == saved.id && it.fingerprintHint == saved.fingerprint }
