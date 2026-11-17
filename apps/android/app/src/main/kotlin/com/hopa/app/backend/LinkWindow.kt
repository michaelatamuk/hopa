package com.hopa.app.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one closing clock around a socket: the 30 s foreground grace or the 45 s
 * wake idle window. Arming replaces any earlier clock, so the two can never run
 * against each other. The owner decides whether a firing clock may still close.
 */
internal class LinkWindow(
    private val scope: CoroutineScope,
    private val gate: Mutex,
    private val close: suspend () -> Unit,
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun arm(delayMs: Long, allowed: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            gate.withLock {
                if (allowed()) {
                    close()
                }
            }
        }
    }
}
