package com.example

import com.example.network.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    private val pin = List(32) { "AB" }.joinToString(":")
    private val service = DiscoveryService("MacBridge-test", BRIDGE_SERVICE)
    private fun record(id: String = "mac", addresses: List<String> = listOf("192.168.1.20"), ipv6: Boolean = true) =
        DiscoveryRecord(id, "My Mac 🌉", pin, addresses, 8990, ipv6)
    private class Backend : DiscoveryBackend {
        data class Listener(val found: (DiscoveryService) -> Unit, val lost: (DiscoveryService) -> Unit, val failed: () -> Unit)
        val listeners = mutableListOf<Listener>()
        val requests = mutableListOf<Pair<DiscoveryService, (DiscoveryRecord?) -> Unit>>()
        var stops = 0
        var failSynchronously = false
        override fun start(found: (DiscoveryService) -> Unit, lost: (DiscoveryService) -> Unit, failed: () -> Unit): () -> Unit {
            listeners.add(Listener(found, lost, failed))
            if (failSynchronously) failed()
            return { stops++ }
        }
        override fun resolve(service: DiscoveryService, result: (DiscoveryRecord?) -> Unit) { requests.add(service to result) }
    }

    @Test fun `service loss late resolution duplicate identities and idle refresh stay bounded`() {
        val backend = Backend()
        var peers = emptyList<DiscoveredPeer>()
        val session = DiscoverySession(backend, "phone", { peers = it }, {})
        session.start(); session.start()
        assertEquals(1, backend.listeners.size)
        val listener = backend.listeners.single()
        listener.found(service); listener.found(service)
        assertEquals(1, backend.requests.size)
        listener.lost(service)
        backend.requests[0].second(record())
        assertTrue(peers.isEmpty())
        listener.found(service)
        backend.requests[1].second(record())
        val first = peers.single()
        listener.found(service)
        backend.requests[2].second(record())
        assertEquals(first, peers.single()) // Re-resolution does not manufacture a timestamp redraw.
        val duplicate = service.copy(name = "MacBridge-duplicate")
        listener.found(duplicate)
        backend.requests[3].second(record())
        assertEquals(1, peers.size)
        listener.lost(service)
        assertEquals(1, peers.size)
        listener.lost(duplicate)
        assertTrue(peers.isEmpty())
        assertEquals(4, backend.requests.size)
        session.stop()
        listener.found(service)
        assertEquals(4, backend.requests.size)
    }

    @Test fun `network restart and failed multicast cannot resurrect old callbacks`() {
        val backend = Backend()
        var peers = emptyList<DiscoveredPeer>()
        var unavailable = false
        val session = DiscoverySession(backend, "phone", { peers = it }, { unavailable = it })
        session.start()
        backend.listeners[0].found(service)
        session.restart()
        backend.requests[0].second(record())
        assertTrue(peers.isEmpty())
        backend.listeners[0].failed()
        assertFalse(unavailable)
        backend.listeners[1].found(service)
        backend.requests[1].second(record(addresses = listOf("192.168.1.30")))
        assertEquals("192.168.1.30", peers.single().host)
        backend.listeners[1].failed()
        assertTrue(unavailable)
        assertTrue(peers.isEmpty())
        backend.failSynchronously = true
        session.start()
        assertEquals(3, backend.stops)
    }

    @Test fun `resolution queue ignores lookalike service types and caps flooding`() {
        val backend = Backend()
        val session = DiscoverySession(backend, "phone", {}, {})
        session.start()
        val listener = backend.listeners.single()
        listener.found(service.copy(type = "_macbridge-evil._tcp."))
        repeat(1000) { listener.found(service.copy(name = "MacBridge-$it")) }
        assertEquals(1, backend.requests.size)
        repeat(32) { backend.requests[it].second(null) }
        assertEquals(32, backend.requests.size)
        session.stop()
    }

    @Test fun `numeric IPv4 IPv6 Unicode and invalid metadata are validated without trusting identity hints`() {
        assertEquals("192.168.1.20", record(addresses = listOf("fd00::20", "192.168.1.20")).toPeer("phone")!!.host)
        assertTrue(record(addresses = listOf("fd00::20")).toPeer("phone")!!.host.contains(":"))
        assertNull(record(addresses = listOf("fd00::20"), ipv6 = false).toPeer("phone"))
        for (host in listOf("localhost", "example.com", "127.0.0.1", "0.0.0.0", "::1", "224.0.0.1"))
            assertNull(record(addresses = listOf(host)).toPeer("phone"))
        assertNull(record("phone").toPeer("phone"))
        assertNull(record().copy(port = 0).toPeer("phone"))
        assertNull(record().copy(port = 65536).toPeer("phone"))
        assertNull(record().copy(fingerprint = "fake").toPeer("phone"))
        assertNull(record().copy(id = "x".repeat(129)).toPeer("phone"))
        assertEquals("My Mac 🌉", record().toPeer("phone")!!.name)
        val peer = record().toPeer("phone")!!
        val saved = PairedDevice("mac", "Saved Mac", pin, "stored-key", "192.168.1.10")
        assertEquals(peer, discoveryEndpoint(saved, listOf(peer)))
        assertNull(discoveryEndpoint(saved.copy(isBlocked = true), listOf(peer)))
        assertNull(discoveryEndpoint(saved, listOf(peer.copy(fingerprintHint = "other-pin"))))
        assertNull(discoveryEndpoint(saved, listOf(peer.copy(id = "unknown"))))
        assertEquals("stored-key", saved.pinnedPublicKey) // Discovery cannot create/change pairing credentials.
    }
}
