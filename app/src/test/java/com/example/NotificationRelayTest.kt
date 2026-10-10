package com.example

import com.example.manager.*
import com.example.model.ProtocolMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRelayTest {
    private val original = NotificationDestination("mac", "pin", 1, true)
    private fun alert(id: String = "key") = ProtocolMessage.NotificationMirror(id, "com.chat", "Chat 🌉", "Hello", "Private text")

    @Test fun `redacted default secret exclusion bounds and Unicode preserve privacy`() {
        val redacted = notificationPayload("key", "pkg", "🌉 Chat", "OTP", "123456", false, false, false, false)!!
        assertEquals("New notification", redacted.title)
        assertFalse(redacted.text.contains("123456"))
        for (flags in listOf(Triple(true,false,false), Triple(false,true,false), Triple(false,false,true))) {
            assertNull(notificationPayload("key", "pkg", "Chat", "Hello", "Text", flags.first, flags.second, flags.third, true))
        }
        assertNull(notificationPayload("x".repeat(513), "pkg", "Chat", "Title", "Body", false,false,false,true))
        val preview = notificationPayload("key", "pkg", "🌉 Chat", "x".repeat(255)+"🌉", "x".repeat(3000), false,false,false,true)!!
        assertEquals(255, preview.title.length)
        assertEquals(2048, preview.text.length)
        assertFalse(preview.hasReplyAction)
    }

    @Test fun `revocation identity change disconnect and privacy epoch discard queued content`() = runTest {
        var destination: NotificationDestination? = original
        var appAllowed = true
        val sent = mutableListOf<ProtocolMessage>()
        val relay = NotificationRelay(backgroundScope, { destination }, { appAllowed }, { msg,_ -> sent.add(msg); true })
        relay.offer(original, alert()); destination = original.copy(allowed = false); runCurrent(); assertTrue(sent.isEmpty())
        destination = original; relay.offer(original, alert()); appAllowed = false; runCurrent(); assertTrue(sent.isEmpty())
        appAllowed = true; relay.offer(original, alert()); destination = original.copy(pin = "changed"); runCurrent(); assertTrue(sent.isEmpty())
        destination = original; relay.offer(original, alert()); destination = original.copy(session = 2); runCurrent(); assertTrue(sent.isEmpty())
        destination = original; relay.offer(original, alert()); destination = null; runCurrent(); assertTrue(sent.isEmpty())
        destination = original; relay.offer(original, alert()); relay.invalidate(); runCurrent(); assertTrue(sent.isEmpty())
        relay.offer(original, alert()); runCurrent(); assertEquals(1, sent.size)
    }

    @Test fun `duplicate updates and removals are ordered without exposing unmirrored keys`() = runTest {
        val sent = mutableListOf<ProtocolMessage>()
        val relay = NotificationRelay(backgroundScope, { original }, { true }, { msg,_ -> sent.add(msg); true })
        relay.offer(original, ProtocolMessage.NotificationAction("unknown", "REMOVE"))
        relay.offer(original, alert()); relay.offer(original, alert().copy(timestamp = 42))
        relay.offer(original, alert().copy(text = "Changed"))
        relay.offer(original, ProtocolMessage.NotificationAction("key", "REMOVE"))
        relay.offer(original, ProtocolMessage.NotificationAction("key", "REMOVE"))
        runCurrent()
        assertEquals(listOf("NOTIFICATION","NOTIFICATION","NOTIFICATION_ACTION"), sent.map { it.type })
        relay.offer(original, ProtocolMessage.NotificationAction("", "CLEAR")); runCurrent()
        assertEquals("CLEAR", (sent.last() as ProtocolMessage.NotificationAction).actionType)
    }

    @Test fun `flood is bounded and clear can follow revocation without replay after restart`() = runTest {
        var allowed = true
        val sent = mutableListOf<ProtocolMessage>()
        val relay = NotificationRelay(backgroundScope, { original.copy(allowed = allowed) }, { true }, { msg,_ -> sent.add(msg); true })
        assertEquals(64, (0 until 1000).count { relay.offer(original, alert("key$it")) })
        runCurrent(); assertEquals(64, sent.size)
        allowed = false
        relay.offer(original, alert("new"))
        relay.offer(original, ProtocolMessage.NotificationAction("com.chat", "CLEAR"))
        runCurrent(); assertEquals(65, sent.size)
        assertTrue(sent.last() is ProtocolMessage.NotificationAction)
    }
}
