package com.example

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MacBridgeDatabase
import com.example.model.*
import com.example.ui.uiStateIn
import com.example.ui.screens.bridge.HomeWorkspace
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPerformanceTest {
    @get:Rule val compose = createComposeRule()

    private fun item(id: String, direction: TransferDirection, status: TransferStatus, time: Long) =
        FileTransferItem(id, "🌉 $id.txt", 100, direction = direction, status = status,
            sha256Checksum = "hash", timestamp = time)

    @Test fun `Home query bounds old history and preserves paused and simultaneous transfers`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MacBridgeDatabase::class.java).build()
        try {
            val dao = db.fileTransferDao()
            repeat(500) { dao.insertOrUpdate(item("old$it", TransferDirection.INCOMING, TransferStatus.COMPLETED, it.toLong())) }
            val incoming = item("receiving", TransferDirection.INCOMING, TransferStatus.TRANSFERRING, 600)
            val paused = item("paused", TransferDirection.OUTGOING, TransferStatus.PAUSED, 601)
            val sending = item("sending", TransferDirection.OUTGOING, TransferStatus.PENDING, 602)
            listOf(incoming, paused, sending).forEach { dao.insertOrUpdate(it) }
            assertEquals(listOf("sending", "paused", "receiving"), withTimeout(5_000) { dao.getHomeTransfers().first() }.map { it.transferId })
            dao.insertOrUpdate(incoming.copy(status = TransferStatus.COMPLETED))
            dao.insertOrUpdate(paused.copy(status = TransferStatus.FAILED, errorMessage = "Cancelled"))
            dao.insertOrUpdate(sending.copy(status = TransferStatus.COMPLETED))
            assertEquals(listOf("sending", "receiving"), withTimeout(5_000) { dao.getHomeTransfers().first() }.map { it.transferId })
            assertEquals(503, dao.getAllTransfers().first().size) // History/recovery remains intact.
        } finally { db.close() }
    }

    @Test fun `UI database subscriptions stop when unused and refresh on return`() = runTest {
        val changes = MutableStateFlow(1)
        var starts = 0
        var stops = 0
        val state = flow {
            starts++
            try { emitAll(changes) } finally { stops++ }
        }.uiStateIn(backgroundScope, 0)
        runCurrent()
        assertEquals(0, starts)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(1, state.value)
        first.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, stops)
        changes.value = 7
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, starts)
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(2, starts)
        assertEquals(7, state.value)
        second.cancel()
    }

    @Test fun `Home is idle without emissions and drops lifecycle subscriptions in background`() {
        val transfers = MutableStateFlow(listOf(item("incoming", TransferDirection.INCOMING, TransferStatus.TRANSFERRING, 1)))
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        var compositions = 0
        var bytes = -1L
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val values by transfers.collectAsStateWithLifecycle()
                val snapshot = values // Read in this composition scope, not only inside the effect.
                SideEffect { compositions++; bytes = snapshot.single().transferredBytes }
                MyApplicationTheme { HomeWorkspace(ConnectionState.Disconnected, emptyList(), {}, {}, {}, {}, transfers = snapshot) }
            }
        }
        compose.waitForIdle()
        val idleCount = compositions
        compose.mainClock.advanceTimeBy(20_000)
        compose.runOnIdle {
            assertEquals(idleCount, compositions)
            assertEquals(1, transfers.subscriptionCount.value)
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()
        assertEquals(0, transfers.subscriptionCount.value)
        transfers.value = transfers.value.map { it.copy(transferredBytes = 50) }
        compose.waitForIdle()
        assertEquals(0L, bytes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            transfers.subscriptionCount.value == 1
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(50L, bytes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        compose.waitForIdle()
        assertEquals(0, transfers.subscriptionCount.value)
    }
}
