package com.example.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** UI queries stop after a short navigation grace period; transport has its own lifetime. */
internal fun <T> Flow<T>.uiStateIn(scope: CoroutineScope, initial: T) =
    stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), initial)
