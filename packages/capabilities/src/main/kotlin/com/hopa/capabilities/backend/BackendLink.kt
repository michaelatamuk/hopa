package com.hopa.capabilities.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's only view of the gateway socket. The Android layer holds one of
 * these and never sees OkHttp, so the connection is testable on the JVM.
 */
interface BackendLink {
    val state: StateFlow<BackendState>
    var onTask: (suspend (BackendEnvelope) -> Unit)?
    fun start(scope: CoroutineScope)
    suspend fun stop()
    suspend fun send(frame: BackendEnvelope)
}
