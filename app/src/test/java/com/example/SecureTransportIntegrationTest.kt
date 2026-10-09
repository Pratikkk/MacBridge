package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.IdentityManager
import com.example.model.ConnectionState
import com.example.model.PairedDevice
import com.example.model.ProtocolMessage
import com.example.network.PairingCode
import com.example.network.SecureTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real SSLSocket -> Python TLS server; requires local Python 3 and OpenSSL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecureTransportIntegrationTest {
    @Test
    fun `UI disconnect detaches immediately and TLS cleanup survives connection scope cancellation`() = withMac { code ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closed = CompletableDeferred<Thread>()
        val transport = SecureTransport(IdentityManager(context, true), scope, { _, _ -> }, {},
            closeSocket = { socket ->
                closed.complete(Thread.currentThread())
                socket.close()
            })
        try {
            runBlocking(Dispatchers.IO) { transport.pairDevice(code.device, code.secret) {} }
            val caller = Thread.currentThread()
            transport.disconnect()
            scope.cancel()
            assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
            assertFalse(transport.isTargetDevice(code.device.id))
            assertFalse(transport.sendMessage(ProtocolMessage.ClipboardSync("must not send", sourceDevice = "Test")))
            runBlocking { assertNotSame(caller, withTimeout(5000) { closed.await() }) }
        } finally { transport.stop(); scope.cancel() }
    }

    private lateinit var peerDirectory: File
    private fun withMac(files: Boolean = false, test: (PairingCode) -> Unit) {
        val working = File(System.getProperty("user.dir"))
        val root = if (File(working, "mac/macbridge.py").exists()) working else working.parentFile
        val directory = Files.createTempDirectory("macbridge-integration-").toFile()
        peerDirectory = directory
        val process = ProcessBuilder(listOf("python3", File(root, "mac/macbridge.py").absolutePath,
            "--state-dir", directory.absolutePath, "--host", "127.0.0.1", "--port", "0",
            "--address", "127.0.0.1", "--headless", "--echo") + (if (files) listOf("--files") else emptyList())).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val line = executor.submit<String> { process.inputStream.bufferedReader().readLine() ?: error("Mac companion exited before startup") }
                .get(20, TimeUnit.SECONDS)
            check(line.startsWith("PAIRING_URI=")) { "Mac companion failed to start" }
            test(PairingCode.parse(line.removePrefix("PAIRING_URI=")))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pair exchange unicode clipboard reconnect and reject consumed secret`() = withMac { code ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = IdentityManager(context, allowSoftwareFallback = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var received = CompletableDeferred<ProtocolMessage>()
        val transport = SecureTransport(identity, scope, { message, _ -> received.complete(message) }, {})
        try {
            runBlocking(Dispatchers.IO) {
                var persisted: PairedDevice? = null
                val verified = transport.pairDevice(code.device, code.secret) { persisted = it }
                assertEquals(verified, persisted)
                assertTrue(verified.pinnedPublicKey.isNotBlank())
                assertFalse(verified.allowClipboard)
                assertFalse(verified.allowFileTransfer)
                assertFalse(verified.allowNotifications)
                val text = "Phone → Mac 🌉\nSecond line"
                assertFalse(transport.sendMessage(ProtocolMessage.ClipboardSync(text, sourceDevice = "Test Phone"), "another-mac"))
                assertTrue(transport.sendMessage(ProtocolMessage.ClipboardSync(text, sourceDevice = "Test Phone")))
                assertEquals(text, (withTimeout(5000) { received.await() } as ProtocolMessage.ClipboardSync).content)
                transport.disconnect()
                delay(200)
                assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
                received = CompletableDeferred()
                transport.connectToDevice(verified)
                withTimeout(10000) { transport.connectionState.first { it is ConnectionState.Connected } }
                assertTrue(transport.sendMessage(ProtocolMessage.ClipboardSync("After reconnect", sourceDevice = "Test Phone")))
                assertEquals("After reconnect", (withTimeout(5000) { received.await() } as ProtocolMessage.ClipboardSync).content)
                transport.disconnect()
                var savedAgain = false
                try {
                    transport.pairDevice(code.device, code.secret) { savedAgain = true }
                    fail("Consumed pairing code must be rejected")
                } catch (_: Exception) {
                    assertFalse(savedAgain)
                    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
                }
            }
        } finally {
            transport.stop()
            scope.cancel()
        }
    }

    @Test
    fun `real TLS file transfer waits for verified delivery and cancelled partial is removed`() = withMac(files = true) { code ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val acknowledgements = kotlinx.coroutines.channels.Channel<ProtocolMessage.FileAck>(4)
        val transport = SecureTransport(IdentityManager(context, true), scope,
            { message, _ -> if (message is ProtocolMessage.FileAck) acknowledgements.send(message) }, {})
        try { runBlocking(Dispatchers.IO) {
            transport.pairDevice(code.device, code.secret) {}
            val bytes = ByteArray(80000) { (it % 256).toByte() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertTrue(transport.sendMessage(ProtocolMessage.FileInit("real-file", "🌉 test.bin", bytes.size.toLong(), hash)))
            assertEquals("READY", withTimeout(5000) { acknowledgements.receive() }.status)
            for (i in 0..1) {
                val offset = i * 65536
                val data = bytes.copyOfRange(offset, minOf(offset + 65536, bytes.size))
                assertTrue(transport.sendMessage(ProtocolMessage.FileChunk("real-file", i, 2, offset.toLong(),
                    android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP), data.size)))
                val ack = withTimeout(5000) { acknowledgements.receive() }
                assertEquals(if (i == 1) "COMPLETED" else "IN_PROGRESS", ack.status)
                if (i == 1) assertEquals(hash, ack.sha256Checksum)
            }
            val folder = File(peerDirectory, "ReceivedFiles")
            assertArrayEquals(bytes, folder.listFiles()!!.single().readBytes())
            transport.sendMessage(ProtocolMessage.FileInit("cancel-file", "cancel.bin", bytes.size.toLong(), hash))
            assertEquals("READY", withTimeout(5000) { acknowledgements.receive() }.status)
            transport.sendMessage(ProtocolMessage.FileCancel("cancel-file"))
            assertEquals("CANCELLED", withTimeout(5000) { acknowledgements.receive() }.status)
            assertEquals(1, folder.listFiles()!!.size)
        } } finally { transport.stop(); scope.cancel() }
    }

    @Test
    fun `wrong TLS pin is rejected without persisting a peer`() = withMac { code ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = SecureTransport(IdentityManager(context, true), scope, { _, _ -> }, {})
        try {
            runBlocking(Dispatchers.IO) {
                var saved = false
                val wrongPin = "00:".repeat(31) + "00"
                try {
                    transport.pairDevice(code.device.copy(fingerprint = wrongPin), code.secret) { saved = true }
                    fail("Wrong pin must be rejected")
                } catch (_: javax.net.ssl.SSLHandshakeException) {
                    assertFalse(saved)
                    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
                }
                // A failed TLS attempt must not consume the valid pairing code.
                transport.pairDevice(code.device, code.secret) { saved = true }
                assertTrue(saved)
            }
        } finally {
            transport.stop()
            scope.cancel()
        }
    }
}
