package com.example

import android.app.Application
import com.example.manager.DiagnosticLogger
import com.example.manager.TransferCheckpoint
import com.example.model.ProtocolMessage
import com.example.network.ProtocolCodec
import com.example.network.StrictJson
import com.example.crypto.IdentityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecurityReviewTest {
    @Test fun `malformed frames cannot leak clipboard reply or secret content to logs`() {
        DiagnosticLogger.clear()
        ShadowLog.clear()
        val privateFixture = "PRIVATE_FIXTURE_MESSAGE_AND_SECRET"
        // Confirm why using the platform parser exception as a log is unsafe.
        val platformError = assertThrows(Exception::class.java) {
            JSONObject("{\"content\":\"$privateFixture")
        }
        assertTrue(platformError.message.orEmpty().contains(privateFixture))
        assertNull(ProtocolCodec.decode("{\"type\":\"CLIPBOARD\",\"content\":\"$privateFixture"))
        assertNull(ProtocolCodec.decode("{\"type\":{},\"replyText\":\"$privateFixture\"}"))
        assertFalse(DiagnosticLogger.exportFormatted().contains(privateFixture))
        assertTrue(ShadowLog.getLogs().none { it.msg.contains(privateFixture) })
        assertTrue(DiagnosticLogger.logsFlow.value.isNotEmpty())
    }

    @Test fun `ambiguous syntax duplicate keys and trailing documents are rejected`() {
        for (payload in listOf("{\"type\":\"HEARTBEAT\",\"type\":\"CLIPBOARD\"}",
            "{\"extra\":{\"allow\":false,\"allow\":true}}", "{type:'HEARTBEAT'}",
            "{/*comment*/\"type\":\"HEARTBEAT\"}", "{\"n\":NaN}", "{\"n\":1e400}",
            "{\"type\":\"HEARTBEAT\",\"seq\":1} {}", "[]")) {
            assertNull(ProtocolCodec.decode(payload))
        }
    }

    @Test fun `deep network and saved JSON is rejected without stack overflow`() {
        val path = File.createTempFile("security-review", ".json")
        try {
            for (depth in listOf(33, 4000)) {
                val payload = "{\"extra\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
                assertThrows(Exception::class.java) { StrictJson.objectFrom(payload) }
                assertNull(ProtocolCodec.decode(payload))
                path.writeText(payload)
                assertEquals(0, TransferCheckpoint(path).load().length())
            }
            val checkpoint = TransferCheckpoint(path)
            checkpoint.save(JSONObject().put("incoming", JSONObject.NULL))
            assertEquals(1, checkpoint.load().getInt("version"))
        } finally { path.delete() }
    }

    @Test fun `numeric and boolean strings fractional sizes and overflow cannot coerce`() {
        for (payload in listOf(
            "{\"type\":\"HEARTBEAT\",\"seq\":\"1\"}",
            "{\"type\":\"HEARTBEAT\",\"seq\":1,\"isAck\":\"true\"}",
            "{\"type\":\"HEARTBEAT\",\"seq\":1.5}",
            "{\"type\":\"HEARTBEAT\",\"seq\":9223372036854775808}",
            "{\"type\":\"FILE_INIT\",\"transferId\":\"x\",\"fileName\":\"file\",\"fileSize\":0.5,\"sha256Checksum\":\"hash\"}",
            "{\"type\":\"FILE_CHUNK\",\"transferId\":\"x\",\"chunkIndex\":2147483648,\"totalChunks\":1,\"offset\":0,\"dataBase64\":\"\",\"chunkLength\":0}",
            "{\"type\":\"FILE_CANCEL\",\"transferId\":null}")) {
            assertNull(ProtocolCodec.decode(payload))
        }
    }

    @Test fun `all current protocol messages still roundtrip Unicode and optional fields`() {
        val messages = listOf(ProtocolMessage.Hello("id", "🌉", "pin"),
            ProtocolMessage.PairRequest("id", "phone", "pin", "fixture", 1),
            ProtocolMessage.PairResponse(true, "id", "pin"),
            ProtocolMessage.Heartbeat(1, 1), ProtocolMessage.ClipboardSync("世界 🌉 " + "[{\\\"".repeat(1000), 1, "mac"),
            ProtocolMessage.FileInit("id", "世界.txt", 0, "checksum"),
            ProtocolMessage.FileChunk("id", 0, 1, 0, "", 0),
            ProtocolMessage.FileAck("id", 0, "COMPLETED", "checksum"), ProtocolMessage.FileCancel("id"),
            ProtocolMessage.NotificationMirror("n", "pkg", "app", "世界", "🌉", 1),
            ProtocolMessage.NotificationAction("n", "REPLY", "世界", "fixture"))
        for (message in messages) assertEquals(message, ProtocolCodec.decode(ProtocolCodec.encode(message)))
        val frame = ProtocolCodec.encode(ProtocolMessage.Heartbeat(1, 1))
        assertNotNull(ProtocolCodec.decode(frame.dropLast(1) + ",\"extension\":{\"future\":[1,2]}}"))
    }

    @Test fun `unused cloud initialization and secret packaging are absent from runtime`() {
        assertTrue(BuildConfig::class.java.declaredFields.none { field ->
            listOf("KEY", "TOKEN", "SECRET").any { field.name.uppercase().contains(it) }
        })
        for (name in listOf("com.google.firebase.provider.FirebaseInitProvider", "com.google.firebase.appcheck.FirebaseAppCheck"))
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
    }

    @Test fun `lost production key cannot silently rotate an existing device identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("macbridge_identity_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("device_id", "existing-fixture-id").commit()
        val missingKeyStore = java.security.KeyStore.getInstance("PKCS12").apply { load(null) }
        try {
            val error = assertThrows(IllegalStateException::class.java) { IdentityManager(context, missingKeyStore) }
            assertTrue(error.cause?.message.orEmpty().contains("explicit identity recovery"))
            assertEquals("existing-fixture-id", prefs.getString("device_id", null))
            assertFalse(missingKeyStore.containsAlias("macbridge_identity_key"))
            assertFalse(prefs.contains("sw_priv_key"))
        } finally { prefs.edit().clear().commit() }
    }
}
