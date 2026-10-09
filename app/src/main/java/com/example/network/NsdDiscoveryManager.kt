package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.manager.DiagnosticLogger
import com.example.model.DiscoveredPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Phase 2: Network Service Discovery (mDNS)
 * Discovery is untrusted: mDNS results are only hints for connecting.
 * Real identity is strictly authenticated via TLS pinned certificates.
 */
class NsdDiscoveryManager(
    private val context: Context,
    private val deviceId: String,
    private val deviceFingerprint: String
) {
    private val TAG = "NsdDiscoveryManager"
    private val SERVICE_TYPE = "_macbridge._tcp."
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isRegistered = false
    private var isDiscovering = false

    fun startAdvertising(localPort: Int, deviceName: String) {
        if (nsdManager == null) {
            DiagnosticLogger.w(TAG, "NsdManager unavailable on this device")
            return
        }
        if (isRegistered) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "MacBridge-$deviceName"
            serviceType = SERVICE_TYPE
            port = localPort
            setAttribute("id", deviceId)
            setAttribute("fingerprint", deviceFingerprint)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                isRegistered = true
                DiagnosticLogger.i(TAG, "mDNS Service advertised: ${service.serviceName} on port ${service.port}")
            }

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                DiagnosticLogger.w(TAG, "mDNS advertising failed with code: $errorCode")
            }

            override fun onServiceUnregistered(service: NsdServiceInfo) {
                isRegistered = false
                DiagnosticLogger.i(TAG, "mDNS Service unregistered")
            }

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                DiagnosticLogger.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            DiagnosticLogger.e(TAG, "Error registering mDNS service: ${e.message}")
        }
    }

    fun startDiscovery() {
        if (nsdManager == null) return
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                DiagnosticLogger.w(TAG, "mDNS discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                DiagnosticLogger.w(TAG, "mDNS stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                DiagnosticLogger.i(TAG, "mDNS discovery started for $regType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                DiagnosticLogger.i(TAG, "mDNS discovery stopped")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                DiagnosticLogger.d(TAG, "Found potential service: ${service.serviceName}")
                if (service.serviceType == SERVICE_TYPE || service.serviceType.startsWith("_macbridge")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                DiagnosticLogger.d(TAG, "Service lost: ${service.serviceName}")
                _discoveredPeers.value = _discoveredPeers.value.filterNot { it.name == service.serviceName }
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            DiagnosticLogger.e(TAG, "Error initiating discovery: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                DiagnosticLogger.w(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                val host = service.host?.hostAddress ?: return
                val port = service.port
                val peerId = service.attributes["id"]?.let { String(it) } ?: service.serviceName
                val fp = service.attributes["fingerprint"]?.let { String(it) }

                // Ignore advertising of self
                if (peerId == deviceId) return

                val peer = DiscoveredPeer(
                    id = peerId,
                    name = service.serviceName.removePrefix("MacBridge-"),
                    host = host,
                    port = port,
                    fingerprintHint = fp
                )

                val current = _discoveredPeers.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.id == peer.id }
                if (existingIndex >= 0) {
                    current[existingIndex] = peer
                } else {
                    current.add(peer)
                }
                _discoveredPeers.value = current
                DiagnosticLogger.i(TAG, "Resolved mDNS peer: ${peer.name} at ${peer.host}:${peer.port}")
            }
        })
    }

    fun stop() {
        try {
            if (isRegistered && registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
                isRegistered = false
            }
            if (isDiscovering && discoveryListener != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                isDiscovering = false
            }
        } catch (e: Exception) {
            DiagnosticLogger.w(TAG, "Error stopping NSD: ${e.message}")
        }
    }
}
