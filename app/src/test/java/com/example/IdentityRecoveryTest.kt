package com.example

import android.app.Application
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.*
import com.example.data.MacBridgeDatabase
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.*
import java.security.cert.Certificate
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("macbridge_identity_prefs", Context.MODE_PRIVATE)
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    // An isolated provider fixture with real EC signatures; no Android live key access.
    private class FixtureStore(var pair: KeyPair? = null) : KeyStoreSpi() {
        var unavailable = false
        var invalidated = false
        var deletes = 0
        private fun accessible() { if (unavailable) throw KeyStoreException("PRIVATE_FIXTURE_PROVIDER_DETAIL") }
        override fun engineContainsAlias(alias: String): Boolean { accessible(); return pair != null }
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            accessible()
            if (invalidated) throw KeyPermanentlyInvalidatedException()
            return pair?.private
        }
        override fun engineGetCertificate(alias: String): Certificate? {
            accessible()
            val pub = pair?.public ?: return null
            return object : Certificate("EC") {
                override fun getPublicKey() = pub
                override fun getEncoded() = pub.encoded
                override fun verify(key: PublicKey) {}
                override fun verify(key: PublicKey, provider: String) {}
                override fun toString() = "Synthetic certificate"
            }
        }
        override fun engineDeleteEntry(alias: String) { accessible(); deletes++; pair = null; invalidated = false }
        override fun engineGetCertificateChain(alias: String) = engineGetCertificate(alias)?.let { arrayOf(it) }
        override fun engineGetCreationDate(alias: String) = Date(0)
        override fun engineAliases() = java.util.Collections.enumeration(listOf("macbridge_identity_key"))
        override fun engineSize() = if (pair == null) 0 else 1
        override fun engineIsKeyEntry(alias: String) = pair != null
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate) = "macbridge_identity_key"
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = error("unused")
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
        fun keyStore(): KeyStore = object : KeyStore(this, object : Provider("Fixture", 1.0, "Isolated") {}, "Fixture") {}.apply { load(null) }
    }

    @Test fun `first use generates once and healthy restart preserves signing identity`() {
        prefs.edit().clear().commit()
        val fixture = FixtureStore()
        var generated = 0
        val generate = { generated++; fixture.pair = pair() }
        val first = IdentityManager(context, fixture.keyStore(), generate)
        val id = first.deviceId
        val pin = first.getFingerprint()
        val second = IdentityManager(context, fixture.keyStore(), generate)
        assertEquals(id, second.deviceId); assertEquals(pin, second.getFingerprint())
        assertEquals(1, generated)
    }

    @Test fun `missing alias preserves all metadata and never generates a replacement`() {
        prefs.edit().clear().putString("device_id", "existing 世界").putString("device_name", "My phone 🌉").commit()
        val before = prefs.all
        var generates = 0
        val fixture = FixtureStore()
        val failure = assertThrows(IdentityAccessException::class.java) { IdentityManager(context, fixture.keyStore()) { generates++ } }
        assertEquals(IdentityFailure.MISSING, failure.reason)
        assertEquals(before, prefs.all); assertEquals(0, generates); assertEquals(0, fixture.deletes)
    }

    @Test fun `durable created marker prevents rotation even before device ID was assigned`() {
        prefs.edit().clear().putBoolean("identity_created", true).commit()
        var generated = 0
        val fixture = FixtureStore()
        assertEquals(IdentityFailure.MISSING, assertThrows(IdentityAccessException::class.java) {
            IdentityManager(context, fixture.keyStore()) { generated++ }
        }.reason)
        assertEquals(0, generated); assertFalse(prefs.contains("device_id"))
    }

    @Test fun `provider outage is retryable and permanent invalidation is definite loss`() {
        prefs.edit().clear().putString("device_id", "old").commit()
        val fixture = FixtureStore(pair())
        val key = fixture.pair!!.public.encoded
        fixture.unavailable = true
        val failure = assertThrows(IdentityAccessException::class.java) { IdentityManager(context, fixture.keyStore()) }
        assertEquals(IdentityFailure.UNAVAILABLE, failure.reason)
        assertFalse(failure.message.orEmpty().contains("PRIVATE_FIXTURE"))
        fixture.unavailable = false
        assertArrayEquals(key, IdentityManager(context, fixture.keyStore()).getPublicKey()!!.encoded)
        fixture.invalidated = true
        assertEquals(IdentityFailure.MISSING, assertThrows(IdentityAccessException::class.java) { IdentityManager(context, fixture.keyStore()) }.reason)
        assertEquals(0, fixture.deletes)
    }

    @Test fun `confirmed replacement is validated once and resumed reset keeps that key`() {
        prefs.edit().clear().putString("device_id", "old").commit()
        val fixture = FixtureStore(pair()).apply { invalidated = true }
        var generated = 0
        val generate = { generated++; fixture.pair = pair() }
        IdentityManager.prepareReplacement(context, fixture.keyStore(), generate)
        val replacement = fixture.pair!!.public.encoded
        IdentityManager.prepareReplacement(context, fixture.keyStore(), generate)
        assertEquals(1, generated); assertEquals(1, fixture.deletes)
        assertArrayEquals(replacement, fixture.pair!!.public.encoded)
        assertEquals("old", prefs.getString("device_id", null))
    }

    @Test fun `startup retry is event driven and never resets unavailable identity`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var available = false; var resets = 0; var attempts = 0
        val controller = IdentityRecoveryController(scope, { false }, {
            attempts++
            if (!available) throw IdentityAccessException(IdentityFailure.UNAVAILABLE)
            "healthy"
        }, { resets++ })
        try {
            controller.retry()
            withTimeout(5000) { controller.state.first { it is IdentityStartup.Recovery } }
            controller.resetConfirmed()
            assertEquals(0, resets); assertEquals(1, attempts)
            available = true; controller.retry()
            assertEquals("healthy", (withTimeout(5000) { controller.state.first { it is IdentityStartup.Ready } } as IdentityStartup.Ready).value)
            controller.retry(); controller.resetConfirmed()
            assertEquals(2, attempts); assertEquals(0, resets)
        } finally { scope.cancel() }
    }

    @Test fun `interrupted reset gates startup until explicit continuation and ignores duplicate clicks`() = runBlocking {
        var pending = true; var initialized = 0; var resets = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val controller = IdentityRecoveryController(scope, { pending }, { initialized++; "ready" }, {
            resets++; gate.await(); pending = false
        })
        try {
            controller.retry()
            assertEquals(IdentityFailure.RESET_INCOMPLETE, (withTimeout(5000) { controller.state.first { it is IdentityStartup.Recovery } } as IdentityStartup.Recovery).reason)
            assertEquals(0, initialized)
            controller.resetConfirmed(); controller.resetConfirmed(); controller.retry()
            gate.complete(Unit)
            withTimeout(5000) { controller.state.first { it is IdentityStartup.Ready } }
            assertEquals(1, resets); assertEquals(1, initialized)
        } finally { scope.cancel() }
    }

    @Test fun `failed durable reset marker prevents any destructive step`() = runBlocking {
        var changed = false
        try {
            completeIdentityReset({ error("journal unavailable") }, { changed = true },
                { changed = true }, { changed = true }, { changed = true }, { changed = true })
            fail("journal failure expected")
        } catch (_: IllegalStateException) {}
        assertFalse(changed)
    }

    @Test fun `failure and process cancellation at every reset stage retain the durable gate`() = runBlocking {
        for (failureAt in 1..5) for (cancelled in listOf(false, true)) {
            var pending = false; var step = 0; var finished = false
            fun stage() {
                step++
                if (step == failureAt) {
                    if (cancelled) throw CancellationException("shutdown")
                    else throw IllegalStateException("synthetic failure")
                }
            }
            try {
                completeIdentityReset({ pending = true }, ::stage, { stage() }, ::stage, ::stage,
                    { stage(); pending = false; finished = true })
                fail("failure expected")
            } catch (_: IllegalStateException) {} catch (_: CancellationException) {}
            assertTrue(pending); assertFalse(finished)
        }
    }

    @Test fun `reset storage clears trust and atomic checkpoints but retains verified files and history`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MacBridgeDatabase::class.java).build()
        val store = IdentityResetStore(context) { db }
        val received = File(context.filesDir, "received_files/fixture.verified").apply { parentFile!!.mkdirs(); writeText("verified 世界") }
        val external = File(context.cacheDir, "saved-fixture.txt").apply { writeText("exported") }
        val folder = File(context.filesDir, "transfer_state").apply { mkdirs() }
        for (name in listOf("incoming.json", "outgoing.json")) for (suffix in listOf("", ".bak", ".new")) File(folder, name + suffix).writeText("synthetic metadata")
        prefs.edit().clear().putString("device_id", "old").putString("device_name", "世界").commit()
        context.getSharedPreferences("macbridge_app_filters", Context.MODE_PRIVATE).edit().putBoolean("previews", true).commit()
        try {
            db.pairedDeviceDao().insertOrUpdate(PairedDevice("mac", "private fixture", "pin", "key", "", allowNotifications = true))
            db.clipboardDao().insert(ClipboardItem(content = "retained", sourceDeviceName = "fixture"))
            db.notificationDao().insert(MirroredNotification("n", "pkg", "app", "title", "text"))
            db.fileTransferDao().insertOrUpdate(FileTransferItem("complete", "世界.txt", 14, direction = TransferDirection.INCOMING,
                status = TransferStatus.COMPLETED, sha256Checksum = "synthetic", filePath = received.path))
            db.fileTransferDao().insertOrUpdate(FileTransferItem("paused", "file", 10, direction = TransferDirection.OUTGOING,
                status = TransferStatus.PAUSED, sha256Checksum = "synthetic"))
            completeIdentityReset(store::begin, {}, store::clearTrust, store::invalidateTransfers, store::resetMetadata, store::finish)
            assertFalse(store.pending)
            assertTrue(db.pairedDeviceDao().getAllPairedDevices().first().isEmpty())
            assertTrue(db.notificationDao().getAllNotifications().first().isEmpty())
            assertEquals(1, db.clipboardDao().getAllClips().first().size)
            assertEquals(TransferStatus.COMPLETED, db.fileTransferDao().getTransfer("complete")!!.status)
            assertEquals(TransferStatus.FAILED, db.fileTransferDao().getTransfer("paused")!!.status)
            assertEquals("verified 世界", received.readText()); assertEquals("exported", external.readText())
            assertTrue(folder.listFiles().orEmpty().isEmpty())
            assertNotEquals("old", prefs.getString("device_id", null)); assertEquals("世界", prefs.getString("device_name", null))
            assertTrue(context.getSharedPreferences("macbridge_app_filters", Context.MODE_PRIVATE).all.isEmpty())
        } finally { db.close(); received.delete(); external.delete(); folder.deleteRecursively() }
    }
}
