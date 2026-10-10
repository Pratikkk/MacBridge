package com.example

import com.example.manager.*
import org.junit.Assert.*
import org.junit.Test

class NotificationActionsTest {
    private val target = NotificationDestination("mac", "pin", 10, true)
    @Test fun `dismiss is generation bound actually delivered one use and session scoped`() {
        var serial = 0
        val actions = NotificationActions { "token-${serial++}" }
        val first = actions.observe("key", "com.chat", 100, true)!!
        assertNull(actions.take("key", first, target)) // Observed but not sent.
        actions.bind("key", first, target)
        actions.clearTarget("other-mac") // Unrelated Mac revocation must not invalidate this session.
        for (changed in listOf(target.copy(id="other"), target.copy(pin="new"), target.copy(session=11), target.copy(allowed=false)))
            assertNull(actions.take("key", first, changed))
        val second = actions.observe("key", "com.chat", 101, true)!!
        actions.bind("key", first, target) // Late send acknowledgement cannot bind a new generation.
        assertNull(actions.take("key", first, target))
        assertNull(actions.take("key", second, target))
        actions.bind("key", second, target)
        val accepted = actions.take("key", second, target)!!
        assertEquals(101L, accepted.posted)
        assertNull(actions.take("key", second, target))
    }
    @Test fun `revocation removal restart nonclearable and flooded handles cannot be reused`() {
        var serial = 0
        val actions = NotificationActions { "token-${serial++}" }
        val token = actions.observe("key", "com.chat", 100, true)!!
        actions.bind("key", token, target); actions.clear("com.other")
        assertNotNull(actions.take("key", token, target))
        val again = actions.observe("key", "com.chat", 100, true)!!
        actions.bind("key", again, target); actions.clear("com.chat")
        assertNull(actions.take("key", again, target))
        assertNull(actions.observe("ongoing", "pkg", 100, false))
        val first = actions.observe("0", "pkg", 100, true)!!; actions.bind("0", first, target)
        repeat(1000) { actions.observe("key-$it", "pkg", 100, true) }
        assertNull(actions.take("0", first, target))
        val last = actions.observe("last", "pkg", 100, true)!!; actions.bind("last", last, target)
        actions.remove("last"); assertNull(actions.take("last", last, target))
        val scoped = actions.observe("scoped", "pkg", 100, true)!!; actions.bind("scoped", scoped, target)
        actions.clearTarget(target.id); assertNull(actions.take("scoped", scoped, target))
        val after = actions.observe("after", "pkg", 100, true)!!; actions.bind("after", after, target)
        actions.clear(); assertNull(actions.take("after", after, target))
    }
    @Test fun `live system notification must match original generation and be clearable`() {
        val record = NotificationGeneration("key", "com.chat", 100, "token")
        assertTrue(notificationStillDismissible(record, "com.chat", 100, true, false, false, false))
        assertFalse(notificationStillDismissible(record, "other", 100, true, false, false, false))
        assertFalse(notificationStillDismissible(record, "com.chat", 101, true, false, false, false))
        assertFalse(notificationStillDismissible(record, null, null, true, false, false, false))
        for (flags in listOf(listOf(false,false,false,false), listOf(true,true,false,false), listOf(true,false,true,false), listOf(true,false,false,true)))
            assertFalse(notificationStillDismissible(record,"com.chat",100,flags[0],flags[1],flags[2],flags[3]))
    }
}
