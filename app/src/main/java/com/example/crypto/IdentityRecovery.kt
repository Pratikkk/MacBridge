package com.example.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class IdentityFailure { MISSING, UNAVAILABLE, RESET_INCOMPLETE }

class IdentityAccessException(val reason: IdentityFailure, cause: Throwable? = null) :
    IllegalStateException("Android identity access requires recovery", cause)

internal fun identityAccessFailure(error: Exception): IdentityAccessException {
    if (error is IdentityAccessException) return error
    // Only definite key loss is destructive-recovery eligible. Provider/read errors
    // are unavailable until retry proves otherwise; never infer loss from text.
    var cause: Throwable? = error
    repeat(16) {
        if (cause is KeyPermanentlyInvalidatedException) return IdentityAccessException(IdentityFailure.MISSING, error)
        cause = cause?.cause
    }
    return IdentityAccessException(IdentityFailure.UNAVAILABLE, error)
}

sealed interface IdentityStartup<out T> {
    data object Loading : IdentityStartup<Nothing>
    data class Ready<T>(val value: T) : IdentityStartup<T>
    data class Recovery(val reason: IdentityFailure) : IdentityStartup<Nothing>
}

/** Event-driven: no polling, automatic retries or automatic destructive recovery. */
internal class IdentityRecoveryController<T>(
    private val scope: CoroutineScope,
    private val pendingReset: () -> Boolean,
    private val initialize: suspend () -> T,
    private val reset: suspend () -> Unit
) {
    private val mutable = MutableStateFlow<IdentityStartup<T>>(IdentityStartup.Loading)
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    fun retry() = perform(false)
    fun resetConfirmed() = perform(true)

    private fun perform(resetRequested: Boolean) {
        if (mutable.value is IdentityStartup.Ready) return
        if (resetRequested && (mutable.value as? IdentityStartup.Recovery)?.reason !in
            listOf(IdentityFailure.MISSING, IdentityFailure.RESET_INCOMPLETE)) return
        if (!mutex.tryLock()) return
        mutable.value = IdentityStartup.Loading
        scope.launch(Dispatchers.IO) {
            try {
                if (resetRequested) reset()
                if (pendingReset()) mutable.value = IdentityStartup.Recovery(IdentityFailure.RESET_INCOMPLETE)
                else mutable.value = IdentityStartup.Ready(initialize())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = IdentityStartup.Recovery(if (pendingReset()) IdentityFailure.RESET_INCOMPLETE
                    else identityAccessFailure(error).reason)
            } finally { mutex.unlock() }
        }
    }
}

/** Each step is restart-safe. The journal is cleared only after all changes succeed. */
internal suspend fun completeIdentityReset(
    markPending: () -> Unit,
    prepareKey: () -> Unit,
    clearPeerTrust: suspend () -> Unit,
    invalidateTransfers: () -> Unit,
    resetMetadata: () -> Unit,
    clearPending: () -> Unit
) {
    markPending()
    prepareKey()
    clearPeerTrust()
    invalidateTransfers()
    resetMetadata()
    clearPending()
}
