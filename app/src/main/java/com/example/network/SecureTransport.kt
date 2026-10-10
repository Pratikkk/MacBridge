package com.example.network

import android.util.Base64
import com.example.crypto.IdentityManager
import com.example.manager.DiagnosticLogger
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.model.ProtocolMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/** Phone-initiated pinned TLS, followed by a signed identity challenge inside TLS. */
class SecureTransport(
    private val identityManager: IdentityManager,
    private val scope: CoroutineScope,
    private val onMessageReceived: suspend (ProtocolMessage, PairedDevice) -> Unit,
    private val onDeviceVerified: (PairedDevice) -> Unit,
    private val closeSocket: (SSLSocket) -> Unit = { it.close() }
) {
    companion object {
        // Cleanup must still run if the app's connection scope was cancelled.
        private val socketCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = state.asStateFlow()
    private val lock = Any()
    private var generation = 0L
    private var connectJob: Job? = null
    private var pending: SSLSocket? = null
    private var targetDeviceId: String? = null
    @Volatile private var session: Session? = null

    private class Session(val socket: SSLSocket, val device: PairedDevice) {
        val input = BufferedInputStream(socket.inputStream)
        val output = BufferedOutputStream(socket.outputStream)
        @Volatile var lastAck = System.nanoTime()
    }

    private fun current(token: Long) = synchronized(lock) { generation == token }

    private fun open(device: PairedDevice, secret: String?, token: Long): Session {
        val key = device.pinnedPublicKey.takeIf { it.isNotBlank() }
        if (secret == null) require(key != null) { "This legacy device must be paired again" }
        val socket = PinnedTls.context(device.fingerprint, key).socketFactory.createSocket() as SSLSocket
        try {
            synchronized(lock) {
                check(generation == token) { "Connection cancelled" }
                pending = socket
                targetDeviceId = device.id
                state.value = ConnectionState.Connecting("${device.lastKnownIp}:${device.port}")
            }
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            socket.soTimeout = 10000
            socket.connect(InetSocketAddress(device.lastKnownIp, device.port), 5000)
            synchronized(lock) {
                check(generation == token) { "Connection cancelled" }
                state.value = ConnectionState.Handshaking(device.name, "Pinned TLS and identity proof")
            }
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.first() as X509Certificate
            val publicKey = Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
            val established = Session(socket, device.copy(pinnedPublicKey = publicKey))
            val challenge = JSONObject(WireFrames.read(established.input))
            require(challenge.getString("type") == "AUTH_CHALLENGE" && challenge.getInt("protocolVersion") == 2) { "Unsupported authentication protocol" }
            require(challenge.getString("deviceId") == device.id &&
                challenge.getString("fingerprint") == device.fingerprint &&
                challenge.getString("publicKey") == publicKey) { "Mac identity does not match pairing code" }
            val nonce = challenge.getString("nonce")
            require(Regex("[0-9a-f]{64}").matches(nonce)) { "Invalid authentication challenge" }
            val transcript = "macbridge-auth-v2\n$nonce\n${device.id}\n${device.fingerprint}\n${identityManager.deviceId}\n${identityManager.getFingerprint()}"
            val proof = JSONObject().apply {
                put("type", "AUTH_PROOF")
                put("deviceId", identityManager.deviceId)
                put("deviceName", identityManager.deviceName)
                put("publicKey", identityManager.getPublicKeyBase64())
                put("signature", identityManager.signData(transcript.toByteArray(Charsets.UTF_8)))
                secret?.let { put("secret", it) }
            }
            WireFrames.write(established.output, proof.toString())
            val ack = JSONObject(WireFrames.read(established.input))
            require(ack.getString("type") == "AUTH_OK" && ack.getString("deviceId") == device.id) { "Mac rejected authentication; use a fresh pairing code" }
            socket.soTimeout = 45000
            return established
        } catch (e: Exception) {
            socket.close()
            throw e
        } finally {
            synchronized(lock) { if (pending === socket) pending = null }
        }
    }

    /** Persist a peer only after TLS pinning, challenge response and server approval. */
    suspend fun pairDevice(device: PairedDevice, secret: String, persist: suspend (PairedDevice) -> Unit): PairedDevice {
        disconnect()
        val token = synchronized(lock) { generation }
        var established: Session? = null
        try {
            established = open(device, secret, token)
            check(current(token)) { "Pairing cancelled" }
            persist(established.device)
            install(established, token)
            val connected = established
            synchronized(lock) {
                check(generation == token) { "Pairing cancelled" }
                connectJob = scope.launch(Dispatchers.IO) {
                    try { readSession(connected, token) }
                    catch (e: Exception) { if (current(token)) DiagnosticLogger.w("Transport", "Connection ended: ${e.javaClass.simpleName}") }
                    finally {
                        clear(connected, token)
                        if (current(token)) connectToDevice(connected.device)
                    }
                }
            }
            return established.device
        } catch (e: Exception) {
            established?.socket?.close()
            if (current(token)) state.value = ConnectionState.Disconnected
            throw e
        }
    }

    fun connectToDevice(device: PairedDevice, host: String = device.lastKnownIp, port: Int = device.port) {
        disconnect()
        val token = synchronized(lock) { generation }
        synchronized(lock) {
            if (generation != token) return
            connectJob = scope.launch(Dispatchers.IO) {
                var attempt = 1
                var backoff = 1000L
                while (isActive && current(token)) {
                    var connected: Session? = null
                    try {
                        connected = open(device.copy(lastKnownIp = host, port = port), null, token)
                        install(connected, token)
                        attempt = 1
                        backoff = 1000L
                        readSession(connected, token)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (current(token)) DiagnosticLogger.w("Transport", "Connection failed: ${e.javaClass.simpleName}")
                        // Authentication failures must not be retried or downgraded.
                        if (e is IllegalArgumentException || e is javax.net.ssl.SSLHandshakeException) {
                            if (current(token)) state.value = ConnectionState.Disconnected
                            return@launch
                        }
                    } finally { connected?.let { clear(it, token) } }
                    if (!current(token)) break
                    state.value = ConnectionState.Reconnecting(device.name, attempt++, backoff)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30000L)
                }
            }
        }
    }

    private fun install(connected: Session, token: Long) {
        synchronized(lock) {
            check(generation == token) { "Connection cancelled" }
            session = connected
            state.value = ConnectionState.Connected(connected.device, connected.socket.inetAddress.hostAddress ?: "Mac", connected.device.port, roundTripTimeMs = 0)
        }
        onDeviceVerified(connected.device)
    }

    private suspend fun readSession(connected: Session, token: Long) = coroutineScope {
        val heartbeat = launch(Dispatchers.IO) {
            var seq = 0L
            while (isActive && current(token)) {
                delay(10000)
                if (System.nanoTime() - connected.lastAck > 35_000_000_000L) {
                    connected.socket.close()
                    break
                }
                try { WireFrames.write(connected.output, ProtocolCodec.encode(ProtocolMessage.Heartbeat(++seq))) }
                catch (_: IOException) { connected.socket.close(); break }
            }
        }
        try {
            while (current(token)) {
                val message = ProtocolCodec.decode(WireFrames.read(connected.input))
                    ?: throw IOException("Invalid protocol frame")
                if (message is ProtocolMessage.Heartbeat) {
                    if (message.isAck) {
                        connected.lastAck = System.nanoTime()
                        synchronized(lock) {
                            val value = state.value as? ConnectionState.Connected
                            if (session === connected && value != null) {
                                state.value = value.copy(roundTripTimeMs = (System.currentTimeMillis() - message.timestamp).coerceAtLeast(0))
                            }
                        }
                    } else {
                        WireFrames.write(connected.output, ProtocolCodec.encode(message.copy(isAck = true)))
                    }
                } else if (session === connected) onMessageReceived(message, connected.device)
            }
        } finally { heartbeat.cancel() }
    }

    private fun clear(connected: Session, token: Long) {
        connected.socket.close()
        synchronized(lock) {
            if (generation == token && session === connected) {
                session = null
                state.value = ConnectionState.Disconnected
            }
        }
    }

    fun sendMessage(message: ProtocolMessage, expectedDeviceId: String? = null, expectedSession: Long? = null): Boolean {
        val connected = synchronized(lock) {
            if (expectedSession != null && (state.value as? ConnectionState.Connected)?.connectedSince != expectedSession) return false
            session
        } ?: return false
        if (expectedDeviceId != null && connected.device.id != expectedDeviceId) return false
        return try {
            WireFrames.write(connected.output, ProtocolCodec.encode(message))
            true
        } catch (_: Exception) { false }
    }

    fun disconnect() {
        val sockets = synchronized(lock) {
            generation++
            connectJob?.cancel()
            connectJob = null
            val oldSockets = listOfNotNull(pending, session?.socket).distinct()
            pending = null
            session = null
            targetDeviceId = null
            state.value = ConnectionState.Disconnected
            oldSockets
        }
        // SSLSocket.close() can send TLS close_notify over the network. Detach
        // immediately, then close only captured sockets on IO, outside the lock.
        if (sockets.isNotEmpty()) socketCleanup.launch {
            sockets.forEach { socket -> runCatching { closeSocket(socket) } }
        }
    }

    fun isTargetDevice(id: String): Boolean = synchronized(lock) {
        targetDeviceId == id || (state.value as? ConnectionState.Connected)?.device?.id == id
    }

    fun setSimulatedConnected(device: PairedDevice, rttMs: Long = 9) {
        disconnect()
        state.value = ConnectionState.Connected(device, "Demo peer", device.port, rttMs, isSimulated = true)
    }

    fun stop() = disconnect()
}
